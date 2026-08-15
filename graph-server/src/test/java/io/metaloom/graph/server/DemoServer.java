package io.metaloom.graph.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;

/**
 * A server over a small, fixed graph — what the browser tests drive, and what a human can start to look at the UI
 * without having a store of their own.
 *
 * <pre>
 * tools/demo-server.sh [directory] [port]
 * </pre>
 *
 * <h2>Why the data is fixed rather than generated</h2>
 *
 * <p>
 * Every end-to-end assertion is about something specific: that Alice's inspector shows {@code age 34}, that the
 * {@code BOUGHT} edge is drawn dashed because it is lightweight, that two {@code Person} circles are the same colour
 * and a {@code Product} circle is not. Random data would force the tests to assert on shapes rather than on values,
 * and a test that only checks shapes passes just as happily when the values are wrong.
 * </p>
 *
 * <p>
 * The graph is deliberately awkward in the ways the UI has to handle: a node with two labels, a node with none, a
 * lightweight edge with no relationship record, two relationships between the same pair (which must be drawn apart
 * rather than on top of each other), a self-relationship, and a label whose name is not a bare identifier and so has
 * to be backtick-quoted when the sidebar turns it into a query.
 * </p>
 *
 * <p>
 * This lives in the test tree: it is a fixture, and shipping a seeder inside the server would be a write path in a
 * read-only application.
 * </p>
 */
public final class DemoServer {

	private DemoServer() {
	}

	public static void main(String[] args) throws Exception {
		// Vert.x copies classpath resources into .vertx/file-cache-* the first time each is served, and serves the
		// copy from then on. A server left running across an `npm run build` therefore keeps serving the previous
		// bundle — which is how the first run of the browser suite came to test a UI that no longer existed, with
		// every assertion failing on markup that had been replaced. Off here, where the cost is nothing and a stale
		// bundle would invalidate the whole suite.
		System.setProperty("vertx.disableFileCaching", "true");

		Path directory = Path.of(args.length > 0 ? args[0] : "target/demo-store");
		int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;

		boolean fresh = !Files.exists(directory) || isEmpty(directory);
		GraphStore store = GraphStore.open(directory);
		if (fresh) {
			seed(store);
		}

		GraphServer server = new GraphServer(store);
		int actual = server.listen(port).toCompletionStage().toCompletableFuture().join();
		// The browser tests wait for this line, so it is the contract between the script and the harness.
		System.out.println("Graph server on http://localhost:" + actual);
		System.out.flush();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				server.close();
				store.close();
			} catch (IOException e) {
				// Shutting down anyway.
			}
		}));
		Thread.currentThread().join();
	}

	private static boolean isEmpty(Path directory) throws IOException {
		try (var entries = Files.list(directory)) {
			return entries.findAny().isEmpty();
		}
	}

	public static void seed(GraphStore store) throws IOException {
		try (Transaction txn = store.begin()) {
			long alice = store.createNode(List.of("Person", "Employee"),
				Properties.of().set("name", "Alice").set("age", 34L).set("city", "Bonn").set("active", true));
			long bob = store.createNode(List.of("Person"),
				Properties.of().set("name", "Bob").set("age", 41L).set("city", "Köln"));
			long carol = store.createNode(List.of("Person"),
				Properties.of().set("name", "Carol").set("age", 29L).set("city", "Bonn"));
			long dave = store.createNode(List.of("Person"),
				Properties.of().set("name", "Dave").set("age", 52L));

			long acme = store.createNode(List.of("Company"), Properties.of().set("name", "Acme").set("founded", 1994L));
			long globex = store.createNode(List.of("Company"), Properties.of().set("name", "Globex"));

			long widget = store.createNode(List.of("Product"), Properties.of().set("name", "Widget").set("price", 9.99));
			long gadget = store.createNode(List.of("Product"), Properties.of().set("name", "Gadget").set("price", 24.5));

			// A label that is not a bare identifier: the sidebar has to quote it to build a query from it.
			store.createNode(List.of("Legacy System"), Properties.of().set("name", "Mainframe"));
			// And a node with no label at all, which still has to be drawn and still has to be counted.
			store.createNode(List.of(), Properties.of().set("name", "Anonymous"));

			store.createRelationship(alice, "KNOWS", bob, Properties.of().set("since", 2019L).set("strength", 0.8));
			store.createRelationship(bob, "KNOWS", carol, Properties.of().set("since", 2021L));
			store.createRelationship(carol, "KNOWS", alice, Properties.of().set("since", 2015L));
			// Two relationships between the same pair, which must not be drawn one on top of the other.
			store.createRelationship(alice, "MENTORS", bob, Properties.of().set("since", 2020L));
			// A self-relationship, which needs a loop rather than a line of zero length.
			store.createRelationship(dave, "KNOWS", dave, Properties.of().set("since", 2000L));

			store.createRelationship(alice, "WORKS_AT", acme, Properties.of().set("role", "Engineer"));
			store.createRelationship(bob, "WORKS_AT", acme, Properties.of().set("role", "Designer"));
			store.createRelationship(carol, "WORKS_AT", globex, Properties.of().set("role", "Analyst"));

			// Lightweight edges: no relationship record, so no id and no properties. The UI marks them.
			store.createEdge(alice, "BOUGHT", widget);
			store.createEdge(bob, "BOUGHT", gadget);
			store.createEdge(carol, "BOUGHT", widget);

			txn.commit();
		}
		store.checkpoint();
	}
}
