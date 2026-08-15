package io.metaloom.graph.core.io;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import io.metaloom.graph.core.nativelib.NativeBindings;
import io.metaloom.graph.core.nativelib.NativeCapabilities;
import io.metaloom.graph.core.nativelib.NativeLib;

/**
 * Batched asynchronous reads through io_uring, reached through the Rust crate.
 *
 * <p>
 * Capability N1 of spec/NATIVE_FFM.md, and the only one on that list the JVM cannot approach at all. A page fault and
 * a {@code FileChannel.read} are both synchronous: fetching eight pages costs eight round trips, one after another. A
 * ring accepts all eight at once and returns them as they complete, which on an NVMe device with real queue depth is
 * close to the cost of one.
 * </p>
 *
 * <p>
 * <b>Single-shot reads are not what this is for and are not faster.</b> {@link #read} on one range goes through the
 * ring for consistency and pays the submission overhead for nothing; {@link #readBatch} is the whole point. The
 * engine reports {@link #queueDepth()} so a caller can size its batches, and everything else about which pages to
 * fetch stays above this layer.
 * </p>
 *
 * <p>
 * A buffer handed to the ring must stay alive and unmoved until its completion is reaped — the kernel writes into it
 * asynchronously. That is why buffers are allocated from an {@link Arena} through {@link FileHandle#buffer} and never
 * from the heap, where a copying collector could move them underneath an in-flight read.
 * </p>
 */
public final class UringIoEngine implements IoEngine {

	/**
	 * The completion struct, described in Java and checked against the crate's own description by
	 * {@link NativeBindings}. Reaping at the wrong stride would attribute results to the wrong requests silently, so a
	 * disagreement disables the engine rather than being trusted.
	 */
	private static final MemoryLayout CQE = NativeBindings.CQE;

	private static final MethodHandle RING_NEW;
	private static final MethodHandle RING_FREE;
	private static final MethodHandle SUBMIT_READ;
	private static final MethodHandle SUBMIT;
	private static final MethodHandle REAP;
	private static final boolean AVAILABLE;

