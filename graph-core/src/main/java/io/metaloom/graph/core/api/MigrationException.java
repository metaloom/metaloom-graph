package io.metaloom.graph.core.api;

/**
 * A store format migration was refused, failed, or was found interrupted.
 *
 * <p>
 * Whatever the cause, the message says what state the store is in. Refused and failed migrations leave the original
 * exactly as it was; an interrupted one is finished by running the migration again, and until then the store refuses
 * to open rather than be opened half way through a swap.
 * </p>
 *
 * @see StoreMigration
 */
public class MigrationException extends GraphStoreException {

	private static final long serialVersionUID = 1L;

	public MigrationException(String message) {
		super(message);
	}

	public MigrationException(String message, Throwable cause) {
		super(message, cause);
	}
}
