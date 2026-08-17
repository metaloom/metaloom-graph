package io.metaloom.graph.cli.dump;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

/**
 * Little-endian primitives onto a stream, with a running checksum.
 *
 * <p>
 * Explicitly little-endian, like every other binary structure in this project: a dump written on one machine has to be
 * readable on another, and relying on the platform default is how that stops being true.
 * </p>
 *
 * <p>
 * The CRC32C covers <b>everything after the header</b> and is written in the trailer. It is checked before a restore
 * applies anything, which is the point: a truncated or corrupted dump should fail as a dump rather than as a store that
 * turns out later to be missing half a graph.
 * </p>
 */
public final class DumpWriter implements AutoCloseable {

	private final OutputStream out;
	private final CRC32C crc = new CRC32C();
	private final byte[] scratch = new byte[8];
	private boolean checksumming;
	private long written;

	public DumpWriter(OutputStream out) {
		this.out = out;
	}

	/** Starts covering bytes with the checksum. Called once the header is out. */
	public void beginChecksum() {
		checksumming = true;
	}

	public long checksum() {
		return crc.getValue();
	}

	public long bytesWritten() {
		return written;
	}

	public void u8(int value) {
		scratch[0] = (byte) value;
		write(scratch, 1);
	}

	public void u16(int value) {
		scratch[0] = (byte) value;
		scratch[1] = (byte) (value >>> 8);
		write(scratch, 2);
	}

	public void u32(int value) {
		for (int i = 0; i < 4; i++) {
			scratch[i] = (byte) (value >>> (8 * i));
		}
		write(scratch, 4);
	}

	public void u64(long value) {
		for (int i = 0; i < 8; i++) {
			scratch[i] = (byte) (value >>> (8 * i));
		}
		write(scratch, 8);
	}

	/** LEB128, unsigned. Every length and count in the format is one of these. */
	public void varInt(long value) {
		long remaining = value;
		while (true) {
			int b = (int) (remaining & 0x7F);
			remaining >>>= 7;
			if (remaining == 0) {
				u8(b);
				return;
			}
			u8(b | 0x80);
		}
	}

	public void string(String value) {
		byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
		varInt(bytes.length);
		bytes(bytes);
	}

	public void bytes(byte[] value) {
		write(value, value.length);
	}

	private void write(byte[] buffer, int length) {
		try {
			out.write(buffer, 0, length);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (checksumming) {
			crc.update(buffer, 0, length);
		}
		written += length;
	}

	public void flush() {
		try {
			out.flush();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void close() {
		try {
			out.close();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
