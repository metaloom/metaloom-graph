# Graph Storage FFM PoC

A **native-code backed graph storage layer** for Java, built on the Foreign Function and Memory API.

It stores nodes, relationships and attributes in compact, purpose-built binary files, optimised for
SSD. The defining design constraint:

> **An element id decodes arithmetically into the exact file and byte offset of its record.**
> Reading an element by id costs zero index probes and zero extra seeks.

## Status

Durable, transactional, indexed, compacting, hardened, with snapshot isolation for readers, a read-only openCypher subset and a browser UI. 355 tests, plus 12 in the Rust crate.

| | |
|---|---|
| **Durability** | Write-ahead log. Every commit is fsynced *before* it touches a data file, so uncommitted data cannot reach disk and recovery is a single redo pass. |
| **Query language** | A read-only openCypher subset — `MATCH` with variable-length paths, `WHERE`, `RETURN`, `WITH`, `UNWIND`, aggregation, parameters — in its own module, plus a Vert.x server with a D3 UI. [spec/CYPHER.md](spec/CYPHER.md) |
| **Transactions** | `begin` / `commit` / `rollback`, plus auto-commit for single operations. |
| **Isolation** | One writer at a time; many concurrent readers. `store.beginRead()` gives a reader **MVCC snapshot isolation** — a view fixed at one instant across many reads, while the writer keeps committing. Reads take no lock and scale to 10x at 32 threads; writes are flat by design. **No concurrent writers.** |
| **Indexes** | Label and property B+trees, unique constraints, and an index-versus-scan differential test. |
| **Space reclamation** | Two halves. `reclaim()` punches all-free pages and breaks no ids. `compact()` relocates records to close holes within pages, rewrites every internal reference, and pulls the high water marks down; it is the one operation that invalidates ids. A store churned to a tenth of its slots comes back to 0.95 liveness. |
| **Native layer** | Rust via FFM, with `cargo test` and `clippy -D warnings` inside `mvn test`. **io_uring is 12.4x** over a `pread` loop on 64 scattered reads — the one capability the JVM cannot approach. The hot-loop codecs are not: 1.38x on edges, 7.8x *slower* on CRC32C. |
| **Hardening** | Reads-per-operation and write-amplification budgets enforced as build failures; a differential harness against an in-memory model across crashes and compaction; every format parser tested against corrupt and hostile input; a `kill -9` loop that checks acknowledged commits. |
| **Not there** | No concurrent writers, no Cypher writes, no backup mechanism, no repair tool. [spec/OPERATIONS.md](spec/OPERATIONS.md) says so plainly. |
| **Adjacency sizing** | A block is a run of 512 byte cells that doubles as the node fills, so degree 3 costs 512 bytes rather than a page. Fixing this took a 10M-node random graph from 61 GiB to 6.2 GiB and its load from 43 to 11 minutes. [spec/reports/SCALE.md](spec/reports/SCALE.md) has the before and after. |

What is done and what is left, as one checklist: [spec/PROGRESS.md](spec/PROGRESS.md). The reason for
every gap: [spec/reports/PHASE_STATUS.md](spec/reports/PHASE_STATUS.md).

