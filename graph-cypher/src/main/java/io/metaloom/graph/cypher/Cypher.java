package io.metaloom.graph.cypher;

import java.util.Map;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.ReadTransaction;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.exec.GraphAccess;
import io.metaloom.graph.cypher.exec.QueryExecutor;
import io.metaloom.graph.cypher.parse.CypherParser;

/**
 * Runs a read-only openCypher query against a store.
 *
 * <pre>{@code
 * CypherResult result = Cypher.query(store,
 *     "MATCH (p:Person)-[:KNOWS]->(f) WHERE p.age > $min RETURN f.name, count(*) AS mutual ORDER BY mutual DESC",
 *     Map.of("min", 30));
 * for (CypherRow row : result) {
 *     System.out.println(row.get("f.name"));
 * }
 * }</pre>
 *
 * <h2>Isolation</h2>
 *
 * <p>
 * Every query runs inside a {@code SNAPSHOT} read view, so a query that scans and then expands cannot observe a mix of
 * commits — without one, a writer committing mid-query could make the same query see a node in its scan and a
 * different graph by the time it expanded from it.
 * </p>
 *
 * <p>
 * <b>If the caller already has a view open, the query joins it</b> rather than opening another. Views are
 * thread-confined and refuse to nest, so opening one unconditionally would make {@code Cypher.query} unusable inside a
 * caller's own snapshot — which is exactly where someone wanting several queries over one consistent state would put
 * it.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
public final class Cypher {

	private Cypher() {
	}

	public static CypherResult query(GraphStore store, String query) {
		return query(store, query, Map.of());
	}

	public static CypherResult query(GraphStore store, String query, Map<String, Object> parameters) {
		Ast.Query parsed = CypherParser.parse(query);
		return execute(store, parsed, parameters);
	}

	/** Parses without running, for a caller that wants to validate a query or inspect its shape. */
	public static Ast.Query parse(String query) {
		return CypherParser.parse(query);
	}

	private static CypherResult execute(GraphStore store, Ast.Query parsed, Map<String, Object> parameters) {
		// Joins the caller's view when there is one. Opening a second would be refused, and closing theirs at the end
		// of this query would be worse than being refused.
		ReadTransaction view = store.hasReadTransaction() ? null : store.beginRead();
		try {
			QueryExecutor.Result result = new QueryExecutor(new GraphAccess(store), parameters).run(parsed);
			return new CypherResult(result.columns(), result.rows());
		} finally {
			if (view != null) {
				view.close();
			}
		}
	}
}
