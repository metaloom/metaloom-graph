package io.metaloom.graph.core.migration;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import io.metaloom.graph.core.nativelib.NativeLib;

/**
 * Copies a closed store into the migration's working directory without materialising its holes.
 *
 * <p>
 * Segment files are sparse: a node segment spans a gigabyte and may hold a few pages. {@code Files.copy} reads the
 * holes as zeros and, on most filesystems, writes them as zeros, so a 20 GiB nominal store with 50 MiB in it would
 * need 20 GiB free to migrate. Two ways round it, in order:
 * </p>
 *
 * <ol>
 * <li><b>A reflink</b> ({@code FICLONE}), where the filesystem has one — btrfs, xfs with reflink, bcachefs. The copy
 * shares every block with the original until a step writes to it, so it costs nothing and is instant. This is the
 * first caller {@link NativeLib#reflink} has had.</li>
 * <li><b>A block-wise copy that skips zeros</b>, everywhere else: ask the filesystem which ranges hold data
 * ({@code lseek(SEEK_DATA/SEEK_HOLE)}, {@link NativeLib#dataExtents}) and read only those, write only the 4 KiB blocks
 * in them that are not all zero, and extend the file to its length with a truncate, which leaves a hole. Without the
 * native layer it reads the whole nominal size instead — correct, and about a second per few GiB of holes — and in
 * both cases a block that was allocated and zero becomes a hole, which is the same bytes.</li>
 * </ol>
 *
 * <p>
 * The top-level {@code LOCK} and {@code wal/} are not copied. The lock is per process; the log is empty past its
 * checkpoint by the time a copy is made ({@link LogInspector}), and its records are differences against the old
 * layout that nothing may ever apply to the new one. The copy opens with a fresh log at the checkpoint LSN.
 * </p>
 */
final class StoreCopier {

	private static final int BLOCK = 4096;
	private static final int CHUNK = 1 << 20;

	record Copied(int files, long bytesWritten, int reflinked) {
	}

	private StoreCopier() {
	}

	static Copied copy(Path source, Path target) throws IOException {
		Files.createDirectories(target);
		List<Path> directories = new ArrayList<>();
		directories.add(target);
		int files = 0;
		int reflinked = 0;
		long written = 0;
		for (Path entry : entries(source)) {
			Path relative = source.relativize(entry);
			Path destination = target.resolve(relative.toString());
			if (Files.isDirectory(entry)) {
				Files.createDirectories(destination);
				directories.add(destination);
				continue;
			}
			if (!Files.isRegularFile(entry)) {
				continue;
			}
			files++;
			if (NativeLib.reflink(entry, destination) && Files.size(destination) == Files.size(entry)) {
				reflinked++;
			} else {
				written += sparseCopy(entry, destination);
			}
			try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE)) {
				channel.force(true);
			}
		}
		for (Path directory : directories) {
			MigrationMarker.syncDirectory(directory);
		}
		return new Copied(files, written, reflinked);
	}

	/** Everything to copy, parents before children, without the lock and the log. */
	private static List<Path> entries(Path source) throws IOException {
		Path lock = source.resolve("LOCK");
		Path wal = source.resolve("wal");
		try (Stream<Path> walk = Files.walk(source)) {
			return walk.filter(p -> !p.equals(source))
				.filter(p -> !p.equals(lock) && !p.startsWith(wal))
				.sorted()
				.toList();
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	private static long sparseCopy(Path source, Path target) throws IOException {
		long written = 0;
		ByteBuffer zero = ByteBuffer.allocateDirect(BLOCK);
		ByteBuffer buffer = ByteBuffer.allocateDirect(CHUNK);
		long size = Files.size(source);
		// Where the filesystem can say which ranges hold data, only those are read; elsewhere the whole file is one
		// range. Either way every range is still checked block by block, because allocated is not the same as non-zero.
		long[] extents = NativeLib.dataExtents(source, size);
		if (extents == null) {
			extents = new long[] { 0, size };
		}
		try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
			RandomAccessFile file = new RandomAccessFile(target.toFile(), "rw")) {
			file.setLength(0);
			FileChannel out = file.getChannel();
			for (int e = 0; e < extents.length; e += 2) {
				long position = extents[e];
				long end = Math.min(extents[e + 1], size);
				while (position < end) {
					buffer.clear();
					buffer.limit((int) Math.min(CHUNK, end - position));
					while (buffer.hasRemaining()) {
						if (in.read(buffer, position + buffer.position()) < 0) {
							break;
						}
					}
					int filled = buffer.position();
					if (filled == 0) {
						break;
					}
					written += writeNonZeroRuns(out, buffer, filled, position, zero);
					position += filled;
				}
			}
			// A truncate that extends leaves a hole, so trailing zeros cost nothing.
			file.setLength(size);
		}
		return written;
	}

	/** Writes the runs of non-zero blocks in {@code buffer[0, filled)} at {@code position}; returns the bytes written. */
	private static long writeNonZeroRuns(FileChannel out, ByteBuffer buffer, int filled, long position,
		ByteBuffer zero) throws IOException {
		long written = 0;
		int offset = 0;
		while (offset < filled) {
			if (isZero(buffer, offset, filled, zero)) {
				offset += Math.min(BLOCK, filled - offset);
				continue;
			}
			int end = offset;
			while (end < filled && !isZero(buffer, end, filled, zero)) {
				end += Math.min(BLOCK, filled - end);
			}
			ByteBuffer run = buffer.slice(offset, end - offset);
			long at = position + offset;
			while (run.hasRemaining()) {
				at += out.write(run, at);
			}
			written += end - offset;
			offset = end;
		}
		return written;
	}

	private static boolean isZero(ByteBuffer buffer, int offset, int limit, ByteBuffer zero) {
		int length = Math.min(BLOCK, limit - offset);
		return buffer.slice(offset, length).mismatch(zero.slice(0, length)) == -1;
	}

	/** The store's size on disk, which a copy without reflink needs free; -1 when it cannot be measured here. */
	static long allocatedBytes(Path source) throws IOException {
		long total = 0;
		for (Path entry : entries(source)) {
			if (!Files.isRegularFile(entry)) {
				continue;
			}
			long allocated = NativeLib.allocatedBytes(entry);
			if (allocated < 0) {
				return -1;
			}
			total += allocated;
		}
		return total;
	}

	static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(path)) {
			for (Path entry : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
				Files.deleteIfExists(entry);
			}
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}
}
