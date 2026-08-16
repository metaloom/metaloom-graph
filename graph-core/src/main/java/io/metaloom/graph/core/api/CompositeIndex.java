package io.metaloom.graph.core.api;

/**
 * A declared index over a label and a property key.
 *
 * <p>
 * The two implicit indexes — label to node, and property value to node — index everything automatically. This one is
 * <b>declared</b>, because there are {@code labels x keys} possible pairs and maintaining them all would multiply the
 * write cost of every node by the size of its label set. Declaring one makes
 * {@code MATCH (n:Person {email: ...})} a single probe instead of a property probe followed by a label verification,
 * and it is the only way to say "no two {@code :Person} share an email" rather than "no two nodes do".
 * </p>
 *
 * @param label
 *            the label a node must carry for its property to be indexed here
 * @param key
 *            the property key
 * @param unique
 *            whether the declaration is also a label-scoped unique constraint
 * @see <a href="../../../../../../../../spec/INDEX.md">spec/INDEX.md</a> section 3.3
 */
public record CompositeIndex(String label, String key, boolean unique) {

	public boolean covers(String label, String key) {
		return this.label.equals(label) && this.key.equals(key);
	}

	@Override
	public String toString() {
		return ":" + label + "." + key + (unique ? " (unique)" : "");
	}
}
