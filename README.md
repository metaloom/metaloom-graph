# MetaLoom // Graph

A **native-code backed graph storage layer** for Java, built on the Foreign Function and Memory API.

It stores nodes, relationships and attributes in compact, purpose-built binary files, optimised for
SSD. The defining design constraint:

> **An element id decodes arithmetically into the exact file and byte offset of its record.**
> Reading an element by id costs zero index probes and zero extra seeks.

## Status

Durable, transactional, indexed, compacting, hardened, with snapshot isolation for readers, an openCypher subset that reads and writes, and a browser UI. **First release: 0.1.0-alpha.1** — see [CHANGELOG.md](CHANGELOG.md) for what is in it, what is not, and what an alpha does and does not promise. 1,577 Java tests across seven modules, 17 in the UI and 12 in the Rust crate, plus 30 browser cases on demand and an 11-check smoke test of the built image.

| | |
|---|---|
| **Durability** | Write-ahead log. Every commit is fsynced *before* it touches a data file, so uncommitted data cannot reach disk and recovery is a single redo pass. |
| **Query language** | An openCypher subset — `MATCH` and `OPTIONAL MATCH` with variable-length paths and `shortestPath` / `allShortestPaths`, `WHERE`, `RETURN`, `WITH`, `UNWIND`, `CASE`, `CALL { … }` and `EXISTS` / `COUNT` / `COLLECT` subqueries, list comprehensions, `all` / `any` / `none` / `single`, temporal types, aggregation, parameters, and `CREATE` / `MERGE` / `SET` / `REMOVE` / `DELETE` — in its own module, plus a single-jar Vert.x server and a React and D3 graph browser. Writes are off on both ports unless the server is started with `--allow-write`. [spec/CYPHER.md](spec/CYPHER.md) |
| **Transactions** | `begin` / `commit` / `rollback`, plus auto-commit for single operations. |
| **Isolation** | **MVCC snapshot isolation for readers and writers alike.** `store.beginRead()` fixes a reader's view at one instant across many reads; `store.begin()` does the same for a writer and validates its writes at commit, so two writers that touch the same bytes end with one of them retrying rather than one of them lost. `SERIALIZABLE` additionally validates what a transaction read. Reads take no lock and scale to 10x at 32 threads. |
| **Indexes** | Label and property B+trees, unique constraints, and an index-versus-scan differential test. |
| **Space reclamation** | Two halves. `reclaim()` punches all-free pages and breaks no ids. `compact()` relocates records to close holes within pages, rewrites every internal reference, and pulls the high water marks down; it is the one operation that invalidates ids. A store churned to a tenth of its slots comes back to 0.95 liveness. |
| **Native layer** | Rust via FFM, with `cargo test` and `clippy -D warnings` inside `mvn test`. **io_uring is 12.4x** over a `pread` loop on 64 scattered reads — the one capability the JVM cannot approach. The hot-loop codecs are not: 1.38x on edges, 7.8x *slower* on CRC32C. |
| **Hardening** | Reads-per-operation and write-amplification budgets enforced as build failures; a differential harness against an in-memory model across crashes and compaction; every format parser tested against corrupt and hostile input; a `kill -9` loop that checks acknowledged commits. |
| **Not there** | No map projections, pattern comprehensions or spatial types ([spec/CYPHER.md §5.5](spec/CYPHER.md)), no repair tool, and no *physical* snapshot or point-in-time recovery. Authentication and per-database RBAC exist ([spec/AUTH.md](spec/AUTH.md)), and **TLS exists on the binary port only** ([spec/WIRE.md §7.1](spec/WIRE.md)) — the HTTP and MCP ports expect a proxy in front of them, or a password crosses in cleartext. [spec/OPERATIONS.md](spec/OPERATIONS.md) says so plainly. |
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
[`UsageExampleTest`](graph-core/src/test/java/io/metaloom/graph/core/example/UsageExampleTest.java) is this
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

An openCypher subset, in a module that depends on the storage engine's public API and nothing
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

Each **read** runs inside a snapshot read view, so it sees one consistent state however long it takes —
and joins the caller's view if there already is one.

`Cypher.stream` is the same execution path with a sink instead of a list, for a result too large to
hold or a query that has to be stoppable. `Cypher.query` *is* `stream` with a sink that collects, so
the two cannot drift apart.

A **write** runs in one transaction instead, joining the caller's if there is one, and commits before
it delivers a single row — so nothing can arrive from a transaction that then rolls back. It reports
what it changed, which for a query with no `RETURN` is the whole result:

