package io.metaloom.graph.core.io;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.LongAdder;

import io.metaloom.graph.core.nativelib.NativeLib;

/**
 * The default engine: memory-mapped data, buffered positional writes through {@link FileChannel}.
 *
 * <p>
 * This is what the engine did before the interface existed, extracted unchanged so that the direct engine has
 * something to be equivalent to.
 * </p>
 */
public final class MmapIoEngine implements IoEngine {

	private final LongAdder reads = new LongAdder();
	private final LongAdder writes = new LongAdder();
	private final LongAdder forces = new LongAdder();

	@Override
	public String name() {
		return "mmap";
	}

	@Override
	public MemorySegment map(Path path, long size, Arena arena) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
			StandardOpenOption.WRITE)) {
			if (channel.size() < size) {
				channel.position(size - 1);
				channel.write(ByteBuffer.wrap(new byte[1]));
			}
			return channel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
		}
	}

	@Override
	public FileHandle open(Path path, boolean create) throws IOException {
		return new ChannelHandle(path, create);
	}

	@Override
	public boolean evict(Path path) {
		// Available through libc whichever engine is in use; it is not an O_DIRECT feature. Reported honestly so a
		// cold-read measurement can skip rather than silently measure a warm cache.
		return NativeLib.evictFromPageCache(path);
	}

	@Override
	public int alignment() {
		return 1;
	}

	@Override
	public IoCounts counts() {
		return new IoCounts(reads.sum(), writes.sum(), forces.sum(), PageFaults.major());
	}

	@Override
	public void close() {
	}

	private final class ChannelHandle implements FileHandle {

		private final FileChannel channel;

		ChannelHandle(Path path, boolean create) throws IOException {
			this.channel = create
				? FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
				: FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
		}

		@Override
		public long write(MemorySegment buffer, long length, long offset) throws IOException {
			ByteBuffer bytes = buffer.asSlice(0, length).asByteBuffer();
			long written = 0;
			while (bytes.hasRemaining()) {
				written += channel.write(bytes, offset + written);
			}
			writes.increment();
			return written;
		}

		@Override
		public long read(MemorySegment buffer, long length, long offset) throws IOException {
			ByteBuffer bytes = buffer.asSlice(0, length).asByteBuffer();
			long total = 0;
			while (bytes.hasRemaining()) {
				int read = channel.read(bytes, offset + total);
				if (read < 0) {
					break;
				}
				total += read;
			}
			reads.increment();
			return total;
		}

		@Override
		public void force() throws IOException {
			channel.force(true);
			forces.increment();
		}

		@Override
		public MemorySegment buffer(Arena arena, long length) {
			return arena.allocate(length, ValueLayout.JAVA_LONG.byteSize());
		}

		@Override
		public void close() throws IOException {
			channel.close();
		}
	}
}
