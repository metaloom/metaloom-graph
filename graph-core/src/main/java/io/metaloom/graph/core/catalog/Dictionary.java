package io.metaloom.graph.core.catalog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.metaloom.graph.core.store.prop.ValueCodec;

/**
 * A persistent string to id mapping.
 * <p>
 * Interning is what stops the same key or label being written into a million records. Ids are assigned sequentially
 * from 1 (0 means "none") and are never reused, because reusing one would silently rewrite the meaning of every record
 * that references it.
 * <p>
 * The backing store is an append-only file of {@code [u32 length][utf8 bytes]} records, loaded fully into memory at
 * open. Phase 7 replaces it with a B+tree in the INDEX kind; the interface does not change.
 * <p>
 * <b>Reads take no lock.</b> Because an id never changes meaning, the reverse table can be a volatile array replaced
 * on append and the forward map a concurrent one, so {@code resolve} is an array index off a volatile reference.
 * This matters more than it looks: every {@code nodeLabel} call resolves an id, so a synchronized {@code resolve} is a
 * single global monitor on the engine's hottest read path, and it was measured holding read throughput to a third of
 * what it should be past two threads. Writes stay under the monitor, where they belong — they append to a file.
 *
 * @see <a href="../../../../../../../../spec/PROPERTY_STORAGE.md">spec/PROPERTY_STORAGE.md</a> section 3
 */
public final class Dictionary implements AutoCloseable {

	/** The longest entry, in UTF-8 bytes, that load accepts — and therefore that intern may write. */
	public static final int MAX_ENTRY_BYTES = 1 << 20;

	private final Path path;
	private final Map<String, Integer> forward = new ConcurrentHashMap<>();
	/** Index 0 is reserved and always null. Replaced wholesale on append, so readers never see a partial update. */
	private volatile String[] reverse = { null };
	private final FileOutputStream fileOut;
	private final OutputStream out;
	private final int maxId;
	/** Set by an intern that has not yet been fsynced; commit forces only when there is something to force. */
	private volatile boolean pendingWrites;

	public Dictionary(Path path, int maxId) throws IOException {
		this.path = path;
		this.maxId = maxId;
		Files.createDirectories(path.getParent());
		if (Files.exists(path)) {
			long validEnd = load();
			if (validEnd < Files.size(path)) {
				// A torn tail from an interrupted append. It was never forced, so no committed record can name it, and
				// appending behind it would make the next entry unreadable — cut it off first.
				try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
					channel.truncate(validEnd);
					channel.force(true);
				}
			}
		}
		this.fileOut = new FileOutputStream(path.toFile(), true);
		this.out = new BufferedOutputStream(fileOut);
	}

	/** Loads every complete entry and returns the byte offset just past the last one. */
	private long load() throws IOException {
		List<String> loaded = new ArrayList<>();
		loaded.add(null); // id 0 is reserved
		long validEnd = 0;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
			while (true) {
				byte[] header = in.readNBytes(4);
				if (header.length < 4) {
					// End of file, or a length cut short by a crash; either way nothing more is complete.
					break;
				}
				int length = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8)
					| (header[3] & 0xFF);
				if (length < 0 || length > MAX_ENTRY_BYTES) {
					throw new IOException("Corrupt dictionary entry length " + length + " in " + path);
				}
				byte[] bytes = in.readNBytes(length);
				if (bytes.length != length) {
					// A truncated tail record from an interrupted append; ignore it and stop.
					break;
				}
				String value = new String(bytes, StandardCharsets.UTF_8);
				forward.put(value, loaded.size());
				loaded.add(value);
				validEnd += 4 + length;
			}
		}
		reverse = loaded.toArray(new String[0]);
		return validEnd;
	}

	/** Returns the id for the string, assigning a new one if needed. */
	public synchronized int intern(String value) {
		Integer existing = forward.get(value);
		if (existing != null) {
			return existing;
		}
		String[] table = reverse;
		int id = table.length;
		if (id > maxId) {
			throw new IllegalStateException("Dictionary " + path.getFileName() + " exhausted its id space (" + maxId + ")");
		}
		// Strict: a lone surrogate would be written as '?' and come back as a different key after a reopen.
		byte[] bytes = ValueCodec.utf8(value);
		if (bytes.length > MAX_ENTRY_BYTES) {
			// load() refuses anything longer, so accepting it here would make the store unopenable.
			throw new IllegalArgumentException("Dictionary entry of " + bytes.length + " UTF-8 bytes exceeds the "
				+ MAX_ENTRY_BYTES + " byte limit");
		}
		try {
			out.write((bytes.length >>> 24) & 0xFF);
			out.write((bytes.length >>> 16) & 0xFF);
			out.write((bytes.length >>> 8) & 0xFF);
			out.write(bytes.length & 0xFF);
			out.write(bytes);
			out.flush();
		} catch (IOException e) {
			throw new UncheckedDictionaryException("Failed to append to " + path, e);
		}
		// The reverse table is published before the forward mapping, so a reader that finds an id through lookup can
		// always resolve it. The other order would expose an id that resolves to nothing.
		String[] grown = Arrays.copyOf(table, id + 1);
		grown[id] = value;
		reverse = grown;
		forward.put(value, id);
		pendingWrites = true;
		return id;
	}

	/** Returns the id, or 0 if the string has never been interned. Lock free. */
	public int lookup(String value) {
		return forward.getOrDefault(value, 0);
	}

	/** Returns the string for an id, or null for id 0 or an unknown id. Lock free. */
	public String resolve(int id) {
		String[] table = reverse;
		if (id <= 0 || id >= table.length) {
			return null;
		}
		return table[id];
	}

	public int size() {
		return reverse.length - 1;
	}

	/**
	 * Flushes and fsyncs, but only when something was interned since the last time. Dictionary ids are embedded in
	 * records, so they must be durable before those records are; forcing when nothing changed is pure latency.
	 */
	public void force() throws IOException {
		if (!pendingWrites) {
			return;
		}
		synchronized (this) {
			out.flush();
			pendingWrites = false;
		}
		fileOut.getFD().sync();
	}

	@Override
	public void close() throws IOException {
		out.close();
	}

	/** Wraps an IO failure from within {@link #intern(String)}, which cannot declare a checked exception. */
	public static final class UncheckedDictionaryException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public UncheckedDictionaryException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
