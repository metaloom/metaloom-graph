package io.metaloom.graph.core.fixture;

import java.util.zip.CRC32C;

/**
 * A property value that carries proof of its own integrity.
 *
 * <p>
 * The read path is the riskiest code in the engine and the hardest to write an oracle for: it runs
 * {@code applyLock.tryOptimisticRead()}, executes the whole operation with <b>no lock at all</b>, and only afterwards
 * asks whether a commit landed underneath it — discarding the result and retrying under a real lock if one did. That
 * is correct only if every torn intermediate state is either caught by the validate or impossible to mistake for an
 * answer. A value that says who it belongs to and checksums itself turns the second half of that into something a test
 * can actually check: a read that returns bytes from two different commits fails its own checksum, and a read that
 * returns the right bytes for the wrong node fails its owner field.
 * </p>
 *
 * <p>
 * Two lengths, because they take different paths through the engine. The short form stays inline in the property
 * block; the long form is past the 255-byte inline limit and spills into the blob store, so it exercises the extent
 * chain as well.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/PROPERTY_STORAGE.md">spec/PROPERTY_STORAGE.md</a>
 */
public final class Witness {

	/** Comfortably past the inline limit, so the value lands in the blob store. */
	private static final int LONG_FORM_PADDING = 40;

	private Witness() {
	}

	public static String shortForm(long owner, long generation) {
		return encode(owner, generation, 1);
	}

	public static String longForm(long owner, long generation) {
		return encode(owner, generation, LONG_FORM_PADDING);
	}

	private static String encode(long owner, long generation, int repeats) {
		String body = owner + ":" + generation;
		StringBuilder sb = new StringBuilder(body);
		// Repeated rather than padded with a constant: a torn read that splices two values together lands in the
		// middle of a repeat, where the owner and generation are still readable and the mismatch is diagnosable.
		for (int i = 1; i < repeats; i++) {
			sb.append('|').append(body);
		}
		String payload = sb.toString();
		return payload + "#" + checksum(payload);
	}

	private static long checksum(String payload) {
		CRC32C crc = new CRC32C();
		crc.update(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return crc.getValue();
	}

	/**
	 * Checks a value read back out of the store.
	 *
	 * @return null when the value is intact, otherwise a description of what is wrong with it
	 */
	public static String problemWith(Object value, long expectedOwner) {
		if (value == null) {
			return "value for owner " + expectedOwner + " read back as null";
		}
		if (!(value instanceof String text)) {
			return "value for owner " + expectedOwner + " read back as " + value.getClass().getName()
				+ " rather than a String";
		}
		int hash = text.lastIndexOf('#');
		if (hash < 0) {
			return "value for owner " + expectedOwner + " has no checksum marker: " + abbreviate(text);
		}
		String payload = text.substring(0, hash);
		String stated = text.substring(hash + 1);
		long actual = checksum(payload);
		if (!String.valueOf(actual).equals(stated)) {
			// The strongest signal in this whole suite: bytes that no single commit ever wrote.
			return "value for owner " + expectedOwner + " failed its own checksum (stated " + stated + ", computed "
				+ actual + "), so the read spliced together bytes from more than one commit: " + abbreviate(text);
		}
		for (String part : payload.split("\\|")) {
			int colon = part.indexOf(':');
			if (colon < 0) {
				return "value for owner " + expectedOwner + " has a malformed segment '" + part + "'";
			}
			long owner = Long.parseLong(part.substring(0, colon));
			if (owner != expectedOwner) {
				return "value read for owner " + expectedOwner + " actually belongs to " + owner
					+ ", so a read resolved to the wrong record";
			}
		}
		return null;
	}

	/** The generation stamped into an intact value. */
	public static long generationOf(String text) {
		String payload = text.substring(0, text.lastIndexOf('#'));
		String first = payload.split("\\|")[0];
		return Long.parseLong(first.substring(first.indexOf(':') + 1));
	}

	private static String abbreviate(String text) {
		return text.length() <= 120 ? text : text.substring(0, 60) + "..." + text.substring(text.length() - 40);
	}
}