```java
try (Transaction txn = store.begin()) {
    Cypher.query(store, "CREATE (n:Person {name: $name})", Map.of("name", "Ada"));
    CypherStats stats = Cypher.query(store, "MATCH (n:Person) SET n.seen = true").stats();
    System.out.println(stats.summary());     // 1 property set
    txn.commit();                            // both queries, or neither
}
```

**Both servers refuse writes unless started with `--allow-write`**; embedded callers are unaffected.
The flag bounds the whole deployment whatever anybody was granted, so that default is the difference between handing out a copy of the
data and handing out the ability to delete it.

## Several databases

A server is pointed at a **root**, and each database is a directory under it — its own segments, WAL,
dictionaries and lock. Selectable from the browser UI, from the HTTP routes with `?database=`, and
from the client at connect time: [spec/DATABASES.md](spec/DATABASES.md).

```
/data/graphs/
  default/     MANIFEST  seg/  wal/  dict/  LOCK
  sales/       MANIFEST  seg/  wal/  dict/  LOCK
```

A name arrives from the network and becomes a path component, so it is checked
against an allow-list — letters, digits, `-` and `_` — rather than a search for the sequences known to
be dangerous. Nothing is created implicitly: asking for a database that is not there is an error, not
a create.

## Over the network

Two ports over the same root. HTTP on 8080 serves the browser UI and `/api/query`, which builds a whole
result as JSON — right for a browser drawing two hundred nodes. The binary port streams rows as the
engine produces them, which is the one to use for anything larger:
[spec/WIRE.md](spec/WIRE.md).

```java
try (GraphClient client = GraphClient.connect("localhost", 8081, "sales");
     RowStream rows = client.query("MATCH (a:Airport) RETURN a.code, a.name")) {
    for (Row row : rows) {
        System.out.println(row.string("a.code") + " " + row.string("a.name"));
    }
}
```

Give the server a certificate and it opens a **second** binary port that terminates TLS, leaving the
first one alone so clients can move across one at a time:

```bash
graph-server --wire-tls-cert=/tls/tls.crt --wire-tls-key=/tls/tls.key /data   # 8081 and 8084
```

```java
ClientOptions options = ClientOptions.defaults()
    .withTls(ClientTls.system())                 // or ClientTls.trusting(pem) for a private CA
    .withCredentials("admin", password);
try (GraphClient client = GraphClient.connect("graph.internal", 8084, "sales", options)) { … }
```

The client verifies the certificate *and* the hostname unless told otherwise. `--wire-tls-only` closes
the cleartext port once nothing is using it.

The database is fixed for the connection: a read view belongs to one store, so a connection that could
change store mid-flight would be one whose open views outlive their subject. Two databases at once
means two connections.

The client depends on the protocol module and Vert.x — not on the storage engine, not on the query
engine, and so not on FFM or the native library. A million rows cost it one batch of memory at a time;
a consumer that falls behind pauses the socket, which the server discovers as backpressure.

**It bounds the payload, not the executor.** The engine is still clause-at-a-time over a list, so a
query's peak *server* memory is its widest intermediate result. What streaming buys is bounded client
memory, a first row that arrives before the last one is found, cancellation, and a deadline the engine
honours while it scans.

**Both ports require a login; only the binary port can encrypt.** A connection presents a credential in
the handshake. On HTTP and on the cleartext binary port a password crosses unencrypted, so the binary port
binds loopback by default and HTTP expects a proxy in front of it — a credential says who the peer is, and
only the network under it says who could read the credential on its way past. The TLS listener above is the
exception.
[spec/AUTH.md](spec/AUTH.md) is the design. Which transport, and the measured trial that chose it:
[spec/TRANSPORT_DECISION.md](spec/TRANSPORT_DECISION.md).

## The graph browser

`graph-server` builds a single runnable jar — engine, query language, Vert.x and the UI inside it:

```bash
java -jar graph-server/target/graph-server.jar /data/mygraph 8080

# Nothing to look at yet? --demo fills an empty store with an air route network.
java -jar graph-server/target/graph-server.jar --demo /tmp/demo 8080
java -jar graph-server/target/graph-server.jar --demo-size=large /tmp/big 8080
tools/demo-server.sh          # the same thing from the reactor
```

| `--demo-size` | Airports | Nodes | Relationships | Seed time |
|---|---|---|---|---|
| `tiny` | 12 | 37 | 93 | 55 ms |
| `small` *(default)* | 58 | 159 | 483 | 55 ms |
| `medium` | 400 | 860 | 4,893 | 150 ms |
| `large` | 3,000 | 6,060 | 29,581 | 360 ms |

