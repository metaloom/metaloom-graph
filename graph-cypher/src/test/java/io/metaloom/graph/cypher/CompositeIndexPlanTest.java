package io.metaloom.graph.cypher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.cypher.ast.Ast;
import io.metaloom.graph.cypher.exec.GraphAccess;
import io.metaloom.graph.cypher.exec.QueryExecutor;

/**
 * Which access path the planner takes, rather than which answer it produces.
 *
 * <p>
 * This distinction is the whole point of the class. Every other test of a composite index would pass with the index
 * ignored entirely — the store answers {@code nodesByLabelAndProperty} correctly with or without a declaration, and
 * only the number of candidates read differs. So the assertions here are on the <em>calls</em> the executor makes
 * against the store, recorded through a proxy that sits between {@link GraphAccess} and the real one.
 * </p>
 *
 * @see <a href="../../../../../../../spec/INDEX.md">spec/INDEX.md</a> section 7
 */
@Tag("unit")
public class CompositeIndexPlanTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphStore store;

	@BeforeEach
	public void populate() throws Exception {
		store = stores.open();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < 50; i++) {
				store.createNode(i % 5 == 0 ? "Person" : "Company",
					Properties.of().set("email", "user" + i + "@example.com").set("active", true));
			}
			txn.commit();
		}
	}

	@AfterEach
	public void closeStore() throws Exception {
		store.close();
	}

	/** Without a declaration: the property probe, then a label verification per candidate. */
	@Test
	public void testWithoutADeclarationThePropertyProbeIsUsed() {
		Calls calls = new Calls();
		QueryExecutor.Result result = run(calls, "MATCH (n:Person {email: 'user0@example.com'}) RETURN n.email");

		assertEquals(1, result.rows().size());
		assertTrue(calls.made("nodesByProperty"), "the property index is the fallback path");
		assertFalse(calls.made("nodesByLabelAndProperty"), "there is no composite index to probe");
		assertFalse(calls.made("forEachNode") || calls.made("forEachNodeWhile"), "a probe, not a scan");
	}

	/** With one: a single composite probe, and the property tree is not consulted at all. */
	@Test
	public void testADeclaredIndexIsPreferred() throws Exception {
		store.createIndex("Person", "email");

		Calls calls = new Calls();
		QueryExecutor.Result result = run(calls, "MATCH (n:Person {email: 'user0@example.com'}) RETURN n.email");

		assertEquals(1, result.rows().size());
		assertTrue(calls.made("nodesByLabelAndProperty"), "the declared index must drive the seed");
		assertFalse(calls.made("nodesByProperty"), "the property probe it replaces must not run as well");
		assertFalse(calls.made("nodesByLabel"), "nor the label scan");
		assertFalse(calls.made("forEachNode") || calls.made("forEachNodeWhile"), "a probe, not a scan");
	}

	/**
	 * The case the index is for: a property that is nearly constant.
	 * <p>
	 * Every node here is {@code active}, so the property probe hands back all fifty and the executor reads each one to
	 * find the ten {@code :Person}. The composite probe hands back ten. Same answer, and it is only the plan that says
	 * which of the two happened.
	 */
	@Test
	public void testALowSelectivityPropertyGoesThroughTheCompositeIndex() throws Exception {
		String query = "MATCH (n:Person {active: true}) RETURN n.email";

		Calls before = new Calls();
		QueryExecutor.Result unindexed = run(before, query);
		assertTrue(before.made("nodesByProperty"));

		store.createIndex("Person", "active");
		Calls after = new Calls();
		QueryExecutor.Result indexed = run(after, query);

		assertEquals(10, indexed.rows().size());
		assertEquals(unindexed.rows(), indexed.rows(), "the declaration must change the plan and not the answer");
		assertTrue(after.made("nodesByLabelAndProperty"));
		assertFalse(after.made("nodesByProperty"));
	}

	/** A declaration on a different pair must not be mistaken for one on this pattern's. */
	@Test
	public void testAnIndexOnAnotherPairIsNotUsed() throws Exception {
		store.createIndex("Company", "email");

		Calls calls = new Calls();
		run(calls, "MATCH (n:Person {email: 'user0@example.com'}) RETURN n.email");
		assertFalse(calls.made("nodesByLabelAndProperty"), ":Company.email says nothing about :Person.email");
		assertTrue(calls.made("nodesByProperty"));
	}

	/** A pattern with no label cannot use one, however many are declared. */
	@Test
	public void testAPatternWithoutALabelStillUsesThePropertyIndex() throws Exception {
		store.createIndex("Person", "email");

		Calls calls = new Calls();
		QueryExecutor.Result result = run(calls, "MATCH (n {email: 'user0@example.com'}) RETURN n.email");
		assertEquals(1, result.rows().size());
		assertTrue(calls.made("nodesByProperty"));
		assertFalse(calls.made("nodesByLabelAndProperty"));
	}

	/** A pattern with no literal property cannot either: there is nothing to probe the index with. */
	@Test
	public void testALabelOnlyPatternStillScansTheLabel() throws Exception {
		store.createIndex("Person", "email");

		Calls calls = new Calls();
		QueryExecutor.Result result = run(calls, "MATCH (n:Person) RETURN n.email");
		assertEquals(10, result.rows().size());
		assertTrue(calls.made("nodesByLabel"));
		assertFalse(calls.made("nodesByLabelAndProperty"));
	}

	// ---------------------------------------------------------------- the recorder

	private QueryExecutor.Result run(Calls calls, String query) {
		Ast.Query parsed = Cypher.parse(query);
		try (var view = store.beginRead()) {
			return new QueryExecutor(new GraphAccess(calls.wrap(store)), Map.of()).run(parsed);
		}
	}

	/**
	 * Every {@link GraphStore} method the executor calls, in order, recorded by a proxy that delegates to the real
	 * store.
	 * <p>
	 * A proxy rather than a hand-written stub because the point is to observe the choice without changing it: the
	 * executor must run against the same store it always does, and a stub that answered differently would be testing
	 * the stub.
	 */
	private static final class Calls implements InvocationHandler {

		private final List<String> names = new ArrayList<>();
		private GraphStore delegate;

		GraphStore wrap(GraphStore store) {
			this.delegate = store;
			return (GraphStore) Proxy.newProxyInstance(GraphStore.class.getClassLoader(),
				new Class<?>[] { GraphStore.class }, this);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			names.add(method.getName());
			try {
				return method.invoke(delegate, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

		boolean made(String method) {
			return names.contains(method);
		}
	}
}
