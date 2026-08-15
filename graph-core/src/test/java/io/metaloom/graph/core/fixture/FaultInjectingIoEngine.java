package io.metaloom.graph.core.fixture;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import io.metaloom.graph.core.io.IoEngine;

/**
 * An {@link IoEngine} decorator that lies about what it wrote.
 *
 * <p>
 * Level 2 of [TESTING.md §6](../../../../../../../../spec/TESTING.md) lists six device faults. Four of them —
 * lost, torn, corrupt and reordered writes — are states that can be produced on disk after the fact, and
 * {@code CrashMatrixTest} produces them that way, at every record in the log. <b>Two cannot be</b>, because they are
 * not states at all but behaviours that happen while the engine is running:
 * </p>
 *
 * <ul>
 * <li>a write that fails, and</li>
 * <li>a write that <em>succeeds</em> and is then followed by an fsync that fails.</li>
 * </ul>
 *
 * <p>
 * The second is the dangerous one. A failing write is loud. An fsync that fails after the write returned is the case
 * where an engine is most likely to have already told its caller the commit is durable — and on Linux a failed
 * writeback can be reported once and then forgotten, so there is no second chance to notice. The only defence is that
 * the commit path treats the fsync as the barrier it claims to be.
 * </p>
 *
 * <p>
 * A test fixture rather than production code: nothing in the engine should be able to ask for a broken device.
 * </p>
 */
public final class FaultInjectingIoEngine implements IoEngine {

	/** What the device does wrong, and when. */
	public enum Fault {
		/** Everything works. The control case. */
		NONE,
		/** The write returns an error. */
		FAIL_WRITE,
		/** The write reports success and nothing reaches the device. */
		LOSE_WRITE,
		/** Only the first half of the buffer lands. */
		TEAR_WRITE,
		/** The write succeeds, and the fsync that was supposed to make it durable does not. */
		FAIL_FSYNC,
		/** The device is full. */
		ENOSPC
	}

	private final IoEngine delegate;
	private final Fault fault;
	private final int failFrom;
	private final AtomicInteger writes = new AtomicInteger();
	private final AtomicInteger forces = new AtomicInteger();

	/**
	 * @param delegate
	 *            the engine that does the real work
	 * @param fault
	 *            what to do wrong
	 * @param failFrom
	 *            the index of the first operation to apply the fault to; earlier ones are served honestly
	 */
	public FaultInjectingIoEngine(IoEngine delegate, Fault fault, int failFrom) {
		this.delegate = delegate;
		this.fault = fault;
		this.failFrom = failFrom;
	}

	public int writeCount() {
		return writes.get();
	}

	public int forceCount() {
		return forces.get();
	}

	@Override
	public String name() {
		return "fault(" + fault + "@" + failFrom + ")/" + delegate.name();
	}

	@Override
	public MemorySegment map(Path path, long size, Arena arena) throws IOException {
		return delegate.map(path, size, arena);
	}

	@Override
	public FileHandle open(Path path, boolean create) throws IOException {
		return new FaultyHandle(delegate.open(path, create));
	}

	@Override
	public boolean evict(Path path) {
		return delegate.evict(path);
	}

	@Override
	public int alignment() {
		return delegate.alignment();
	}

	@Override
	public IoCounts counts() {
		return delegate.counts();
	}

	@Override
	public void close() throws IOException {
		delegate.close();
	}

	private final class FaultyHandle implements FileHandle {

		private final FileHandle handle;

		FaultyHandle(FileHandle handle) {
			this.handle = handle;
		}

		@Override
		public long write(MemorySegment buffer, long length, long offset) throws IOException {
			int index = writes.getAndIncrement();
			if (index < failFrom) {
				return handle.write(buffer, length, offset);
			}
			return switch (fault) {
				case FAIL_WRITE -> throw new IOException("injected write failure at operation " + index);
				case ENOSPC -> throw new IOException("injected ENOSPC at operation " + index);
				// Reports the full length while writing nothing, which is what a lying cache does.
				case LOSE_WRITE -> length;
				case TEAR_WRITE -> {
					long half = length / 2 / alignment() * alignment();
					if (half > 0) {
						handle.write(buffer, half, offset);
					}
					// Still claims the whole thing landed.
					yield length;
				}
				default -> handle.write(buffer, length, offset);
			};
		}

		@Override
		public long read(MemorySegment buffer, long length, long offset) throws IOException {
			return handle.read(buffer, length, offset);
		}

		@Override
		public void force() throws IOException {
			int index = forces.getAndIncrement();
			if (index >= failFrom && fault == Fault.FAIL_FSYNC) {
				throw new IOException("injected fsync failure at force " + index);
			}
			handle.force();
		}

		@Override
		public MemorySegment buffer(Arena arena, long length) {
			return handle.buffer(arena, length);
		}

		@Override
		public void close() throws IOException {
			handle.close();
		}
	}
}