Any number works too — `--demo-size=250`. **`--demo` only ever writes into an empty store**; one that already has
nodes is left alone and said so, which makes the flag safe to leave in a start script.

The demo graph is airports in cities in countries, airlines with hubs, and routes carrying a distance, a duration and
a flight number. Airport names, codes and coordinates are real, so distances come out about right and
`ORDER BY r.distanceKm DESC` answers a real question; **everything else — the airlines, every route, every
frequency — is generated.** Past 58 airports the generator invents them outright rather than inventing facts about
real places.

```cypher
MATCH (a:Airport)-[r:ROUTE_TO]->(b:Airport) WHERE a.code = 'LHR' RETURN a, r, b
MATCH (h:Hub)-[:ROUTE_TO]->() RETURN h.code AS hub, count(*) AS routes ORDER BY routes DESC
MATCH p = (a:Airport)-[:ROUTE_TO*1..3]->(b:Airport)
WHERE a.code = 'AKL' AND b.code = 'HEL' RETURN p LIMIT 5
```

| | |
|---|---|
| Left | Every label and relationship type in the store with live counts, each clickable into a query; below it the inspector — the selected node's labels and properties, or the selected relationship's, with its incident relationships as links to walk |
| Above | The Cypher editor. Ctrl+Enter runs it. **Replace** clears the canvas for each result, **Merge** adds to it |
| Right | A D3 force layout: one colour per label and per relationship type, the type written along each line, drag, zoom and fit |

The UI is a React application under `graph-server/src/main/frontend`, bundled by Vite straight into the
jar's resources. It is optional in the same way the Rust layer is: no `npm`, or `-Dui.skip=true`, and
the build skips it, the API still works, and the index page says what happened.

Two things worth knowing, both of which the tests pin down. **Colour comes from a hash of the name**,
so the legend on the left and the circles on the right always agree without sharing any state. And
**merge keeps node objects rather than rebuilding them**, so a node that appears in a second query
stays where it is instead of the whole picture jumping.

## Building

One command builds and checks everything the way a release is built — the reactor's tests with the native
layer required, the jars, the container image, the Helm chart, and a smoke test of the image:

```bash
./build.sh                 # --help for the options; --release cuts a version (spec/RELEASE.md)
```

The Maven reactor on its own builds the Java modules, the Rust native layer and the React UI:

```bash
mvn clean install
```

The jars under `target/` have version-free names — `graph-server/target/graph-server.jar` and
`graph-cli/target/graph-cli-cli.jar` are the runnable ones — so nothing outside the poms names a version.

The reactor is a parent pom over `graph-core`, `graph-cypher`, `graph-protocol`, `graph-client`,
`graph-cli` and `graph-server`, plus `graph-api-lock`, the test-scope tooling that locks the public Java API of
the four library modules ([spec/API_LOCK.md](spec/API_LOCK.md)). `graph-core` drives
`cargo` through the exec plugin at four points: **build** before compile, **package** the resulting
shared object into the jar under its platform classifier, **test** the crate with
`cargo test` and `clippy -D warnings` during `mvn test`, and **clean** the crate's target directory
during `mvn clean`. `graph-server` drives `npm` on the same terms — install, bundle into
`target/classes/webroot`, and run the UI's own tests — and then shades everything into one runnable
jar.

```bash
mvn test              # the suite across seven modules (API locks included), the UI's own 17, and the Rust crate's 12
mvn test -Pslow       # adds the scaling curve, the soak and the long-running reader
mvn test -Pbench      # adds the benchmarks and the performance gates
mvn test -Pall-tests  # everything
tools/kill9-loop.sh   # kill the process under load, reopen, check every acknowledged commit

mvn test -Phuge -Dtest=GraphScaleBenchmark   # 10M nodes + 20M edges: 11 min, 6.2 GiB
```

| Flag | |
|---|---|
| `-Dnative.skip=true` | Build with no native layer at all, even where `cargo` is present, **and do not load one that an earlier build left behind**. The whole suite passes on the pure-Java path, with the native-only tests skipped, and this is how that is checked; CI runs both. |
| `-Dui.skip=true` | Build with no browser UI, even where `npm` is present. The API is unaffected and the index page says the bundle is missing. |
| `-Poffline` | Passes cargo's and npm's offline flags, for warm caches with no network. Pairs with `mvn -o`. |
| `-DskipTests` | Skips the Rust and UI tests too, not only the Java ones. |

