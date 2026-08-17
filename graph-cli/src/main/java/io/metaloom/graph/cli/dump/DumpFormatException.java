package io.metaloom.graph.cli.dump;

/**
 * A dump this build cannot read: a wrong magic, an unknown version, a corrupt length, a bad checksum, a truncated file.
 *
 * <p>
 * The one exception type the decoder is allowed to raise, which is what makes the hostile-input rule assertable:
 * reading a dump either produces a graph or throws this, and never an {@code OutOfMemoryError}, a
 * {@code NegativeArraySizeException} or a {@code StackOverflowError}.
 * </p>
 */
public class DumpFormatException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public DumpFormatException(String message) {
		super(message);
	}
}
