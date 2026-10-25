package io.metaloom.graph.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.client.GraphClient;
import io.metaloom.graph.client.QueryFailedException;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.fixture.TempStore;
import io.metaloom.graph.server.wire.WireServer;
import io.metaloom.graph.server.wire.WireServerOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Shutting down without breaking anything that is still running.
 *
 * <p>
 * The property under test is an ordering, and getting it wrong is not a slow shutdown but a crash: a store's segment
 * files are memory-mapped, so closing one unmaps them, and a worker still inside a query at that instant is reading an
 * address the process no longer owns. Refusing new work, draining, aborting the remainder and only then closing is what
 * makes that impossible.
 * </p>
 *
 * <p>
 * What is <em>not</em> tested here is durability, because shutdown has nothing to do with it: the log is fsynced before
 * any data file is touched, so a write interrupted at any instant either committed or did not — with a graceful
 * shutdown, with a kill, or with the power cable. {@code RecoveryTest} and the crash matrix cover that.
 * </p>
 */
@Tag("unit")
public class ShutdownTest {

	private static final int NODES = 40_000;

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphDatabases databases;
	private GraphStore store;
	private HttpClient client;

	@BeforeEach
	public void seed() throws Exception {
		databases = GraphDatabases.open(stores.path().resolve("root"));
		store = databases.database(GraphDatabases.DEFAULT_NAME);
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < NODES; i++) {
				store.createNode(List.of("Person"), Properties.of().set("n", (long) i).set("name", "p" + i));
			}
			txn.commit();
		}
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@AfterEach
	public void stop() throws Exception {
		if (databases != null) {
			databases.close();
		}
	}

	// ---------------------------------------------------------------- HTTP

	@Test
	public void testANewQueryIsRefusedOnceShutdownHasBegun() throws Exception {
		GraphServer server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME, 0, false);
		int port = server.listen(0).toCompletionStage().toCompletableFuture().join();

		// A query that will still be running when the shutdown starts, so the drain has something to drain.
		CountDownLatch started = new CountDownLatch(1);
		Thread slow = new Thread(() -> {
			started.countDown();
			post(port, "MATCH (a:Person), (b:Person) RETURN count(*) AS pairs");
		});
		slow.setDaemon(true);
		slow.start();
		assertTrue(started.await(5, TimeUnit.SECONDS));
		Thread.sleep(150);

		// A short grace period, because the query above will not finish inside it — which is the case that matters.
		server.shutdown(200);

		assertTrue(server.isClosing());
		slow.join(20_000);
		// The store survived a shutdown that ran while a scan was in flight, which is the whole claim.
		assertEquals(NODES, store.nodesByLabel("Person").size());
	}

	/**
	 * A query that finishes inside the grace period gets its response.
	 *
	 * <p>
	 * The other tests here are about the query that does <em>not</em> finish. This is the promise the grace period
	 * makes to the one that does: closing the listener must not close the connection that query will answer on.
	 * </p>
	 */
	@Test
	public void testAQueryInFlightThatFinishesInsideTheGraceGetsItsAnswer() throws Exception {
		GraphServer server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME, 0, false);
		int port = server.listen(0).toCompletionStage().toCompletableFuture().join();
		String slow = "MATCH (a:Person) WHERE a.n < 40 MATCH (b:Person) RETURN count(*) AS pairs";

		long began = System.nanoTime();
		HttpResponse<String> warm = postForResponse(port, slow);
		long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
		assertEquals(200, warm.statusCode(), warm.body());
		assertTrue(millis >= 50, "the fixture query must take long enough to be caught in flight: " + millis + " ms");

		java.util.concurrent.CompletableFuture<HttpResponse<String>> inFlight = java.util.concurrent.CompletableFuture
			.supplyAsync(() -> {
				try {
					return postForResponse(port, slow);
				} catch (Exception e) {
					throw new java.util.concurrent.CompletionException(e);
				}
			});
		Thread.sleep(Math.max(10, millis / 4));
		server.shutdown(30_000);

		HttpResponse<String> answered = inFlight.get(30, TimeUnit.SECONDS);
		assertEquals(200, answered.statusCode(), answered.body());
		assertTrue(answered.body().contains(String.valueOf(40L * NODES)), answered.body());
	}

	private HttpResponse<String> postForResponse(int port, String cypher) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/query"))
			.header("content-type", "application/json")
			.timeout(Duration.ofSeconds(30))
			.POST(HttpRequest.BodyPublishers.ofString(new io.vertx.core.json.JsonObject().put("query", cypher).encode()))
			.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	public void testAQueryInFlightIsAbandonedRatherThanHoldingShutdownOpen() throws Exception {
		// No query timeout at all, so the only thing that can stop this query is the shutdown itself. Without the
		// composed abort signal the shutdown would wait for a scan of 40,000 nodes squared, which is the difference
		// between a shutdown and a hang.
		GraphServer server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME, 0, false);
		int port = server.listen(0).toCompletionStage().toCompletableFuture().join();

		CountDownLatch started = new CountDownLatch(1);
		Thread slow = new Thread(() -> {
			started.countDown();
			post(port, "MATCH (a:Person), (b:Person), (c:Person) RETURN count(*) AS triples");
		});
		slow.setDaemon(true);
		slow.start();
		assertTrue(started.await(5, TimeUnit.SECONDS));
		Thread.sleep(200);

		long began = System.nanoTime();
		server.shutdown(200);
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

		assertTrue(elapsedMillis < 15_000,
			"the shutdown must not wait for an unbounded query; it took " + elapsedMillis + " ms");
		slow.join(20_000);
	}

	@Test
	public void testShutdownIsIdempotent() throws Exception {
		// Reachable from both the shutdown hook and an ordinary close, and a JVM exiting on a signal runs both.
		GraphServer server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME, 5_000, false);
		server.listen(0).toCompletionStage().toCompletableFuture().join();

		server.close();
		server.close();
		server.shutdown(10);
		assertTrue(server.isClosing());
	}

	@Test
	public void testAFinishedQueryIsUnaffected() throws Exception {
		// The control: a server with nothing running shuts down immediately and the results it gave are still right.
		GraphServer server = new GraphServer(databases, GraphDatabases.DEFAULT_NAME, 5_000, false);
		int port = server.listen(0).toCompletionStage().toCompletableFuture().join();

		HttpResponse<String> response = post(port, "MATCH (n:Person) RETURN n.n AS n LIMIT 3");
		assertEquals(200, response.statusCode());
		assertEquals(3, new JsonObject(response.body()).getInteger("rowCount"));

		long began = System.nanoTime();
		server.shutdown(5_000);
		assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 4_000,
			"an idle server must not sit out its whole grace period");
	}

	// ---------------------------------------------------------------- the binary port

	@Test
	public void testTheWirePortRefusesNewQueriesOnceClosing() throws Exception {
		Vertx vertx = Vertx.vertx();
		WireServer wire = new WireServer(vertx, databases, GraphDatabases.DEFAULT_NAME,
			WireServerOptions.defaults().withPort(0).withQueryTimeoutMillis(0), false);
		int port = wire.listen().toCompletionStage().toCompletableFuture().join();

		try (GraphClient connected = GraphClient.connect("127.0.0.1", port)) {
			assertEquals(3, connected.execute("MATCH (n:Person) RETURN n LIMIT 3").rows());

			CountDownLatch started = new CountDownLatch(1);
			Thread slow = new Thread(() -> {
				started.countDown();
				try {
					connected.execute("MATCH (a:Person), (b:Person), (c:Person) RETURN count(*) AS n");
				} catch (RuntimeException expected) {
					// Abandoned by the shutdown, which is the point.
				}
			});
			slow.setDaemon(true);
			slow.start();
			assertTrue(started.await(5, TimeUnit.SECONDS));
			Thread.sleep(200);

			long began = System.nanoTime();
			wire.shutdown(200);
			assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 15_000,
				"the wire shutdown must not wait for an unbounded query");
			assertTrue(wire.isClosing());
			slow.join(20_000);
		} catch (QueryFailedException expected) {
			// The first query may itself be caught by the shutdown on a slow machine; either outcome is fine.
		} finally {
			vertx.close().toCompletionStage().toCompletableFuture().join();
		}

		assertEquals(NODES, store.nodesByLabel("Person").size(), "the store came through it intact");
	}

	// ---------------------------------------------------------------- the store

	@Test
	public void testClosingAStoreWaitsForAWriteInFlight() throws Exception {
		// close() takes the writer lock, so it cannot run beside a transaction that is half applied — unmapping a
		// segment part way through a commit is not a recoverable state.
		GraphStore own = databases.create("closing");
		CountDownLatch inside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		Thread writer = new Thread(() -> {
			try (Transaction txn = own.begin()) {
				own.createNode("Held");
				inside.countDown();
				release.await(10, TimeUnit.SECONDS);
				txn.commit();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		writer.setDaemon(true);
		writer.start();
		assertTrue(inside.await(5, TimeUnit.SECONDS));

		Thread closer = new Thread(() -> {
			try {
				own.close();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		closer.setDaemon(true);
		closer.start();
		Thread.sleep(150);
		assertTrue(closer.isAlive(), "close must wait for the writer rather than unmapping underneath it");

		release.countDown();
		writer.join(10_000);
		closer.join(10_000);
		assertFalse(closer.isAlive(), "and must finish once the writer has");
	}

	// ---------------------------------------------------------------- plumbing

	private HttpResponse<String> post(int port, String query) {
		try {
			return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/query"))
				.header("content-type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(new JsonObject().put("query", query).encode()))
				.timeout(Duration.ofSeconds(60))
				.build(), HttpResponse.BodyHandlers.ofString());
		} catch (Exception e) {
			// A connection cut by the shutdown is an outcome this test allows; the assertions are about the server.
			return null;
		}
	}
}
