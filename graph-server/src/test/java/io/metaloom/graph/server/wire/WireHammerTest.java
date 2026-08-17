package io.metaloom.graph.server.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.client.GraphClient;
import io.metaloom.graph.client.Row;
import io.metaloom.graph.client.RowStream;
import io.metaloom.graph.core.api.GraphDatabases;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.VerifyLevel;
import io.metaloom.graph.core.fixture.Hammer;
import io.metaloom.graph.core.fixture.TempStore;

/**
 * Many clients at once, over the real socket.
 *
 * <p>
 * The wire is the layer where this project's two worst defects lived, and neither was visible to any assertion: a
 * missing {@code volatile} on the client's {@code readsPaused}, and a ROWS batch published to the consuming thread
 * before it was added to the flow-control budget. Both were green in isolation and both hung the reactor under load
 * ({@code spec/TESTING.md} section 1). So the assertion that matters most here is simply <b>that the run finishes</b>,
 * which is why {@link Hammer} carries a watchdog that dumps the stack of every thread still running and why this class
 * has a deadline of its own.
 * </p>
 *
 * <p>
 * The conserved total from {@code ParallelConsistencyTest} comes along for the ride, so that concurrency at the
 * transport layer is checked for correctness and not only for liveness: every client totalling the accounts must get
 * the same answer, because each query is one snapshot view on the server side.
 * </p>
 *
 * <p>
 * <b>The server must own its Vert.x.</b> Reaching for the caller-owned constructor leaks an acceptor thread, an event
 * loop and a worker pool per test method, and no assertion in this class or any other can see it — it surfaces later
 * as an unrelated build that hangs. The event-loop count in {@link #stop()} is the only check that catches it.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/WIRE.md">spec/WIRE.md</a> section 5.1
 * @see <a href="../../../../../../../../spec/TESTING.md">spec/TESTING.md</a> section 9
 */