**It has been tried on something real.** Phase 10 built it into MetaLoom as an index over the asset relationship
graph — behind an interface, alongside Postgres, with a differential check that both return the same answers. They
do, through insertions, deletions, a rebuild and a compaction. The recommendation is nonetheless **not to adopt**:
against the obvious SQL it is 657x faster, against the same query written properly it is 2.4x faster, and only the
second number is a reason to do anything.
[spec/reports/PHASE_STATUS.md §7](spec/reports/PHASE_STATUS.md#7-phase-10--metaloom-integration) has the numbers.

**At scale** ([spec/reports/SCALE.md](spec/reports/SCALE.md)): 10 million nodes and 20 million random
relationships load in **11 to 25 minutes** and occupy **6.1 GiB**. Reopening the finished store takes
1 ms, `nodeLabel` runs at 4.2M/s over it and `neighbours` at 1.1M/s. The load figure is a range because
it has been measured four times with the same command and the edge phase varied 2.5x across them while
the resulting store stayed identical to the byte — every run, and what is known about the spread, is in
[spec/GRAPH_BENCHMARK.md](spec/GRAPH_BENCHMARK.md).

Measured against the original proof of concept ([spec/reports/BASELINE.md](spec/reports/BASELINE.md)):

| | Before | Now |
|---|---|---|
| Node record | 1080 B | 64 B |
| Node with two properties | ~1144 B | 128 B |
| Directed adjacency entry | 32 B | 1.0 to 1.3 B, from degree 3 upwards |
| Reads to enumerate d neighbours | d | 0 for d <= 2, one per ~8000 otherwise |
| Memory mappings for N accesses | N | 1 per segment |
| Durability | none | fsync before any data page is touched |
| Space returned after mass deletion | none | hole punching, plus compaction to over 0.94 liveness |

## API

Every line below runs as a test:
[`UsageExampleTest`](src/test/java/io/metaloom/graph/core/example/UsageExampleTest.java) is this
example with assertions, so the documentation cannot drift from the API.

```java
try (GraphStore store = GraphStore.open(Path.of("/data/mygraph"))) {

    long person;
    long vehicle;

    // One transaction for a bulk load: durability costs an fsync per commit, so batching matters.
    try (Transaction txn = store.begin()) {
        person = store.createNode("Person", Properties.of()
            .set("name", "Wes Anderson")
            .set("born", 1969));

        vehicle = store.createNode("Vehicle", Properties.of().set("name", "VW Beetle"));

        // A property free edge: 16 bytes in total, no relationship record.
        store.createEdge(person, "OWNS", vehicle);

        // An edge that carries properties gets a record and a stable id.
        store.createRelationship(person, "DROVE", vehicle,
            Properties.of().set("since", Instant.parse("2019-04-01T00:00:00Z")));

        txn.commit();
    }

    for (Edge edge : store.neighbours(person, Direction.OUT, Set.of("OWNS"))) {
        System.out.println(store.nodeProperties(edge.neighbourId()).getString("name"));
    }

    List<Long> people = store.nodesByLabel("Person");
    List<Long> born69 = store.nodesByProperty("born", 1969);

    // A read view fixes what every read on this thread sees, however much the writer commits
    // underneath it. Close it: an open view holds versions in memory until it does.
    try (ReadTransaction view = store.beginRead()) {
        List<Edge> before = store.traverse(person, Direction.OUT, null, 3);
        store.createEdge(person, "OWNS", store.createNode("Vehicle"));
        assert store.traverse(person, Direction.OUT, null, 3).equals(before);
    }

    store.verify(VerifyLevel.FULL);

    // Give space back. reclaim() moves nothing and keeps every id valid; compact() closes holes
    // *inside* pages and is the only thing in the engine that invalidates an id.
    store.reclaim();
    CompactionReport report = store.compact();
}
```

## Querying

A read-only openCypher subset, in a module that depends on the storage engine's public API and nothing
inside it. Full grammar, null semantics and limits: [spec/CYPHER.md](spec/CYPHER.md).

```java
CypherResult result = Cypher.query(store, """
    MATCH (p:Person)-[:KNOWS*1..2]->(friend)
    WHERE p.city = $city AND friend.age IS NOT NULL
    RETURN friend.name AS name, count(*) AS routes
    ORDER BY routes DESC LIMIT 10
    """, Map.of("city", "Bonn"));

for (CypherRow row : result) {
    System.out.println(row.string("name") + " " + row.longValue("routes"));
}
```

Each query runs inside a snapshot read view, so it sees one consistent state however long it takes —
and joins the caller's view if there already is one.

There is also a browser UI: a query box, a D3 force layout of the nodes and relationships a query
returns, and a table of the raw rows.

```bash
mvn -o -pl graph-server exec:java \
  -Dexec.mainClass=io.metaloom.graph.server.GraphServer \
  -Dexec.args="/data/mygraph 8080"
```

## Building

One command builds everything — three Java modules and the Rust native layer:

```bash
mvn clean install
```

The reactor is a parent pom over `graph-core`, `graph-cypher` and `graph-server`. `graph-core` drives
`cargo` through the exec plugin at four points: **build** before compile, **package** the resulting
shared object into the jar under its platform classifier, **test** the crate with
`cargo test` and `clippy -D warnings` during `mvn test`, and **clean** the crate's target directory
during `mvn clean`.

```bash
mvn test              # 355 tests across three modules + the Rust crate's own
mvn test -Pslow       # adds the scaling curve, the soak and the long-running reader
mvn test -Pbench      # adds the benchmarks and the performance gates
mvn test -Pall-tests  # everything
tools/kill9-loop.sh   # kill the process under load, reopen, check every acknowledged commit

mvn test -Phuge -Dtest=GraphScaleBenchmark   # 10M nodes + 20M edges: 11 min, 6.2 GiB
```

| Flag | |
|---|---|
| `-Dnative.skip=true` | Build with no native layer at all, even where `cargo` is present. The whole suite passes on the pure-Java path; this is how that is checked. |
| `-Poffline` | Passes cargo's offline flag, for a warm registry with no network. Pairs with `mvn -o`. |
| `-DskipTests` | Skips the Rust tests too, not only the Java ones. |

Requires JDK 25. **The Rust layer is optional**: the build skips it automatically when `cargo` is
absent and the engine runs on the pure-Java path — every native capability has a fallback
([spec/NATIVE_FFM.md](spec/NATIVE_FFM.md) section 5).

Run the query UI:

```bash
mvn -pl graph-server exec:java -Dexec.args="/data/mygraph 8080"
```

## Specification

[spec/OPERATIONS.md](spec/OPERATIONS.md) is the one to read before running this anywhere real: sizing, tuning, what
to monitor, and an explicit list of what is missing.

[spec/GRAPH_STORAGE_CONTEXT.md](spec/GRAPH_STORAGE_CONTEXT.md) is the entry point: it routes to every
spec file, carries the cheat sheet and the conventions, and links the phased plan in
[spec/PLAN.md](spec/PLAN.md). [spec/CURRENT_STATE.md](spec/CURRENT_STATE.md) records the proof of
concept this replaced, including its defect register.