	static {
		MethodHandle ringNew = null;
		MethodHandle ringFree = null;
		MethodHandle submitRead = null;
		MethodHandle submit = null;
		MethodHandle reap = null;
		boolean available = false;
		if (NativeCapabilities.has(NativeCapabilities.CAP_URING) && NativeBindings.agrees()) {
			try {
				SymbolLookup library = NativeCapabilities.library().orElseThrow();
				Linker linker = Linker.nativeLinker();
				ringNew = linker.downcallHandle(library.findOrThrow("mlg_ring_new"),
					FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
				ringFree = linker.downcallHandle(library.findOrThrow("mlg_ring_free"),
					FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
				submitRead = linker.downcallHandle(library.findOrThrow("mlg_ring_submit_read"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
						ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
				submit = linker.downcallHandle(library.findOrThrow("mlg_ring_submit"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
				reap = linker.downcallHandle(library.findOrThrow("mlg_ring_reap"),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
						ValueLayout.JAVA_INT));
				available = true;
			} catch (RuntimeException e) {
				available = false;
			}
		}
		RING_NEW = ringNew;
		RING_FREE = ringFree;
		SUBMIT_READ = submitRead;
		SUBMIT = submit;
		REAP = reap;
		AVAILABLE = available;
	}

	public static final int DEFAULT_DEPTH = 64;

	private final DirectIoEngine direct = new DirectIoEngine();
	private final Arena arena = Arena.ofShared();
	private final MemorySegment ring;
	private final MemorySegment completions;
	private final int depth;

	/** Whether a ring can actually be created here. False on an old kernel, in a restricted container, or unbuilt. */
	public static boolean isSupported() {
		return AVAILABLE && DirectIoEngine.isSupported();
	}

	public UringIoEngine() {
		this(DEFAULT_DEPTH);
	}

	public UringIoEngine(int depth) {
		if (!isSupported()) {
			throw new IllegalStateException("io_uring is not available: " + NativeCapabilities.status()
				+ NativeBindings.mismatch().map(m -> " (ABI layout mismatch: " + m + ")").orElse(""));
		}
		this.depth = depth;
		try {
			MemorySegment created = (MemorySegment) RING_NEW.invokeExact(depth);
			if (created.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("mlg_ring_new returned null; io_uring is unavailable here");
			}
			this.ring = created;
			this.completions = arena.allocate(CQE, depth);
		} catch (Throwable e) {
			arena.close();
			throw new IllegalStateException("Could not create an io_uring ring", e);
		}
	}

	@Override
	public String name() {
		return "uring";
	}

	@Override
	public int queueDepth() {
		return depth;
	}

	@Override
	public MemorySegment map(Path path, long size, Arena target) throws IOException {
		return direct.map(path, size, target);
	}

	@Override
	public FileHandle open(Path path, boolean create) throws IOException {
		return direct.open(path, create);
	}

	@Override
	public boolean evict(Path path) {
		return NativeLib.evictFromPageCache(path);
	}

	@Override
	public int alignment() {
		return direct.alignment();
	}

	@Override
	public IoCounts counts() {
		return direct.counts();
	}

	/**
	 * Issues every read at once and waits for all of them.
	 * <p>
	 * Requests beyond the ring's depth are submitted in waves rather than refused, so a caller may pass any number.
	 */
	@Override
	public long[] readBatch(FileHandle handle, MemorySegment[] buffers, long[] lengths, long[] offsets)
		throws IOException {
		if (buffers.length != lengths.length || buffers.length != offsets.length) {
			throw new IllegalArgumentException("buffers, lengths and offsets must be the same length");
		}
		int fd = ((DirectIoEngine.DirectHandle) handle).fd();
		long[] read = new long[buffers.length];

		int index = 0;
		while (index < buffers.length) {
			int wave = Math.min(depth, buffers.length - index);
			int queued = 0;
			for (int i = 0; i < wave; i++) {
				int slot = index + i;
				if (!submitRead(fd, buffers[slot], lengths[slot], offsets[slot], slot)) {
					// The submission queue filled early. Submitting what is queued and continuing is correct: this is
					// backpressure, not an error.
					break;
				}
				queued++;
			}
			if (queued == 0) {
				throw new IOException("The ring accepted no entries at depth " + depth);
			}
			submitAndWait(queued);
			reapInto(read, queued);
			index += queued;
		}
		return read;
	}

	private boolean submitRead(int fd, MemorySegment buffer, long length, long offset, long userData)
		throws IOException {
		try {
			int result = (int) SUBMIT_READ.invokeExact(ring, fd, buffer, (int) length, offset, userData);
			if (result < 0) {
				throw new IOException("mlg_ring_submit_read returned " + result);
			}
			return result == 1;
		} catch (IOException e) {
			throw e;
		} catch (Throwable e) {
			throw new IOException("io_uring submission failed", e);
		}
	}

	private void submitAndWait(int minComplete) throws IOException {
		try {
			int result = (int) SUBMIT.invokeExact(ring, minComplete);
			if (result < 0) {
				throw new IOException("mlg_ring_submit returned " + result);
			}
		} catch (IOException e) {
			throw e;
		} catch (Throwable e) {
			throw new IOException("io_uring submit failed", e);
		}
	}

	private void reapInto(long[] read, int expected) throws IOException {
		int collected = 0;
		while (collected < expected) {
			int count;
			try {
				count = (int) REAP.invokeExact(ring, completions, depth);
			} catch (Throwable e) {
				throw new IOException("io_uring reap failed", e);
			}
			if (count < 0) {
				throw new IOException("mlg_ring_reap returned " + count);
			}
			if (count == 0) {
				// Everything submitted has been waited for, so a zero reap means the completions are not yet visible.
				// Ask the kernel again rather than spinning on the queue.
				submitAndWait(expected - collected);
				continue;
			}
			for (int i = 0; i < count; i++) {
				MemorySegment cqe = completions.asSlice((long) i * CQE.byteSize(), CQE.byteSize());
				long userData = cqe.get(ValueLayout.JAVA_LONG, 0);
				int result = cqe.get(ValueLayout.JAVA_INT, 8);
				if (result < 0) {
					throw new IOException("io_uring read of request " + userData + " failed with " + result);
				}
				read[(int) userData] = result;
			}
			collected += count;
		}
	}

	@Override
	public void close() throws IOException {
		try {
			RING_FREE.invokeExact(ring);
		} catch (Throwable e) {
			throw new IOException("Could not free the io_uring ring", e);
		} finally {
			arena.close();
			direct.close();
		}
	}
}
