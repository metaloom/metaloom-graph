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

	/**
	 * Interns a relationship type. Types share the label id space, so the id can exceed {@link #MAX_EDGE_TYPE_ID};
	 * such a type is still legal, it just cannot ride in a packed adjacency entry — see {@link #packable}.
	 */
	public int internEdgeType(String type) {
		return internLabel(type);
	}

	/** Whether a relationship type id fits the 14 bit type field of a lightweight adjacency entry. */
	public static boolean packable(int typeId) {
		return typeId > 0 && typeId <= MAX_EDGE_TYPE_ID;
	}

	public String label(int id) {
		return labels.resolve(id);
	}

	/**
	 * Told about every lookup that finds nothing.
	 *
	 * <p>
	 * A miss is a read that touches no page: "no node carries this label" is answered by the dictionary alone, so a
	 * page-granular read set never sees it, and a {@code SERIALIZABLE} transaction that decided something from it could
	 * not be refused when another transaction later introduced the name. The store installs an observer that remembers
	 * the miss for such a transaction, and the commit refuses if the name has since come to exist.
	 * </p>
	 */
	public interface MissObserver {

		void missed(boolean label, String name);
	}

	private volatile MissObserver missObserver;

	public void observeMisses(MissObserver observer) {
		this.missObserver = observer;
	}

	public int lookupLabel(String label) {
		int id = labels.lookup(label);
		if (id == 0) {
			missed(true, label);
		}
		return id;
	}

	private void missed(boolean label, String name) {
		MissObserver observer = missObserver;
		if (observer != null && name != null) {
			observer.missed(label, name);
		}
	}

	public int internKey(String key) {
		if (key == null) {
			throw new IllegalArgumentException("Property key must not be null");
		}
		return keys.intern(key);
	}

	public int lookupKey(String key) {
		int id = keys.lookup(key);
		if (id == 0) {
			missed(false, key);
		}
		return id;
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
