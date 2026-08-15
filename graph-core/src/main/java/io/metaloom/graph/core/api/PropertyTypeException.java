package io.metaloom.graph.core.api;

/** A property was read as a type it was not stored as. There is no implicit coercion. */
public class PropertyTypeException extends GraphStoreException {

	private static final long serialVersionUID = 1L;

	public PropertyTypeException(String key, Object stored, Class<?> requested) {
		super("Property '" + key + "' is stored as "
			+ (stored == null ? "null" : stored.getClass().getSimpleName())
			+ " and cannot be read as " + requested.getSimpleName());
	}
}
