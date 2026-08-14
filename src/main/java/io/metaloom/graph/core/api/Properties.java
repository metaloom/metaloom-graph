package io.metaloom.graph.core.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Typed property values, keyed by name.
 * <p>
 * Values are stored as their real types, not as text. Reading a property as the wrong type raises
 * {@link PropertyTypeException} rather than coercing, and an absent key is distinguishable from a stored null.
 *
 * @see <a href="../../../../../../../../spec/PROPERTY_STORAGE.md">spec/PROPERTY_STORAGE.md</a> section 4
 */
public final class Properties {

	private final Map<String, Object> values;

	public Properties() {
		this.values = new LinkedHashMap<>();
	}

	private Properties(Map<String, Object> values) {
		this.values = values;
	}

	public static Properties of() {
		return new Properties();
	}

	public static Properties wrap(Map<String, Object> values) {
		return new Properties(values);
	}

	public Properties set(String key, Object value) {
		if (key == null) {
			throw new IllegalArgumentException("Property key must not be null");
		}
		values.put(key, value);
		return this;
	}

	/** Stores an explicit null, which is different from not having the key at all. */
	public Properties setNull(String key) {
		return set(key, null);
	}

	public Properties remove(String key) {
		values.remove(key);
		return this;
	}

	public boolean has(String key) {
		return values.containsKey(key);
	}

	public boolean isNull(String key) {
		return values.containsKey(key) && values.get(key) == null;
	}

	public Object get(String key) {
		return values.get(key);
	}

	public <T> T get(String key, Class<T> type) {
		Object value = values.get(key);
		if (value == null) {
			return null;
		}
		if (!type.isInstance(value)) {
			throw new PropertyTypeException(key, value, type);
		}
		return type.cast(value);
	}

	public <T> Optional<T> getOptional(String key, Class<T> type) {
		return Optional.ofNullable(get(key, type));
	}

	public String getString(String key) {
		return get(key, String.class);
	}

	public long getLong(String key) {
		Long value = get(key, Long.class);
		if (value == null) {
			throw new PropertyTypeException(key, null, Long.class);
		}
		return value;
	}

	public int getInt(String key) {
		return Math.toIntExact(getLong(key));
	}

	public double getDouble(String key) {
		Object value = values.get(key);
		if (value instanceof Double d) {
			return d;
		}
		if (value instanceof Float f) {
			return f;
		}
		throw new PropertyTypeException(key, value, Double.class);
	}

	public boolean getBoolean(String key) {
		Boolean value = get(key, Boolean.class);
		if (value == null) {
			throw new PropertyTypeException(key, null, Boolean.class);
		}
		return value;
	}

	public Instant getInstant(String key) {
		return get(key, Instant.class);
	}

	public UUID getUuid(String key) {
		return get(key, UUID.class);
	}

	public byte[] getBytes(String key) {
		return get(key, byte[].class);
	}

	public float[] getVector(String key) {
		return get(key, float[].class);
	}

	public Set<String> keys() {
		return Collections.unmodifiableSet(values.keySet());
	}

	public int size() {
		return values.size();
	}

	public boolean isEmpty() {
		return values.isEmpty();
	}

	public Map<String, Object> asMap() {
		return Collections.unmodifiableMap(values);
	}

	@Override
	public String toString() {
		return values.toString();
	}
}
