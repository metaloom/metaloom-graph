package io.metaloom.graph.core.api;

/**
 * One privilege on one database, or on all of them.
 *
 * <p>
 * The scope is a database because that is the only boundary this server actually has: it serves several stores under
 * one root ({@code spec/DATABASES.md}), and {@code spec/DATABASES.md} section 6 named the gap this closes — "there is
 * no way to open one database for writing and keep another read-only; that would need an identity to attach the
 * distinction to, and there is none". This is that attachment point.
 * </p>
 *
 * <p>
 * There is deliberately no finer scope. Per-label and per-property authorization would have to be enforced inside
 * {@code GraphAccess}, on every element a query touches, rather than once before it runs — a different and much larger
 * feature. Claiming it without building it would be worse than not having it, so the granularity stops here and
 * {@code spec/AUTH.md} says so in its threat model.
 * </p>
 *
 * @param privilege
 *            what may be done
 * @param database
 *            the database it may be done on, or {@link #ALL_DATABASES}
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a>
 */
public record Grant(Privilege privilege, String database) {

	/**
	 * Every database, present and future.
	 *
	 * <p>
	 * {@code *} rather than an empty string or a null, because a grant is written by hand and read by eye in
	 * {@code metaloom-graph-cli auth list}. It is also unambiguous by construction: {@code GraphDatabases.validate}
	 * allows only letters, digits, {@code -} and {@code _}, so no real database can ever be called {@code *} and the
	 * wildcard needs no escaping rule.
	 * </p>
	 */
	public static final String ALL_DATABASES = "*";

	/** The separator in the text form, {@code MERGE ON sales}. */
	private static final String ON = " ON ";

	public Grant {
		if (privilege == null) {
			throw new IllegalArgumentException("A grant needs a privilege");
		}
		if (database == null || database.isEmpty()) {
			throw new IllegalArgumentException("A grant needs a database name, or '" + ALL_DATABASES + "'");
		}
		if (!ALL_DATABASES.equals(database)) {
			if (privilege.isGlobalOnly()) {
				// Refused rather than widened to '*' or narrowed to nothing. A grant that silently meant something
				// other than what it said would be the worst outcome available here.
				throw new IllegalArgumentException(privilege + " is not a per-database privilege, so it can only be "
					+ "granted ON " + ALL_DATABASES + "; it was granted ON '" + database + "'");
			}
			// The same allow-list a database name faces everywhere else. One validator, so a grant can never name
			// something that is not a name, and a name in this file can never become a path component elsewhere.
			GraphDatabases.validate(database);
		}
	}

	/** A grant of one privilege on every database. */
	public static Grant on(Privilege privilege) {
		return new Grant(privilege, ALL_DATABASES);
	}

	/** Whether this grant authorises {@code privilege} against {@code target}. */
	public boolean permits(Privilege wanted, String target) {
		if (privilege != wanted) {
			return false;
		}
		return ALL_DATABASES.equals(database) || database.equals(target);
	}

	/** True when this applies to every database rather than a named one. */
	public boolean isWildcard() {
		return ALL_DATABASES.equals(database);
	}

	/**
	 * Reads the text form, {@code MERGE ON sales} or {@code MERGE ON *}.
	 *
	 * <p>
	 * The inverse of {@link #toString()}, so what {@code auth list} prints is what {@code auth grant} accepts. The
	 * keyword is matched case-insensitively along with the privilege, because both are typed by a person.
	 * </p>
	 *
	 * @throws IllegalArgumentException
	 *             with a message naming the expected shape rather than the position that failed
	 */
	public static Grant parse(String text) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("A grant is required, as '<PRIVILEGE> ON <database|*>'");
		}
		String trimmed = text.trim();
		int on = indexOfOn(trimmed);
		if (on < 0) {
			throw new IllegalArgumentException("'" + text + "' is not a grant. Write '<PRIVILEGE> ON <database|"
				+ ALL_DATABASES + ">', for example 'MERGE ON sales'");
		}
		String privilege = trimmed.substring(0, on).trim();
		String database = trimmed.substring(on + ON.length()).trim();
		if (privilege.isEmpty() || database.isEmpty()) {
			throw new IllegalArgumentException("'" + text + "' is not a grant. Write '<PRIVILEGE> ON <database|"
				+ ALL_DATABASES + ">', for example 'MERGE ON sales'");
		}
		return new Grant(Privilege.parse(privilege), database);
	}

	/** Case-insensitive index of the {@code " ON "} separator, without allocating a lower-cased copy. */
	private static int indexOfOn(String text) {
		for (int i = 0; i + ON.length() <= text.length(); i++) {
			if (text.regionMatches(true, i, ON, 0, ON.length())) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public String toString() {
		return privilege.name() + ON + database;
	}
}
