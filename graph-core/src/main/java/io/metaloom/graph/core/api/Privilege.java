package io.metaloom.graph.core.api;

/**
 * One thing a principal may be allowed to do.
 *
 * <p>
 * The set spans three surfaces rather than one, and that is the first thing to know about it: some of these name a
 * Cypher clause, some name an HTTP route, and some name an MCP tool. Index creation in particular is <b>not</b> a
 * clause in this engine — there is no {@code CREATE INDEX} in the grammar ({@code spec/CYPHER.md}) — so a privilege
 * model derived only from the AST would leave the index routes ungoverned.
 * </p>
 *
 * <h2>This is not the whole gate</h2>
 *
 * <p>
 * A request needs the privilege <b>and</b> the server-wide flag that permits the action at all — {@code --allow-write},
 * {@code --allow-drop}, {@code --allow-backup}, {@code --allow-load-csv}. The flags can only subtract. That is what
 * keeps turning authentication on from ever widening what a deployment can do, and it is why a read-only container
 * stays read-only whoever logs into it. {@code spec/AUTH.md} section 5 has the reasoning.
 * </p>
 *
 * <h2>Names, not numbers, on disk and on the wire</h2>
 *
 * <p>
 * A grant is stored and transmitted as this constant's name. There is no second registry of codes to keep in step, a
 * refusal explains itself without a lookup table, and the identity file holds tens of entries so the size difference is
 * irrelevant. An unknown name is <b>refused</b> wherever it is read, never skipped: a build that half-understood a file
 * and then rewrote it would silently drop a grant, and losing authority quietly is as bad as gaining it.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/AUTH.md">spec/AUTH.md</a>
 */
public enum Privilege {

	/**
	 * Read the graph: {@code MATCH}, {@code UNWIND}, {@code WITH}, {@code RETURN} and {@code CALL}.
	 *
	 * <p>
	 * One privilege rather than one per read clause, because the others compute over rows a {@code MATCH} already
	 * bound and reach no store of their own. A grant of {@code UNWIND} without {@code MATCH} would authorise nothing.
	 * </p>
	 */
	MATCH(false, false),

	/** {@code CREATE}. */
	CREATE(true, false),

	/**
	 * {@code MERGE}.
	 *
	 * <p>
	 * Its own name, and not derivable from the two it sits between: "may upsert, may not delete" is a real loader
	 * role. Because {@code MERGE} reads before it writes, a query using it requires {@link #MATCH} as well — see
	 * {@code Ast.SingleQuery.requiredPrivileges()}.
	 * </p>
	 */
	MERGE(true, false),

	/** {@code SET}, including {@code SET n:Label}. */
	SET(true, false),

	/** {@code REMOVE}, including {@code REMOVE n:Label}. */
	REMOVE(true, false),

	/** {@code DELETE} and {@code DETACH DELETE}. */
	DELETE(true, false),

	/**
	 * {@code LOAD CSV}.
	 *
	 * <p>
	 * Not a write, and that is the point of it having a name at all: {@code LOAD CSV … RETURN row} creates nothing, so
	 * a model that folded file access into {@link #CREATE} would serve it to a principal granted only reads. The same
	 * reasoning gives {@code --allow-load-csv} a flag of its own.
	 * </p>
	 */
	LOAD_CSV(false, false),

	/** {@code POST /api/indexes}, and the MCP index tool. Covers a unique constraint, which is a declaration with a flag. */
	CREATE_INDEX(true, false),

	/**
	 * {@code DELETE /api/indexes/{label}/{key}}, and the MCP tool.
	 *
	 * <p>
	 * Split from {@link #CREATE_INDEX} because dropping is not the inverse of creating in consequence: dropping an
	 * index costs queries time, and dropping a <b>unique constraint</b> — which is the same declaration with a flag —
	 * lets the next write put two live elements on one value. One is a cost, the other is a correctness event.
	 * </p>
	 */
	DROP_INDEX(true, false),

