package io.metaloom.graph.core.util;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/**
 * CRC32C over memory segments.
 * <p>
 * This is the pure-Java fallback path described in spec/NATIVE_FFM.md section 4. The native (Rust) implementation
 * replaces it in Phase 6; this implementation remains the reference against which the native one is differentially
 * tested.
 */
public final class Crc {

	private Crc() {
	}

	public static int crc32c(MemorySegment segment, long offset, long length) {
		CRC32C crc = new CRC32C();
		ByteBuffer buf = segment.asSlice(offset, length).asByteBuffer();
		crc.update(buf);
		return (int) crc.getValue();
	}

	public static int crc32c(byte[] data) {
		CRC32C crc = new CRC32C();
		crc.update(data);
		return (int) crc.getValue();
	}

	public static int crc32c(byte[] data, int offset, int length) {
		CRC32C crc = new CRC32C();
		crc.update(data, offset, length);
		return (int) crc.getValue();
	}
}
