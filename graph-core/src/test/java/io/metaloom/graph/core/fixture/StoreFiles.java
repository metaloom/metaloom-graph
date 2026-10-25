package io.metaloom.graph.core.fixture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import io.metaloom.graph.core.nativelib.NativeLib;

/** Byte-level views of a directory tree, for asserting that something did not change it. */
public final class StoreFiles {

	private StoreFiles() {
	}

	/**
	 * Every regular file under a directory, by relative path, to a SHA-256 of its bytes. {@code LOCK} is left out: a
	 * lock is taken and released by reading code too, and its content is not data.
	 */
	public static Map<String, String> digest(Path root) throws IOException {
		Map<String, String> digests = new TreeMap<>();
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile).sorted()::iterator) {
				String relative = root.relativize(file).toString();
				if (relative.equals("LOCK")) {
					continue;
				}
				digests.put(relative, sha256(file));
			}
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
		return digests;
	}

	/**
	 * A digest of a file's content: its length, then every 4 KiB block that is not all zero, with its offset. So it
	 * is the same for two files with the same bytes however their holes are allocated — a copy that turns an
	 * allocated zero block into a hole has not changed anything — and it reads only the ranges the filesystem says
	 * hold data, because a store with default geometry spans about 15 GiB of mostly holes.
	 */
	private static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			long size = Files.size(file);
			digest.update(java.nio.ByteBuffer.allocate(8).putLong(size).array());
			long[] extents = NativeLib.dataExtents(file, size);
			if (extents == null) {
				extents = new long[] { 0, size };
			}
			byte[] block = new byte[4096];
			byte[] zero = new byte[4096];
			try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file.toFile(), "r")) {
				for (int e = 0; e < extents.length; e += 2) {
					// Block aligned, so the same block is hashed whichever extent boundaries the filesystem reports.
					long position = extents[e] / block.length * block.length;
					long end = Math.min(extents[e + 1], size);
					while (position < end) {
						int length = (int) Math.min(block.length, size - position);
						in.seek(position);
						in.readFully(block, 0, length);
						if (!java.util.Arrays.equals(block, 0, length, zero, 0, length)) {
							digest.update(java.nio.ByteBuffer.allocate(8).putLong(position).array());
							digest.update(block, 0, length);
						}
						position += length;
					}
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The names in a directory, sorted. */
	public static java.util.List<String> list(Path directory) throws IOException {
		try (Stream<Path> entries = Files.list(directory)) {
			return entries.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	public static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(path)) {
			for (Path entry : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
				Files.deleteIfExists(entry);
			}
		}
	}
}
