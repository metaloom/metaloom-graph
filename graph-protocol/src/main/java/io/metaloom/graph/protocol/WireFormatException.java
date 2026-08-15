package io.metaloom.graph.protocol;

/**
 * A frame or a value that cannot be decoded.
 *
 * <p>
 * Every rejection path in the decoder raises this and nothing else — not {@code OutOfMemoryError} from a length field
 * nobody checked, not {@code StackOverflowError} from unbounded nesting, not {@code ArrayIndexOutOfBoundsException}
 * from a read past the end. That is the whole contract the hostile-input oracle asserts, and it is what lets a server
 * answer a malformed frame with a protocol error and carry on serving everyone else.
 * </p>
 */
public class WireFormatException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public WireFormatException(String message) {
		super(message);
	}
}
