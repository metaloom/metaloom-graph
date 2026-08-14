package io.metaloom.graph.core.catalog;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The label and property-key dictionaries.
 * <p>
 * Node labels use the full 32 bit id space. Relationship types are capped at 14 bits because that is what the packed
 * adjacency entry can carry; a type beyond that limit is legal but forces every edge of that type to be materialised.
 *
 * @see <a href="../../../../../../../../spec/PROPERTY_STORAGE.md">spec/PROPERTY_STORAGE.md</a> section 3
 */
public final class Catalog implements AutoCloseable {

	/** Relationship types must fit the 14 bit field of a packed edge. */
	public static final int MAX_EDGE_TYPE_ID = (1 << 14) - 1;

	/** Property keys are stored as 24 bit fields inside a property block. */
	public static final int MAX_KEY_ID = (1 << 24) - 1;

	public static final int MAX_LABEL_ID = Integer.MAX_VALUE - 1;

	private final Dictionary labels;
	private final Dictionary keys;

	public Catalog(Path storePath) throws IOException {
		Path dir = storePath.resolve("dict");
		this.labels = new Dictionary(dir.resolve("labels.dict"), MAX_LABEL_ID);
		this.keys = new Dictionary(dir.resolve("keys.dict"), MAX_KEY_ID);
	}

	public int internLabel(String label) {
		if (label == null) {
			return 0;
		}
		return labels.intern(label);
	}

	/** Interns a relationship type, rejecting one that would not fit a packed adjacency entry. */
	public int internEdgeType(String type) {
		int id = internLabel(type);
		if (id > MAX_EDGE_TYPE_ID) {
			throw new IllegalStateException("Relationship type '" + type + "' was assigned id " + id
				+ ", beyond the " + MAX_EDGE_TYPE_ID + " that a packed adjacency entry can carry");
		}
		return id;
	}

	public String label(int id) {
		return labels.resolve(id);
	}

	public int lookupLabel(String label) {
		return labels.lookup(label);
	}

	public int internKey(String key) {
		if (key == null) {
			throw new IllegalArgumentException("Property key must not be null");
		}
		return keys.intern(key);
	}

	public int lookupKey(String key) {
		return keys.lookup(key);
	}

	public String key(int id) {
		return keys.resolve(id);
	}

	public int labelCount() {
		return labels.size();
	}

	public int keyCount() {
		return keys.size();
	}

	public void force() throws IOException {
		labels.force();
		keys.force();
	}

	@Override
	public void close() throws IOException {
		try {
			labels.close();
		} finally {
			keys.close();
		}
	}
}