Requires JDK 25. **The Rust layer is optional**: the build skips it automatically when `cargo` is
absent and the engine runs on the pure-Java path — every native capability has a fallback
([spec/NATIVE_FFM.md](spec/NATIVE_FFM.md) section 5). **The UI is optional** in the same way, and
needs `npm` only to build; the jar it produces needs nothing but a JVM.

The browser suite is opt-in, because it downloads a browser and starts a server:

```bash
cd graph-server/src/main/frontend
npm run e2e        # Playwright, against the real server over the demo store
npm run dev        # hot reload against a server started separately on 8080
```

Run the query UI from the reactor rather than the shaded jar:

```bash
mvn -pl graph-server exec:java -Dexec.args="/data/mygraph 8080"
```

### A native binary

The server and the backup CLI both compile to standalone executables with no JVM:

```bash
export GRAALVM_HOME=/path/to/graalvm-25
mvn -Pgraalvm verify                               # jars, then binaries, then a smoke test against each
./graph-server/target/native/graph-server --demo /tmp/demo 8080
./graph-cli/target/native/metaloom-graph-cli database backup default --path=/data/graphs --out=g.dump
```

**The FFM layer survives ahead-of-time compilation intact** — libc downcalls, mapped segment files, and
the Rust crate `dlopen`ed from inside the image, which reports `ABI 2` and all five capabilities there
just as it does on the JVM. What it needed was a metadata file listing every downcall signature, one
build flag for shared arenas, and one substitution for netty, which builds its own FFM handles
reflectively in a way the static analysis cannot compile.

| | jar on JDK 25 | native binary |
|---|---|---|
| Launch to first response | ~490 ms | **~110 ms** |
| Resident memory | ~160 MB | **~78 MB** |
| Artifact | 8.1 MB **plus a JDK** | **38 MB, nothing else** |
| Warm query over 23,584 relationships | **42 ms** | 129 ms |

That last row is the trade and it is not a footnote: with no JIT, a scan-heavy query runs **3x slower**
in the binary. The image wins where startup and footprint decide it; the jar wins for a server that
stays up. The whole exercise, including the four things that build cleanly and fail at run time, is in
[spec/GRAALVM.md](spec/GRAALVM.md).

**For the CLI there is no such trade**, because it runs once and exits rather than staying up: `--help`
in 1–2 ms against 18–20, a real backup in 32 ms against 96, 22 MB resident against 64. Its profile is
fifteen lines and no metadata of its own — everything native-image needs travels inside `graph-core`'s
jar, which is what makes a second executable free.

Both binaries go into the container images, which is not a nicety for the native one: it has no JVM, so
without the compiled CLI that image could not be backed up from the inside at all.

```bash
docker exec g metaloom-graph-cli database backup default --server=http://localhost:8080 --out=- > g.dump
```

### Upgrading across an on-disk format version

A build reads exactly one store format version (4 today) and refuses an older store with a message naming the
command that migrates it — it never upgrades on open. With the server stopped:

```bash
metaloom-graph-cli database info    default --path=/data/graphs              # the store's version; no lock taken
metaloom-graph-cli database migrate default --path=/data/graphs --dry-run    # the plan; changes nothing
metaloom-graph-cli database migrate default --path=/data/graphs              # copy, migrate, verify(FULL), swap
```

The original is kept beside it as `default.pre-migration-v<N>`, a failure leaves it untouched, and a crash is finished
by running the command again. [spec/MIGRATION.md](spec/MIGRATION.md) is the design and the procedure every format
change follows; [spec/OPERATIONS.md §5.1](spec/OPERATIONS.md#51-upgrading-across-a-store-format-version) is the
checklist.

## Specification

[spec/OPERATIONS.md](spec/OPERATIONS.md) is the one to read before running this anywhere real: sizing, tuning, what
to monitor, and an explicit list of what is missing.

[spec/CONTEXT.md](spec/CONTEXT.md) is the entry point: it routes to every
spec file, carries the cheat sheet and the conventions, and links the phased plan in
[spec/PLAN.md](spec/PLAN.md). [spec/CURRENT_STATE.md](spec/CURRENT_STATE.md) records the proof of
concept this replaced, including its defect register.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

The query language is an independent partial implementation of Cypher, written against the language as
published by the [openCypher project](https://opencypher.org/) (itself Apache 2.0). No openCypher
source, grammar file or TCK is included here. Cypher® and Neo4j® are registered trademarks of Neo4j,
Inc.; this project is not endorsed by or affiliated with them, and its subset is not an openCypher
Implementers Group approved implementation.
