# Graph Storage FFM PoC

A **native-code backed graph storage layer** for Java, built on the Foreign Function and Memory API.

It stores nodes, relationships and attributes in compact, purpose-built binary files, optimised for
SSD. The defining design constraint:

> **An element id decodes arithmetically into the exact file and byte offset of its record.**
> Reading an element by id costs zero index probes and zero extra seeks.

## Status

Durable, transactional, indexed, compacting, hardened. 127 tests.

| | |
|---|---|
| **Durability** | Write-ahead log. Every commit is fsynced *before* it touches a data file, so uncommitted data cannot reach disk and recovery is a single redo pass. |
| **Transactions** | `begin` / `commit` / `rollback`, plus auto-commit for single operations. |
| **Isolation** | Single-writer serialisable: one writer, many concurrent readers, no reader sees half a commit. **No MVCC.** Reads take no lock and scale to 10x at 32 threads; writes are flat by design. |
| **Indexes** | Label and property B+trees, with an index-versus-scan differential test. |
| **Space reclamation** | Two halves. `reclaim()` punches all-free pages and breaks no ids. `compact()` relocates records to close holes within pages, rewrites every internal reference, and pulls the high water marks down; it is the one operation that invalidates ids. A store churned to a tenth of its slots comes back to 0.95 liveness. |
| **Native layer** | libc via FFM works. The Rust crate is written but has never been compiled here. |
| **Hardening** | Reads-per-operation and write-amplification budgets enforced as build failures; a differential harness against an in-memory model across crashes and compaction; every format parser tested against corrupt and hostile input; a `kill -9` loop that checks acknowledged commits. |
| **Not there** | No backup mechanism, no MVCC, no repair tool. [spec/OPERATIONS.md](spec/OPERATIONS.md) says so plainly. |

Full per-phase status, including the reason for every gap:
[spec/reports/PHASE_STATUS.md](spec/reports/PHASE_STATUS.md).

**It has been tried on something real.** Phase 10 built it into MetaLoom as an index over the asset relationship
graph — behind an interface, alongside Postgres, with a differential check that both return the same answers. They
do, through insertions, deletions, a rebuild and a compaction. The recommendation is nonetheless **not to adopt**:
against the obvious SQL it is 657x faster, against the same query written properly it is 2.4x faster, and only the
second number is a reason to do anything.
[spec/reports/PHASE_STATUS.md §7](spec/reports/PHASE_STATUS.md#7-phase-10--metaloom-integration) has the numbers.

Measured against the original proof of concept ([spec/reports/BASELINE.md](spec/reports/BASELINE.md)):

| | Before | Now |
|---|---|---|
| Node record | 1080 B | 64 B |
| Node with two properties | ~1144 B | 128 B |
| Directed adjacency entry | 32 B | 1.0 to 1.2 B |
| Reads to enumerate d neighbours | d | 0 for d <= 2, one per ~8000 otherwise |
| Memory mappings for N accesses | N | 1 per segment |
| Durability | none | fsync before any data page is touched |
| Space returned after mass deletion | none | hole punching, plus compaction to over 0.94 liveness |

## API

```java
try (GraphStore store = GraphStore.open(Path.of("/data/mygraph"))) {

    // One transaction for a bulk load: durability costs an fsync per commit, so batching matters.
    try (Transaction txn = store.begin()) {
        long person = store.createNode("Person", Properties.of()
            .set("name", "Wes Anderson")
            .set("born", 1969));

        long vehicle = store.createNode("Vehicle", Properties.of().set("name", "VW Beetle"));

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

    store.verify(VerifyLevel.FULL);

    // Give space back. reclaim() moves nothing and keeps every id valid; compact() closes holes
    // *inside* pages and is the only thing in the engine that invalidates an id.
    store.reclaim();
    CompactionReport report = store.compact();
}
```

## Building

```bash
mvn test              # 127 tests, about twenty seconds
mvn test -Pslow       # adds the scaling curve and the soak
mvn test -Pbench      # adds the benchmarks and the performance gates
mvn test -Pall-tests  # everything
tools/kill9-loop.sh   # kill the process under load, reopen, check every acknowledged commit
```

Requires JDK 25. The Rust native layer is optional: the build skips it when `cargo` is absent and the
engine runs on the pure-Java path.

## Specification

[spec/OPERATIONS.md](spec/OPERATIONS.md) is the one to read before running this anywhere real: sizing, tuning, what
to monitor, and an explicit list of what is missing.

[spec/GRAPH_STORAGE_CONTEXT.md](spec/GRAPH_STORAGE_CONTEXT.md) is the entry point: it routes to every
spec file, carries the cheat sheet and the conventions, and links the phased plan in
[spec/PLAN.md](spec/PLAN.md). [spec/CURRENT_STATE.md](spec/CURRENT_STATE.md) records the proof of
concept this replaced, including its defect register.