@Tag("unit")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class WireHammerTest {

	private static final int ACCOUNTS = 12;
	private static final long OPENING_BALANCE = 1_000L;
	private static final long TOTAL = ACCOUNTS * OPENING_BALANCE;

	@RegisterExtension
	TempStore stores = new TempStore();

	private GraphDatabases databases;
	private GraphStore store;
	private WireServer server;
	private int port;
	private List<Long> accounts;

	private static int eventLoopsAtStart;

	@BeforeAll
	public static void countEventLoops() {
		eventLoopsAtStart = eventLoops();
	}

	@BeforeEach
	public void start() throws Exception {
		databases = GraphDatabases.open(stores.path().resolve("root"));
		store = databases.database(GraphDatabases.DEFAULT_NAME);
		accounts = new ArrayList<>();
		try (Transaction txn = store.begin()) {
			for (int i = 0; i < ACCOUNTS; i++) {
				accounts.add(store.createNode(List.of("Account"), Properties.of()
					.set("balance", OPENING_BALANCE)
					.set("n", (long) i)));
			}
			txn.commit();
		}
		// The owning constructor. See the class javadoc.
		server = new WireServer(databases, GraphDatabases.DEFAULT_NAME, WireServerOptions.defaults().withPort(0), true);
		port = server.listen().toCompletionStage().toCompletableFuture().join();
	}

	@AfterEach
	public void stop() throws Exception {
		if (server != null) {
			server.close();
		}
		if (databases != null) {
			assertEquals(0, store.snapshotStats().openViews(), "a read view outlived its query");
			store.verify(VerifyLevel.FULL);
			databases.close();
		}
		int alive = eventLoops();
		for (int i = 0; i < 100 && alive > eventLoopsAtStart + 2; i++) {
			Thread.sleep(20);
			alive = eventLoops();
		}
		assertTrue(alive <= eventLoopsAtStart + 2, "this test leaked a Vert.x: " + alive
			+ " event loop threads are alive against " + eventLoopsAtStart + " before the class started");
	}

	private static int eventLoops() {
		int count = 0;
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (thread.getName().startsWith("vert.x-eventloop") || thread.getName().startsWith("vert.x-acceptor")) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Reads and writes from many clients at once. Every client opens its own connection, because a connection is the
	 * unit the server's flow control and query slots are accounted against.
	 */
	@Test
	public void testManyClientsReadingAndWritingConcurrently() throws Exception {
		AtomicLong totals = new AtomicLong();
		AtomicLong created = new AtomicLong();

		Hammer.named("wire")
			.threads(6)
			.opsPerThread(25)
			.watchdogSeconds(90)
			.op("readTotal", 3, rnd -> {
				try (GraphClient client = connect();
					RowStream rows = client.query("MATCH (a:Account) RETURN a.balance AS balance")) {
					long total = 0;
					int seen = 0;
					while (rows.hasNext()) {
						Row row = rows.next();
						total += row.longValue("balance");
						seen++;
					}
					assertEquals(ACCOUNTS, seen, "a client saw " + seen + " accounts rather than " + ACCOUNTS);
					assertEquals(TOTAL, total, "a client totalled " + total + " rather than " + TOTAL
						+ ", so its query spanned more than one committed state");
					totals.incrementAndGet();
				}
			})
			.op("transfer", 2, rnd -> {
				int from = rnd.nextInt(ACCOUNTS);
				int to = rnd.nextInt(ACCOUNTS);
				if (from == to) {
					return;
				}
				// One statement so the server runs it as one transaction; the amount is fixed so the total is
				// conserved whatever order these land in.
				try (GraphClient client = connect()) {
					client.execute("MATCH (a:Account {n: " + from + "}), (b:Account {n: " + to + "}) "
						+ "WHERE a.balance > 10 "
						+ "SET a.balance = a.balance - 10, b.balance = b.balance + 10");
				}
			})
			.op("write", 1, rnd -> {
				try (GraphClient client = connect()) {
					client.execute("CREATE (t:Trace {n: " + rnd.nextInt(100_000) + "})");
					created.incrementAndGet();
				}
			})
			.op("streamLarge", 1, rnd -> {
				try (GraphClient client = connect();
					RowStream rows = client.query("MATCH (a:Account) RETURN a.balance AS balance, a.n AS n")) {
					while (rows.hasNext()) {
						rows.next();
					}
				}
			})
			.run()
			.assertRan("readTotal", "transfer", "write", "streamLarge");

		assertTrue(totals.get() > 0, "no client ever totalled the accounts");
		assertEquals(created.get(), store.nodesByLabel("Trace").size(),
			"every acknowledged CREATE must have left exactly one node");

		long finalTotal = 0;
		for (long account : accounts) {
			finalTotal += (Long) store.nodeProperty(account, "balance");
		}
		assertEquals(TOTAL, finalTotal, "value was created or destroyed over the wire");
		System.out.println("[wire] totals=" + totals.get() + " traces=" + created.get());
	}

	/**
	 * Clients that disconnect part-way through a stream. The server has to reclaim the query slot and, above all, the
	 * read view — a view left behind by a client that hung up holds back reclamation for the life of the process.
	 */
	@Test
	public void testClientsAbandoningStreamsLeaveNoViewBehind() throws Exception {
		Hammer.named("wire-abandon")
			.threads(4)
			.opsPerThread(20)
			.watchdogSeconds(90)
			.op("abandonEarly", 2, rnd -> {
				GraphClient client = connect();
				RowStream rows = client.query("MATCH (a:Account) RETURN a.balance AS balance");
				if (rows.hasNext()) {
					rows.next();
				}
				// Deliberately not draining: close underneath the stream, as a client that crashed would.
				client.close();
			})
			.op("drain", 1, rnd -> {
				try (GraphClient client = connect();
					RowStream rows = client.query("MATCH (a:Account) RETURN a.balance AS balance")) {
					while (rows.hasNext()) {
						rows.next();
					}
				}
			})
			.run()
			.assertRan("abandonEarly", "drain");

		// Views are closed by the server thread that opened them, so allow a moment before concluding one leaked.
		for (int i = 0; i < 100 && store.snapshotStats().openViews() > 0; i++) {
			Thread.sleep(20);
		}
		assertEquals(0, store.snapshotStats().openViews(),
			"a client that hung up mid-stream left a read view open on the server");
	}

	private GraphClient connect() {
		return GraphClient.connect("127.0.0.1", port);
	}
}
