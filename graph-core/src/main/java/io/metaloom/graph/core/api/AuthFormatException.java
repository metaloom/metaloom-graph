package io.metaloom.graph.core.api;

/**
 * The identity file could not be read: a bad magic, a version this build does not know, a length that cannot be
 * satisfied, a failed checksum.
 *
 * <p>
 * This is the <b>only</b> thing {@code AuthCodec.decode} throws, and that is the whole of the contract
 * {@code AuthHostileInputTest} asserts: decoding either produces a valid image or raises this. Never an
 * {@code OutOfMemoryError} from a believed length, never a {@code NegativeArraySizeException}, never a
 * {@code StackOverflowError}, and always in bounded time.
 * </p>
 *
 * <p>
 * A server that meets one <b>refuses to start</b> rather than bootstrapping over the file. Overwriting a damaged
 * credential file would discard grants nobody could then reconstruct, and could silently widen access.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a> section 3.4
 */
public class AuthFormatException extends AuthException {

	private static final long serialVersionUID = 1L;

	public AuthFormatException(String message) {
		super(message);
	}

	public AuthFormatException(String message, Throwable cause) {
		super(message, cause);
	}
}
