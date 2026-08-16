package io.metaloom.graph.core.api;

import io.metaloom.graph.core.id.ElementId;

/**
 * A write refused because it would break a unique constraint.
 *
 * <p>
 * Carries the key, the value and the element that already holds it, because "duplicate key" without saying which
 * element already has it is the least useful message a storage engine can produce.
 * </p>
 */
public class ConstraintViolationException extends GraphStoreException {

	private static final long serialVersionUID = 1L;

	private final String label;
	private final String key;
	private final transient Object value;
	private final long existingId;

	public ConstraintViolationException(String key, Object value, long existingId) {
		super("Unique constraint on '" + key + "' is violated: " + describe(value) + " is already held by "
			+ ElementId.toString(existingId));
		this.label = null;
		this.key = key;
		this.value = value;
		this.existingId = existingId;
	}

	/**
	 * A <b>label-scoped</b> violation: the constraint is over {@code (label, key)}, so the message has to name the
	 * label as well. "no two nodes share an email" and "no two :Person share an email" are different refusals, and a
	 * message that cannot tell them apart sends the reader looking at the wrong nodes.
	 */
	public ConstraintViolationException(String label, String key, Object value, long existingId) {
		super("Unique constraint on ':" + label + "." + key + "' is violated: " + describe(value)
			+ " is already held by " + ElementId.toString(existingId));
		this.label = label;
		this.key = key;
		this.value = value;
		this.existingId = existingId;
	}

	/** The label the constraint is scoped to, or null for a store-wide constraint on the key alone. */
	public String label() {
		return label;
	}

	private static String describe(Object value) {
		if (value instanceof byte[] bytes) {
			return bytes.length + " bytes";
		}
		return String.valueOf(value);
	}

	public String key() {
		return key;
	}

	public Object value() {
		return value;
	}

	/** The element that already holds the value. */
	public long existingId() {
		return existingId;
	}
}