	/**
	 * {@code /api/schema}, {@code /api/stats}, {@code /api/indexes}, and the MCP schema, stats, store and index-listing
	 * tools.
	 *
	 * <p>
	 * One name for all of them, because "how many nodes" and "which labels" are not a role boundary. It is separate
	 * from {@link #MATCH} because either without the other is a real role: a monitoring principal that may see sizes
	 * and not contents, and a query principal that may read data without being handed a map of the whole store —
	 * {@code /api/schema} is a full scan of every label and type.
	 * </p>
	 */
	INTROSPECT(false, false),

	/** {@code PUT /api/databases/{name}}. Scopable by name, which is meaningful for a name agreed in advance. */
	CREATE_DATABASE(true, false),

	/** {@code DELETE /api/databases/{name}}, which unlinks a whole directory in one request. */
	DROP_DATABASE(true, false),

	/**
	 * {@code GET /api/databases/{name}/backup}.
	 *
	 * <p>
	 * A read, and the one read that hands over every byte of a database in a single request — the same reasoning that
	 * gives {@code --allow-backup} a flag separate from {@code --allow-write}.
	 * </p>
	 */
	BACKUP(false, false),

	/**
	 * Create, drop, enable and disable a user, and set another user's password.
	 *
	 * <p>
	 * <b>Global only.</b> A user is not a per-database object, so a grant of this on one database would be a promise
	 * nothing could keep; {@link Grant} refuses one rather than accepting it and quietly meaning something else.
	 * </p>
	 */
	MANAGE_USERS(true, true),

	/**
	 * Create and drop a role, and grant or revoke anything.
	 *
	 * <p>
	 * <b>The most important split in this enum.</b> "May reset a forgotten password" is a helpdesk task; "may grant
	 * myself {@code MERGE ON *}" is privilege escalation. Folding the two together would mean every principal trusted
	 * to do the first could silently do the second, so {@link #MANAGE_USERS} deliberately does not imply this.
	 * </p>
	 *
	 * <p>
	 * Global only, for the reason above.
	 * </p>
	 */
	MANAGE_ROLES(true, true);

	private final boolean write;

	private final boolean globalOnly;

	Privilege(boolean write, boolean globalOnly) {
		this.write = write;
		this.globalOnly = globalOnly;
	}

	/**
	 * Whether exercising this can change a store or the server's configuration.
	 *
	 * <p>
	 * Reported rather than inferred so an administrative UI can group and warn. It is <b>not</b> the write gate:
	 * {@code Ast.Query.isWrite()} decides that for a query, and the server-wide flag decides whether writes happen at
	 * all.
	 * </p>
	 */
	public boolean isWrite() {
		return write;
	}

	/** Whether this may only be granted on every database at once; see {@link Grant#ALL_DATABASES}. */
	public boolean isGlobalOnly() {
		return globalOnly;
	}

	/**
	 * Resolves a name, or refuses it.
	 *
	 * <p>
	 * Not {@link #valueOf(String)}: this is reached from an identity file and from a request body, and
	 * {@code IllegalArgumentException: No enum constant …} is not a message to put in front of somebody who mistyped a
	 * grant. The refusal names what is allowed, the way {@code GraphDatabases.validate} does.
	 * </p>
	 *
	 * @throws IllegalArgumentException
	 *             if no privilege has this name. Case-insensitive, because a grant is written by hand
	 */
	public static Privilege parse(String name) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("A privilege name is required. One of: " + names());
		}
		for (Privilege privilege : values()) {
			if (privilege.name().equalsIgnoreCase(name)) {
				return privilege;
			}
		}
		throw new IllegalArgumentException("'" + name + "' is not a privilege this build knows. One of: " + names());
	}

	/** Every name, comma separated, for a message that has to tell somebody what they may write instead. */
	public static String names() {
		StringBuilder out = new StringBuilder();
		for (Privilege privilege : values()) {
			if (out.length() > 0) {
				out.append(", ");
			}
			out.append(privilege.name());
		}
		return out.toString();
	}
}
