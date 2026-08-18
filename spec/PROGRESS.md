# Progress

The single consolidated checklist for this project: **everything that is done, and everything that is
not**, in one place.

| Where to look | For |
|---|---|
| This file | What is done and what is left, as checkboxes, across every phase |
| [PLAN.md](PLAN.md) | The work breakdown itself — deliverables, exit criteria, dependencies, risks |
| [reports/PHASE_STATUS.md](reports/PHASE_STATUS.md) | *Why* each gap exists, and what each phase's tests found |
| [reports/BASELINE.md](reports/BASELINE.md) | Measured numbers and the machine they were measured on |
| [reports/SCALE.md](reports/SCALE.md) | 10M nodes / 20M relationships: load time, disk cost, and the adjacency defect it exposed |
| [GRAPH_BENCHMARK.md](GRAPH_BENCHMARK.md) | Every scale run in one progression table, the traversal and supernode numbers, and the SIMD investigation |
| [CYPHER.md](CYPHER.md) | The openCypher subset, its null semantics, the two engine mismatches, and the server and UI |
| [CURRENT_STATE.md](CURRENT_STATE.md) | The proof of concept this replaced, and its defect register |

**Legend**: `[x]` done and tested · `[~]` partially done, with the missing part named · `[ ]` not started.

---

## 1. Where Things Stand

| Phase | State | Evidence |
|-------|-------|----------|
| 0 — Baseline and harness | **Done** except CI, which has no runner | [reports/BASELINE.md](reports/BASELINE.md) |
| 1 — Addressing and segments | **Done** | `ElementIdTest`, `GeometryTest`, `SegmentLifecycleTest` |
| 2 — Record stores and catalog | **Done** except `MAP`/`DURATION` value types | `ValueCodecTest`, `PropertyRoundTripTest` |
| 3 — Adjacency and traversal | **Done** except a streaming `traverse`; neighbour enumeration streams (IDX-4) | `AdjacencyTierTest`, `EdgeCodecTest`, `SpaceEfficiencyTest`, `NeighbourCursorTest` |
| 4 — WAL, recovery, checkpoints | **Done**, as no-steal redo rather than ARIES | `RecoveryTest` |
| 5 — Transactions | **Done** — ACID, **concurrent writers**, snapshot isolation for readers and writers, `SERIALIZABLE`, commit-time conflict detection | `VisibilityTest`, `SnapshotIsolationTest`, `VacuumTest`, `ConcurrentReaderTest`, `RollbackTest`, `ParallelConsistencyTest`, `HammerTest` |
| 6 — Native layer | **Done** except a second build target; `miri` and `fuzz` now installed and run | `NativeCodecTest`, `UringIoEngineTest`, `DirectWalTest`, `BlobCompressionTest`, `StatxTest`, `NativeBindingsTest` |
| 7 — Indexes | **Done** for the label, property, composite and unique B+trees | `IndexTest`, `CompositeIndexTest`, `UniqueConstraintTest` |
| 8 — Compaction and reclamation | **Done** except the p99 latency budget | `CompactionTest`, `CompactionCrashTest` |
| 9 — Hardening | **Partial** — see §4.9; crash matrix **built**, no JMH, long runs not run | `CrashMatrixTest`, `WalFaultTest`; §6 of PHASE_STATUS |
| 10 — MetaLoom integration | **Done** — built, measured, recommended against adopting | §6 below |
| 11 — Query language | **Done** for an openCypher subset in its own module, reads and writes, with a single-jar Vert.x server and a React and D3 graph browser. `FOREACH` and `OPTIONAL MATCH` refused by name | [CYPHER.md](CYPHER.md); `CypherVersusApiTest`, `CypherWriteVersusApiTest`, `NullSemanticsTest`, `e2e/graph-browser.spec.ts` |
| 12 — GraalVM native image | **Done** — the server builds to a 38 MB standalone binary, FFM and all, behind `-Pgraalvm` | [GRAALVM.md](GRAALVM.md); `ForeignDowncallMetadataTest`, `tools/native-image.sh` |
| 13 — Binary streaming port | **Done** for stage 1: a framed TCP protocol and a Java client that stream rows as the engine produces them. The *executor* still materialises — see below | [WIRE.md](WIRE.md), [TRANSPORT_DECISION.md](TRANSPORT_DECISION.md); `WireVersusEmbeddedTest`, `WireServerTest`, `WireDecoderHostileInputTest` |
| 14 — Several databases | **Done** — one directory per database under a root, selectable from the client, the HTTP routes and the browser UI | [DATABASES.md](DATABASES.md); `GraphDatabasesTest`, `DatabaseRoutesTest` |
| 18 — Operability | **Done** — environment-variable configuration, Prometheus metrics on their own port with `/health` and `/ready`, and a slow query log on every front end | [METRICS.md](METRICS.md), [OPERATIONS.md §3.1, §4](OPERATIONS.md); `MetricsCatalogScrapeTest`, `MetricsServerTest`, `SlowQueryLogTest`, `GraphServerOptionsTest` |
| 19 — MCP | **Done** — eight tools over HTTP+SSE and WebSocket, off by default and loopback when on, write-gated at two points | [MCP.md](MCP.md); `McpProtocolTest`, `McpServerTest`, `CypherGuideToolTest` |
| 20 — Deployment | **Done** — JVM and GraalVM container images, a Helm chart, and a k3d harness that runs a real pipeline against them | [DEPLOYMENT.md](DEPLOYMENT.md); `helm/test/run.sh` — 35 assertions across nine phases |

**The engine is usable today** for an embedded workload with concurrent writers: durable, crash-safe,
transactional, indexed, compacting, with snapshot isolation for readers, a Cypher query
language that reads **and writes**, a browser UI, a streaming binary port and several databases under one root — and it
holds its answers against a reference model, against real SQL, against its own Java API, and now over its own wire.

**It is also deployable and observable**: it configures itself from the environment, exports Prometheus metrics with
`/health` and `/ready` on a port of their own, writes a slow query log that never logs a parameter value, ships as a
container image in two flavours — each carrying the backup CLI as well as the server — installs from a Helm chart that
refuses a second replica by name, and speaks the Model Context Protocol so an agent can query it.

It is **not** usable where concurrent writers, `OPTIONAL MATCH` or an **authenticated** network are requirements. None
of those exists and none is faked — and the last one is worth repeating, because everything in the paragraph above
makes it easier to expose.

---

## 2. Evidence

The repository is six modules — `graph-core`, `graph-cypher`, `graph-protocol`, `graph-client`, `graph-cli`,
`graph-server` — under one parent pom, and every profile below applies across all of them. `mvn clean install` builds the lot, Rust and
the browser UI included: `graph-core` drives cargo at four points, **build** (generate-resources),
**package** the shared object into the jar (process-classes), **test** the crate with clippy (test),
and **clean** its target directory (clean); `graph-server` drives npm on the same terms and then
shades a single runnable jar.

```bash
mvn clean install             # everything, including the native layer
mvn test -Dnative.skip=true   # the pure-Java fallback: the same tests, 23 skipped rather than 3
mvn -o -Poffline install      # warm cargo registry, no network
mvn test                      # 795 tests, 0 failures, 3 skipped — plus the UI's 17 and 12 Rust tests
mvn test -Pbench              # benchmarks, the structural gates, and the supernode gates
mvn test -Pcrash              # kill -9, compaction crash injection, and the crash matrix
mvn test -Pslow               # the soak harness and the long-running reader
mvn test -Pall-tests          # everything
mvn test -Phuge -Dtest=GraphScaleBenchmark       # 10M nodes / 20M edges; 11 to 25 minutes, 6.1 GiB
mvn test -Phuge -Dtest=GraphTraversalBenchmark   # random access, 30k-relationship walk, 30k-hop chain
mvn test -Pcrash -Dtest=CrashMatrixTest -Dgraph.crash.transactions=24   # 2,394 fault cases, 240 s
tools/kill9-loop.sh 100       # the crash loop, one iteration per store
tools/verify-native.sh        # cargo miri, then cargo fuzz on both targets

java -jar graph-server/target/graph-server-0.0.1-SNAPSHOT.jar /path/to/store 8080   # the graph browser
java -jar graph-server/target/graph-server-0.0.1-SNAPSHOT.jar --demo /tmp/demo 8080 # with a graph in it
tools/demo-server.sh [dir] [port] [size]           # the same, from the reactor
cd graph-server/src/main/frontend && npm run e2e   # 29 Playwright cases against that server
```

In MetaLoom: `mvn test -pl loom/services/graphstore` — **23 tests**, and
`-Dgroups=graphbench -Dgraphstore.excluded.groups=` for the benchmark.

Neither number is the point. The numbers that decide anything are the budgets and gates in
[TESTING.md §7](TESTING.md#7-benchmarks), scored in §4.9 below.

---

## 3. Completed

### Phase 0 — Baseline, harness, build hygiene

- [x] Java 25 (`<release>25</release>`); FFM compiles without preview flags
- [x] Test suite split per [TESTING.md §2](TESTING.md#2-layout); JUnit tags and the `slow`, `crash`,
      `bench`, `all-tests` profiles
- [x] Bulk loops moved out of the unit suite into `bench/BulkBenchmark`
- [x] [reports/BASELINE.md](reports/BASELINE.md) with measured numbers and the machine

### Phase 1 — Addressing and segment substrate

- [x] `ElementId` arithmetic over a `long`, `Kind`, `Geometry` with strides, capacities and `dataStart`
- [x] `StaleIdException` and the generation check
- [x] `SegmentFile`: sparse create, open, superblock validation, mapped once from a store-scoped arena
- [x] `Superblock` A/B flip with CRC32C and `sb_sequence`
- [x] `FreeSlotBitmap` with the 64-slot word scan and page-aware run search
- [x] `PageMetadataArray`: epoch, checksum, live count, free hint
- [x] `SegmentSet`: bump cursor, FIFO free ring, segregated run lists, rollover
- [x] `Manifest` with A/B flip and geometry validation
- [x] `GraphStore.open/close` with the `LOCK` file
- [x] Every PoC class deleted: `GraphUUID`, `FileHeader`, `OffsetProvider`, `AbstractMMapFileStorage`,
      `AbstractElementStorage`, `LongBinaryTree`

### Phase 2 — Record stores and catalog

- [x] `NodeStore` and `RelStore`: 64-byte records with fixed-offset accessors over a slice
- [x] Create, read with generation check, update, free with cascade
- [x] `Catalog` with the label and key dictionaries; the read path is lock-free (§4.9)
- [x] `PropertyStore` block encoder and decoder, entries sorted by key id
- [x] Cell run allocation with segregated free lists and page-straddle avoidance
- [x] `BlobStore` with extent chaining and a whole-blob CRC
- [x] Cascade: freeing an element frees its property block and every blob it references
- [x] Typed `Properties` with absent distinguished from null

### Phase 3 — Adjacency and traversal

- [x] `PackedEdge`, with the tombstone unreachable through the constructors
- [x] All three tiers: inline (degree ≤ 2, zero extra I/O) → single block → chain, promotion preserving
      the edge set exactly
- [x] `AdjacencyBlock`: compressed sorted region plus raw tail, with in-place recompaction
- [x] Bidirectional maintenance at both endpoints, always
- [x] `EdgeCodec`, delta plus varint
- [x] Tombstone deletion with block rewrite
- [x] `min_location` / `max_location` and the type bitmap, used to skip blocks on delete
- [x] Degree counters, asserted against actual adjacency
- [x] Breadth-first `traverse` with depth, direction, type filter and node uniqueness
- [x] Lightweight edges and materialised relationships in one chain
- [x] A materialised entry caches its relationship's type, so a type-filtered traversal reads a
      relationship record once per surviving candidate rather than once per entry
      ([RELATIONSHIP_STORAGE.md §3.1](RELATIONSHIP_STORAGE.md),
      [IDX-2](INDEX_TASKS.md#3-closed)).
      Compatible with format version 2: type id 0 means "not cached", which is what an older store's
      entries already hold
- [x] `AdjacencyStore.defragment` (built in Phase 8)
- [x] **Cell-based adjacency blocks**: a block is a run of 512 byte cells that doubles as the node
      fills, so degree 3 costs 512 bytes rather than a page. Format version 2

### Phase 4 — WAL, recovery, checkpoints

Built as **no-steal, force-at-commit redo logging** rather than the ARIES steal/no-force design in
[WAL.md](WAL.md). The trade and the reason are in
[reports/PHASE_STATUS.md §1](reports/PHASE_STATUS.md#1-phase-4--what-was-built); the short version is
that a steal policy cannot be made correct over a `MAP_SHARED` mapping.

- [x] `WalRecord`: 40-byte header, CRC32C, self-identifying LSN
- [x] `WalWriter`: preallocated segments, LSN allocation under a short append lock, buffered block flush
- [x] WAL segment rotation, retention and deletion (`trim`)
- [x] `durable_lsn` watermark
- [x] `wal.sync.mode` implementations (`WalSyncMode`), including group commit on an interval
- [x] Redo handler for every record type written
- [x] Recovery driver — **one pass, redo only**; uncommitted data structurally cannot reach the data
      files, so there is nothing to undo
- [x] Checkpoint and manifest advance
- [x] Corruption detection and `PageCorruptException`
- [x] `PageOverlay` / `OverlayHolder`: private per-transaction page copies, applied after the fsync
- [x] Tests: the `RecoveryTest` matrix

### Phase 7 — Indexes

- [x] `BTree` over `INDEX`-kind pages
- [x] `IndexCatalog` with roots in the manifest
- [x] Label index: `label_id -> node ids`
- [x] Property index: `(key_id, value) -> element ids`
- [x] Index maintenance inside the transaction — an index update is part of the atomic unit
- [x] Index rebuild, gated on entries per page rather than on slot liveness (Phase 8)
- [x] Property values are keyed by a **content** hash, arrays and lists included — a `float[]` used to
      be keyed by its identity hash ([INDEX_TASKS.md IDX-1](INDEX_TASKS.md#3-closed))
- [x] Composite index: declared `(label, key)` pairs, keyed on `mix64(labelId, keyId, valueHash)`,
      backfilled at declaration, swept at `dropIndex`, and maintained by the same four hooks — which is
      also what makes a **label-scoped** unique constraint expressible
      ([INDEX.md §3.3](INDEX.md), [INDEX_TASKS.md IDX-7](INDEX_TASKS.md#3-closed))
- [x] Tests: `IndexTest`, `IndexKeyTest` and `CompositeIndexTest`, the planner's choice in
      `CompositeIndexPlanTest`, the HTTP surface in `IndexRoutesTest`, plus the index-vs-scan
      differential inside `ReferenceModelTest`
- [x] Declaring, listing and dropping a composite index over HTTP and from the browser UI — the query
      language has no `CREATE INDEX`, so this is the only surface for it that is not Java
      ([CYPHER.md §8](CYPHER.md))

### Phase 8 — Compaction and space reclamation

- [x] All-free page detection and `punch_hole` reclamation — no ids break; prefer it
- [x] `Compactor`: liveness scan, destination allocation, record-by-record relocation
- [x] Forwarding map held **in memory for one batch**, not on disk
- [x] Reference rewrite sweep across every reference class: adjacency entries and block owners, node
      inline edges, relationship endpoints, property back-pointers, ids embedded in property payloads,
      both indexes
- [x] High water mark shrink and free counter recomputation — where the space actually reappears
- [x] Segment retirement and unlink, preceded by a checkpoint
- [x] Adjacency block defragmentation
- [x] Throttling: the writer lock is taken per batch, not per run, with a configurable pause
- [x] Tests: `CompactionTest` (11 cases), `CompactionCrashTest` (failure injected at every batch, both
      between batches and inside one), `benchCompaction` — liveness 0.105 → 0.947

### Phase 9 — Hardening (the completed part)

- [x] Reads-per-operation budgets enforced as build failures (`ReadBudgetTest`)
- [x] Write amplification measured and gated — **1.70x** against a structural floor of 2
- [x] Concurrency scaling curve to 32 threads with the bottleneck named at each knee (`ScalingTest`)
- [x] Reference-model differential harness (`GraphModel`, `ReferenceModelTest`)
- [x] Hostile-input suite against every format parser (`HostileInputTest`), each case under a hard
      timeout because "it hangs" is the failure being tested for
- [x] Soak harness asserting leak, growth and fragmentation bounds (`SoakTest`) — built, and currently
      **failing** its growth bound; see the open defect in the Phase 14 list below
- [x] `kill -9` harness checking acknowledged commits against a separately fsynced ack file
- [x] Optimistic `StampedLock` read path; lock-free `Dictionary`; striped `LongAdder` counters
- [x] Observability: `StoreMetrics`, `IoStats`, `IoCounters`
- [x] `store.verify(FULL)` usable online, throttled via `VerifyOptions`
- [x] [OPERATIONS.md](OPERATIONS.md): sizing, tuning, monitoring, backup, disaster recovery, what every
      exception means, and an explicit list of what is missing
- [x] Security review of the format parsers
- [x] API stability review ([reports/API_REVIEW.md](reports/API_REVIEW.md)) — 4 defects fixed
- [x] `UsageExampleTest`: the documented example, executable and asserting, so the README cannot drift
      from the API. It found a defect on its first run — an index lookup after a rolled-back *first*
      transaction threw `StaleIdException` on a healthy store, because the catalog page id was cached
      in memory while the allocation behind it was discarded
- [x] CI workflow written (`.github/workflows/ci.yml`), including the cargo job

### Phase 12 — GraalVM native image

- [x] **The server compiles to a standalone binary**, `mvn -Pgraalvm package`, with a smoke test that
      seeds a store, runs a query, and fetches the UI and its hashed asset from the binary itself
- [x] **The FFM layer needed no code changes.** libc downcalls, `Arena.ofShared` mappings and the
      Rust crate `dlopen`ed at run time all work inside an image; the crate reports `ABI 2` and all
      five capabilities from in there. What it needed was metadata, one build flag, and one
      substitution for netty ([GRAALVM.md](GRAALVM.md))
- [x] **`ForeignDowncallMetadataTest`** — the registrations checked against the source in both
      directions, in the ordinary `mvn test`, so the file cannot silently fall behind a new binding
- [x] **The fallback path holds under AOT**: an image built from a jar with no crate in it starts,
      serves and queries on the pure-Java path
- [x] `/api/stats` now reports `nativeLayer`, which is the only way to ask a running binary which of
      the two paths it took
- [ ] **PGO and `-march=native` untried.** A warm scan-heavy query is **3x slower** in the binary than
      on the JVM, which is the honest cost of no JIT and the reason the jar is still the artifact for
      a long-lived server. Neither remedy has been measured, so neither is claimed
- [ ] Linux x86-64 only; the binary is not in CI, and the Playwright suite still runs against the jar

### Phase 13 — The binary streaming port

- [x] **`Cypher.stream`**, and `Cypher.query` reimplemented as a sink that collects it, so there is one
      execution path and one place the read view is managed
- [x] **`QueryAbort`**, consulted inside `GraphAccess` rather than only between rows — which is what
      makes a deadline reach a scan that has not produced a row yet. It also made
      `QUERY_TIMEOUT_MILLIS` implementable; it had been declared, read nowhere, and *unimplementable*,
      because `executeBlocking` cannot interrupt a running task
- [x] **The transport chosen by measurement**, with the criteria committed before the numbers
      ([TRANSPORT_DECISION.md](TRANSPORT_DECISION.md)). The throwaway trial module is gone, as designed
- [x] **`graph-protocol` and `graph-client` depend on neither engine**, asserted by loading rather than
      by reading a pom
- [x] **Four bounds on a slow reader**, and the memory multiplication enforced in `WireServerOptions`
      rather than documented ([WIRE.md §4, §5](WIRE.md))
- [ ] **The executor still materialises.** Stage 1 bounds the encoded payload, the client's memory,
      time-to-first-row and cancellation. It does **not** bound what the query engine holds: a
      `MATCH (n) RETURN n` over a 10M-node store still peaks at 10M rows of server heap. The lazy
      clause prefix — `forEachNode(LongPredicate)`, a push pipeline, and `LIMIT` stopping the scan — is
      the next piece and is not built
- [x] **Authentication**, with per-database RBAC — [AUTH.md](AUTH.md)
- [~] **Transport security on the binary port only** ([WIRE.md §7.1](WIRE.md)), off by default. The HTTP and MCP
      ports have none and expect a proxy ([OPERATIONS.md §9.1](OPERATIONS.md)). The cleartext binary listener still
      binds loopback for that reason
- [ ] The trial harness was never committed, so its numbers are recorded but not reproducible from this
      repository

### Phase 8.1 — Deletion and reuse, corrected

- [x] **Hole punching no longer resurrects deleted ids.** The generation counter of a slot lives inside
      its record, so punching an all-free page zeroed every counter on it and the allocator handed the
      *same* id back out; a caller holding the deleted id then read the new record with no error.
      `gen_floor` in the page metadata array — a region that is never punched — carries the history
      across. [ID_ENCODING.md §4](ID_ENCODING.md), [FILE_FORMAT.md §3.3](FILE_FORMAT.md),
      `NativeReclaimTest`
- [x] **`Compactor` called `set.reclaim()` outside `runWrite()`**, and two things followed from it.
      `force()` there calls `flushChecksums()`, which clears the segment's dirty-page set and so
      discarded a concurrent foreground writer's marks, leaving its pages with stale checksums — an
      intermittent `CompactionTest` failure, reproduced. And a page could be punched while a writer was
      allocating into it. **Fixed**: `set.reclaim()` and `set.retireEmptySegments()` now run inside
      `runner.runWrite(...)`, like `shrinkHighWater` beside them.
      <br>The same defect existed on the **public `verify()` path**, and was worse there because
      verification is what an operator reaches for when they already suspect corruption. It held only
      the apply read latch, which does not exclude an *open write transaction* — so it compared a
      free-slot bitmap still sitting in the overlay against a superblock live count already advanced,
      and reported `accounting mismatch` on a perfectly healthy store, within a second, every time.
      `verify()` takes the writer lock now, and its throttling pacer releases both locks between
      batches so a writer can still get in. `VerifyUnderLoadTest` covers both
- [ ] **The free ring does not survive a restart.** It is in-memory, is never rebuilt from the bitmap,
      and `allocate()` only scans the bitmap once the active segment's bump cursor is exhausted — and
      only ever looks at the newest segment. So holes below the high water mark, and every hole in a
      sealed segment, are unreachable after a reopen until `compact()` runs. Measured: delete slot 3,
      reopen, next insert gets slot 10
- [x] **Fixed: the index high water mark ratcheted up on every compaction, and `SoakTest` was right about
      it.** `testSteadyStateDoesNotGrowWithoutBound` failed at the default twenty seconds with committed
      bytes per live element climbing from 346 B at the settled sample to 566–610 B at the end.
      <br>**Where it was.** Broken down per record kind, every kind was flat except `INDEX`, which went
      from 450 KB to 1.65 MB while its live page count sat at 57 — a high water mark of 252 over 57 live
      pages. Compaction rebuilds the B+trees wholesale: `resetTrees()` frees every page and the caller
      refills from the live records. The free ring is FIFO, so the refill scattered itself across the
      freed range in tree-walk order; a single live page landing near the top was enough to stop
      `shrinkHighWater()` trimming anything; and the mark rose by one or two slots on every rebuild,
      for the life of the store. Instrumenting the allocator showed it exactly: zero ring rejections,
      the ring drained to empty every time, and the shortfall taken from the bump cursor.
      <br>**Why it was worse than a metric bug.** With hole punching available the stranded pages are
      all-free, `reclaim()` returns their blocks, and real disk usage stays flat at ~1.7 MB while
      `storageBytes()` climbs to 3.3 MB — so on this host the damage looked cosmetic. But
      `reclaimEmptyPages()` returns immediately when the native library is absent, **and CI runs the whole
      suite with `-Dmetaloom.graph.native.enabled=false`**. Measured in that configuration the ratchet is
      a genuine unbounded disk leak: the index segment grew 762 KB → 2.2 MB and was still climbing.
      <br>**The fix** is `SegmentSet.packFreeRing()`, called by `resetTrees()` once the trees have been
      freed: it sorts the free ring so the refill packs against the bottom of the segment and the tail
      becomes trimmable. FIFO reuse stays the rule everywhere else, because rotating through the segment
      is what keeps the 12 bit generation a useful dangling-id detector — that argument simply does not
      apply to a kind whose ids never leave the engine, which is the same property that makes
      `resetTrees()` affordable at all.
      <br>**Measured after**: mark tracks live exactly (57/57, 59/59), bytes per element flat at 318 → 312
      instead of 346 → 606, real disk flat in both native configurations, and `SoakTest` green.
      `IndexSpaceReuseTest` pins it, and fails at `mark=53 live=27` with the one-line fix removed

### Phase 15 — Cypher writes

- [x] `CREATE`, `SET`, `REMOVE`, `DELETE` and `DETACH DELETE`, with `RETURN` optional for a write and
      required for a read. `FOREACH` refused at parse time by name with a caret (`MERGE`, `CALL` and
      `LOAD CSV` were too, until they were built)
- [x] `Ast.Query.isWrite()`, so both servers can refuse a write on the event loop and `Cypher.stream`
      can choose a transaction over a read view without running anything first
- [x] One transaction per write query, joined when the caller holds one, **committed before the first
      row is delivered**. A write inside an open read view is refused
- [x] `GraphStore.hasWriteTransaction`, `addNodeLabel`, `removeNodeLabel`, and
      `IndexCatalog.onLabelsChanged` — the engine could only set a label set at creation
- [x] `CypherStats` on the result, `stats` in the HTTP response, and nine counters in the wire's
      `COMPLETE` frame. Protocol version 3
- [x] `--allow-write`, off by default, governing both ports; `--allow-drop` re-justified and kept
      separate
- [x] `SchemaScanner.invalidate()`, called after a committed write, and the UI refetching the sidebar
- [x] `WireError.READ_ONLY` and `WireError.CONSTRAINT_VIOLATION`; a write permit per store so the wait
      for the engine's writer lock has a deadline and a name
- [x] **`MERGE`** — built in Phase 17, and the prediction recorded here held: it is *easier* here than
      elsewhere, because writers serialise on one lock and a write transaction reads its own writes
      through the same overlay, so an index probe inside `MERGE` sees rows the same statement created
      and the lock-and-retry dance other engines need does not arise

**The constraint on the streaming executor** (Phase 16, now built): a push pipeline must never carry a
write clause. The write clauses get openCypher's eager semantics for free from the executor materialising
each clause — `MATCH (n) CREATE (:Copy)` structurally cannot feed the scan driving it — and streaming one
would take that away silently. `QueryExecutor.streams` returns false for all four, and
`LazyExecutionTest` asserts it.

### Phase 17 — Licensing, and an external corpus

- [x] `LICENSE` (verbatim Apache 2.0) and `NOTICE` at the root, and a `<licenses>` block in the reactor
      pom. The project was already Apache 2.0 by inheritance from `io.metaloom:maven-parent`, but
      nothing in the checkout said so — a licence discoverable only by resolving a parent from a
      private repository is not discoverable
- [x] The shaded jar carries both: `ApacheNoticeResourceTransformer` merges every dependency's NOTICE
      (section 4(d)), and the licence text is copied into `META-INF` because
      `ApacheLicenseResourceTransformer` strips every LICENSE it finds
- [x] The openCypher position recorded in `NOTICE` and the README: no openCypher source, grammar file
      or TCK is included — the lexer, parser and executor are an independent implementation — and
      Cypher® is a registered trademark of Neo4j, Inc. openCypher itself is Apache 2.0, so there is no
      licence-compatibility question; the live constraint is naming, not copyright
- [x] `CypherCorpusTest` over `test-data/`: four openCypher dumps and 29 queries whose counts were
      derived elsewhere and proven against Neo4j — [CYPHER.md §9.2](CYPHER.md). **All four dumps import
      and all 29 queries agree.** The corpus is what identified `shortestPath`, `CASE` and `MERGE` as
      the three features this subset was furthest from openCypher on, rather than a guess about what to
      build next — and all three are now built
- [x] `shortestPath` and `allShortestPaths` **built**, breadth-first — [CYPHER.md §7.2](CYPHER.md).
      They arrived by way of a spec claim that was false: §5.5 had always listed them as refused by
      name, the corpus found the parser in fact said "expected a node pattern but found an identifier",
      and naming them properly then made the one thing they were blocking visible as a refusal.
      `PatternPart.selector` carries the choice, so the search is how the part is *matched* rather than
      a filter over what it produced. `ShortestPathTest` checks it against the depth-first enumeration
      over every pair in a diamond graph
- [x] **`CASE`**, both forms — [CYPHER.md §5.4](CYPHER.md). The AST node had existed unconstructed since
      the parser was written. Two rules carry the weight and neither throws when broken: only the branch
      taken is evaluated, and null follows §2 — in the simple form a null subject matches no `WHEN`,
      including `WHEN null`
- [x] **`MERGE`**, with `ON CREATE SET` / `ON MATCH SET` — [CYPHER.md §5.3](CYPHER.md). It reuses the
      pattern matcher for the match and `CREATE`'s builder for the create, so its only new semantics are
      which branch runs. It was predicted to be *easier* here than elsewhere and that held: writers
      serialise on one lock and a write transaction reads its own writes, so a MERGE sees rows the same
      statement created and the lock-and-retry dance other engines need never arises.
      `MergeTest` pins the whole-pattern rule; the corpus runs the MERGE-only dump **twice** and asserts
      not a count moves, which is the sharper test — a MERGE creating duplicates imports perfectly once
- [x] The corpus bar is no longer a count at all: every query must agree, every dataset in the catalogue
      must be covered, and each import must pass `verify(FULL)`. Two rounds of raising a threshold were
      two rounds of leaving slack a regression could hide in

### Phase 16 — The streaming executor, and shutting down

- [x] `GraphStore.forEachNodeWhile(LongPredicate)` — a scan that can be told it is finished. A separate
      name rather than an overload, because a lambda whose body returns `boolean` matches
      `LongConsumer` and `LongPredicate` equally and every existing caller would have become ambiguous
- [x] `RowConsumer`, and a push chain from the seed to the sink. `PatternMatcher` seeds one node at a
      time; everything after the seed is the list-based expansion unchanged, per seed row
- [x] Clause classification: `MATCH`, `WHERE`, `UNWIND` and a projection with no star, `DISTINCT`,
      `ORDER BY` or aggregate. All or nothing per query part — see [CYPHER.md §7.1](CYPHER.md)
- [x] `SKIP`/`LIMIT` as per-stage counters, with the limit's false reaching the bitmap walk
- [x] Graceful shutdown: refuse, drain, abort, close — [OPERATIONS.md §8.9](OPERATIONS.md).
      `QueryAbort.or` composes the shutdown reason with the query's own deadline, so a query with no
      timeout cannot hold the process open
- [x] `GraphStore.close()` takes the writer lock, so it cannot run beside a half-applied commit
- [x] Tests: `LazyExecutionTest`, `ShutdownTest`, and the two paths compared cell by cell
- [ ] **`UNION ALL` could stream and does not.** Each part would push into the same sink in turn. Not
      done because the parts must agree on their columns, which is checked by comparing the two
      materialised results
- [ ] **The longest streaming *prefix*** ahead of a blocking clause. Deliberately not built: a blocking
      clause buffers its whole input anyway, so the prefix would bound nothing

### Phase 14 — Several databases

- [x] **`GraphDatabases`**: a root holding one directory per database, each a complete independent
      store. The engine needed no change — the `LOCK` file was already per store
- [x] **Names are checked against an allow-list**, not a deny-list, because one arrives from an
      unauthenticated port and becomes a path component. `GraphDatabasesTest` fires traversal,
      absolute paths, NUL, percent-encoding and Unicode at it and then asserts nothing was created
      anywhere
- [x] **Stores open lazily and stay open**: a root of fifty databases with one in use costs one open
      store. `GET /api/databases` reports counts only for the ones already open, for the same reason
- [x] **Nothing is created implicitly.** A typo that quietly makes a database is a typo that fills a
      disk
- [x] **Dropping is opt-in** (`--allow-drop`, 403 otherwise) and refuses the last database. It is the
      only route in the server that destroys data
- [x] **Protocol version 4**: the database is chosen per connection at `HELLO` and echoed in
      `WELCOME`. A read view belongs to one store, so a connection cannot change store mid-flight
- [x] A picker in the browser UI, which **clears the canvas on switch** — an element id is only unique
      within a store, so merging two databases' graphs would draw one circle for two records
- [ ] **No cross-database query**, and none planned: the engine has no notion of a second store
- [x] **Per-database authorization**, which is what a grant is. Previously: there was no authentication at all, so anything that could
      reach the port can reach every database under the root

---

## 4. Pending

Ordered by phase. Each item says what it costs to leave undone.

### 4.0 Phase 0

- [ ] **Run CI.** The workflow exists; this repository has no runner attached, so nothing has ever
      executed it.
- [ ] `CountingIoEngine` for device-level read counting — needs the `IoEngine` abstraction from Phase 6.
      Today the budgets count *record* reads, which is the right granularity for the addressing
      invariant but says nothing about the device.

### 4.1 Phase 1

- [ ] `IoEngine` interface and `MmapIoEngine` — deferred to Phase 6, where alternative engines make the
      abstraction worth having. `SegmentFile` maps directly today.
- [ ] `PageCache` with latches and eviction — deferred to Phase 5. The OS page cache serves the mapping.

### 4.2 Phase 2

- [ ] `MAP` and `DURATION` value types. Every other type in
      [PROPERTY_STORAGE.md §4](PROPERTY_STORAGE.md#4-value-encoding) round-trips.
- [ ] `NoOrphanBlocks` and `DictionaryIntegrity` as standalone invariant checkers. Orphan detection
      exists inside `InvariantChecker`; the dictionary is unchecked.

### 4.3 Phase 3

- [ ] **A middle adjacency tier.** Measured at scale ([reports/SCALE.md](reports/SCALE.md)): a node
      with degree 3 costs a whole 8 KiB block, so a random 10M-node graph of mean degree 4 spends
      **59 of its 61 GiB on adjacency** — 1,560 bytes per edge, against the 1.0 to 1.2 bytes the
      format achieves once blocks are full. A small block (256 to 512 B) for degrees 3 to a few
      hundred closes it. **The highest-value change available**, and a format change, so it needs a
      version bump and a converter.

- [x] Streaming `EdgeCursor` / `NeighbourCursor` — IDX-4. One block is decoded only when the previous
      one is exhausted, so taking one edge off a node of degree 10^6 costs the head block and nothing
      else, and `edges` / `neighbours` are drains of the cursor rather than a second walk
      ([RELATIONSHIP_STORAGE.md §5.4](RELATIONSHIP_STORAGE.md)), gated by `ReadBudgetTest`. The cursor
      holds the read latch outright, because unlike a list it cannot be re-run by the optimistic read
      path.
- [ ] Prefetch on that cursor, and a `TraversalCursor` for the breadth-first form, which still
      materialises. Needs Phase 6 for the prefetch to mean anything.
- [x] Type-bitmap and min/max skipping on the **read** path — `AdjacencyStore.edges(nodeId, typeIds)`
      and `hasEdge(a, b)`, gated by `ReadBudgetTest`
      ([RELATIONSHIP_STORAGE.md §5.3](RELATIONSHIP_STORAGE.md)). It skips the *decode* of a block, not
      its read: the chain pointer lives inside each block, so the walk acquires every one regardless.
- [ ] `Bidirectionality` and `DegreeCounters` as standalone checkers — both are asserted inside
      `InvariantChecker`, but not separately runnable.

### 4.4 Phase 4

Everything below follows from the no-steal design and is **not** missing work in the sense of being
owed:

- [ ] Undo handlers and CLRs — nothing to undo under no-steal
- [ ] Three-pass recovery — one redo pass is sufficient and is what is built
- [ ] `WriteBackScheduler` — the overlay applies at commit, after the fsync, so there is no separate
      write-back to schedule

Closed since this section was written:

- [x] `FaultInjectingIoEngine` and the crash matrix — see §4.9.
- [x] Recovery against data files older than the log now raises `PageCorruptException` rather than a
      raw `IllegalStateException`. **Found by the crash matrix**: a caller could not tell "this store
      is damaged" from "this code has a bug", and a restored backup or a partial copy produces exactly
      that state.

Genuinely missing:

- [ ] `awaitDurable(lsn)` on the public API. `durable_lsn` is tracked but a caller cannot wait on it.
- [ ] Page checksums verified on read. The `PageMetadataArray` field exists and is written; nothing
      verifies it on the read path, so a bit flip in a data page is caught by `verify(FULL)` and not
      before.
- [ ] Read-only salvage mode for a store that will not open. **The crash matrix argues this is not
      urgent**: across 2,394 damaged logs, not one store refused to open (§4.9). The mode would be for
      damage to the *data* files, which nothing here produces.

### 4.5 Phase 5 — MVCC

**Half built.** Snapshot isolation for readers is done and tested; concurrent writers are not. Several
items below turned out to have nothing to do rather than being unfinished, and the difference is
recorded rather than left as an open checkbox that never closes.

What exists now, beyond the atomicity, durability and rollback that were already there:

- [x] `TransactionManager`: LSN timestamps, the active view set, the reclamation horizon, the budget
- [x] `store.beginRead()` / `beginRead(IsolationLevel)`, thread-confined, `AutoCloseable`. Every read
      method joins the calling thread's view exactly as every write method joins its transaction, so
      the API does not double
- [x] **Visibility per page**, at one choke point — `SegmentFile.readSlice`. This is the deviation
      from [TRANSACTIONS.md §5](TRANSACTIONS.md#5-version-storage) and the reason for it is that a
      reader touches far more than records: adjacency blocks, index pages, and on every single read
      the free-slot bitmap and the page-metadata array, none of which has an `xmin`. `VisibilityTest`
      pins that down with a view that must still resolve a node whose slot has since been freed *and
      handed out again*
- [x] `PageVersions` in place of an on-disk `UndoStore`. Undo does not need to be durable under
      no-steal — recovery is redo-only and a crash leaves no live snapshots — so it costs no log
      record, no segment kind and no format version bump
- [x] `READ_COMMITTED` and `SNAPSHOT`, with the anomaly matrix asserted **in both directions**:
      read-committed is shown exhibiting the non-repeatable reads and phantoms it permits, so a level
      that silently behaved like a stricter one could not pass
- [x] Reclamation after every commit; `SnapshotTooOldException` and the retention budget
- [x] `store.inTransaction(work, attempts)` with retry
- [x] Tests: `VisibilityTest` (13), `SnapshotIsolationTest` (7), `VacuumTest` (8),
      `ConcurrentReaderTest` (4), `RollbackTest` (7), `RollbackEqualsRecoveryTest` (2),
      `LongRunningReaderTest` (2, `slow`)

Two results worth stating:

- **The mechanism costs nothing when unused.** Nothing is retained while no snapshot view is open and
  no second writer is running, so a single-writer workload never allocates a byte of it.
- **Concurrent writers did not need the record-granular rewrite this file predicted.** The blocker was
  stated as "page overlays resolve two writers on one page by last-writer-wins", and that was true of
  the overlay as it stood: the commit worked out what it had changed by diffing its copy against the
  live mapping, which by then contained the other writer's bytes. Keeping the image each page was
  copied from removes it — the diff is then exactly the bytes this transaction wrote — and the same
  runs turn out to be the write set that conflict detection needs. [TRANSACTIONS.md §6.1](TRANSACTIONS.md).

**Write skew is now possible at `SNAPSHOT`, and that is a regression this file has to own.** It was
prevented before because writers were serialised and read the live mapping, which is safety by
scheduling rather than by isolation level. `SERIALIZABLE` is built and rules it out.

What concurrent writers cost, and what it bought:

| | Before | After |
|---|---|---|
| Writers | One at a time, for the length of a transaction | Concurrent; serialised only for slot allocation and the commit point |
| A writer's reads | The live mapping | Its own view, plus its overlay |
| Lost update | Prevented by scheduling | Prevented by first-committer-wins, retried by `inTransaction` |
| Write skew | Unreachable | Possible at `SNAPSHOT`, prevented at `SERIALIZABLE` |
| Conflicts | Could not arise | A normal outcome; auto-commit and Cypher writes absorb their own |

Still missing:

- [ ] `xmin` / `version_ptr` placeholder scheme, `UndoStore`, the 64-byte undo record — **not needed**
      by the page-level scheme, and no longer expected to be. The fields stay in the record layouts,
      written as zero
- [ ] `LockTable` with eager conflict detection — **not needed**, and now a choice rather than a gap:
      intents fail faster and cost a striped table on every statement, where validation at commit costs
      nothing until a commit happens
- [ ] SSI — it would abort *less* than the read-set validation that is built, which is a performance
      question rather than a correctness one
- [ ] jcstress harnesses — jcstress is not in the offline repository

### 4.6 Phase 6 — Native layer

- [x] The crate **compiles and is verified**. `NativeCodecTest` loads
      `libmetaloom_graph_native.so` through the FFM linker and proves both codecs are byte-identical
      to the Java reference, in both directions, and that a truncated block is rejected rather than
      over-read. The Maven build still skips gracefully where `cargo` is absent.
- [x] N4 measured, and the measurement **argues against shipping it**: the edge codec is **1.38x**
      faster than Java including the boundary crossing, and `mlg_crc32c` is **7.8x slower** than
      `java.util.zip.CRC32C`. (An earlier note here said 200x. That figure compared a hardware
      intrinsic against a bitwise loop in the crate — two implementations, not two languages — and
      was withdrawn when the crate moved onto the same instruction.) Nothing in the engine calls
      either.
- [x] **`IoEngine` interface, `MmapIoEngine` and `DirectIoEngine`** — the seam deferred from Phase 1
      on the grounds that an abstraction with one implementation is a guess. `IoEngineTest` holds the
      implementations to byte-for-byte equivalence in both directions
- [x] **N2: `O_DIRECT` open and aligned allocation** through libc via FFM. `FileHandle.buffer` is the
      only supported way to allocate, because a heap array has no alignment guarantee and `O_DIRECT`
      rejects an unaligned address outright. Falls back to a buffered descriptor where the filesystem
      refuses the flag, which tmpfs and some overlay filesystems do
- [x] **`posix_fadvise(DONTNEED)`** — page cache eviction without the root that `/proc/sys/vm/drop_caches`
      needs. This is what makes a cold read measurable at all
- [x] **`PageFaults`** — the process major fault counter out of `/proc/self/stat`, which is the only
      honest way to count device reads on a memory-mapped data path
- [x] **`mlg_capabilities` negotiation** (`NativeCapabilities`): version checked before any other
      symbol, a mismatched ABI refused rather than trusted, capabilities reported and unused
- [x] `native-c-lib/` deleted
- [x] Equivalence: the whole suite green with `metaloom.graph.native.enabled` both true and false
- [x] **N1: io_uring, and `UringIoEngine`** — ring lifecycle, submit, reap, and a batch read that
      serves more requests than the ring is deep in waves. **15.1x** over a `pread` loop on 64
      scattered 4 KiB reads through `O_DIRECT`, which upholds the claim in
      [NATIVE_FFM.md](NATIVE_FFM.md) §1 that this is the largest lever in the design. **Called by
      nothing but its own test**, because a mapped data path issues no reads to batch — the whole
      question now has its own file, [IO_URING.md](IO_URING.md)
- [x] `mlg_xxh3` and lz4 in the crate; CRC32C moved onto the hardware instruction, which **withdrew
      the 200x figure** — it compared an intrinsic against a bitwise loop. Rust is still slower, by
      7.8x
- [x] Crate restructured to the `codec/{crc,edges}` plus `ring` layout of [NATIVE_FFM.md](NATIVE_FFM.md) §2.1
- [x] `cargo test` and `cargo clippy -D warnings` run inside `mvn test`, not only in CI, and both are
      clean. Five clippy errors fixed on the way — every `extern "C"` entry point that dereferences a
      caller pointer is now `unsafe`, which changes no symbol and silences no lint
- [x] **The WAL on the direct engine** (`DirectWalTest`, 5 tests). An aligned staging buffer, whole
      blocks only, and a `TYPE_PADDING` record filling the tail of the last block of every flush — so
      **a partly filled block is never rewritten**, which is the rule that keeps a torn write from
      destroying an acknowledged commit. The padding is a real CRC-covered record rather than a gap,
      because an LSN *is* a byte offset and a gap would make one ambiguous. One read-modify-write
      exists, of a single block, when a crash left the log mid-block; every other block is written
      exactly once. Segments are preallocated through the direct handle, so no commit extends a file.
      **`AUTO` resolves to buffered, not direct**: under `FSYNC_ON_COMMIT` each commit rounds up to a
      block, which is up to 4,095 bytes of waste per commit. Choose `DIRECT` deliberately, with group
      commit, or when the log is competing with the data path for page cache
- [x] **lz4 wired into `BlobStore`** (`BlobCompressionTest`, 7 tests). Not a format change after all:
      the extent header has carried a `compression` byte since the format was written and it has
      always been zero, so old stores read as "none" and no version bump is needed.
      **The compressor is native and optional; the decompressor is pure Java and mandatory**
      (`Lz4`), because a blob written where the crate exists must be readable where it does not —
      otherwise the native layer has quietly become load-bearing for reading a store. A 50,000 byte
      run compresses to 207
- [x] **SIMD codec paths — investigated, measured, and declined with evidence.** A SWAR varint
      decoder (eight bytes per operation, no per-byte branch, the 1BRC delimiter trick applied to
      continuation bits) is **2.3x slower** than the loop it replaced, because varints in a real
      adjacency block average 1.93 bytes and the scalar loop exits after one well-predicted branch.
      Decode is under 1 percent of a traversal in any case: 1.85 ns per entry against 230 ns per
      relationship. Reverted; `VarIntSegmentTest` guards the next attempt. Kept from the same work: a
      merge in place of a full sort, and decoding straight out of the mapped page.
      [GRAPH_BENCHMARK.md](GRAPH_BENCHMARK.md) section 9
- [ ] Cursor prefetch onto the ring — the engine exposes `readBatch` and `queueDepth`; no traversal
      calls them yet, which is what would turn 12.4x on a microbenchmark into 12.4x on a query
- [x] **N3 `madvise`**, bound and tested (`PrefetchTest`), exposed as `IoEngine.prefetch`. This is
      what prefetch means on a memory mapped data path: a traversal takes page faults rather than
      issuing reads, so `readBatch` has nothing to batch there however deep the ring is. The ring
      becomes the right primitive for the data path on the day it stops being memory mapped, and is
      already the right one for the log
- [x] **N3 `statx` and reflink** (`StatxTest`). `statx` reports allocated blocks rather than apparent
      length, which is the only way for the engine to see what a sparse segment with punched holes
      actually costs — 67 MiB apparent against 4 KiB allocated on a preallocated log segment, without
      shelling out to `du`. Reflink (`FICLONE`) is bound and **called by nothing**: it is the
      primitive a snapshot would be built on and there is no snapshot feature. It declines on this
      filesystem, which is a false rather than an error
- [x] **`module-info.java`**, so native access can be granted to this module alone rather than to
      everything on the classpath via `ALL-UNNAMED`. Only the `api` package is exported
- [x] **In-jar packaging** of the shared object under `/native/<classifier>/`, with
      `NativeCapabilities` unpacking it when the build-tree paths miss — which they always do outside
      a build tree
- [x] **`cargo miri test` and `cargo fuzz`**, both installed and both run — `tools/verify-native.sh`.
      Miri needed a raw-pointer test module to be worth anything: the existing tests exercise the safe
      internals, and undefined behaviour lives in the `extern "C"` entry points where a caller's
      pointer and length become a slice. It cannot execute syscalls, so the io_uring tests are
      `#[cfg_attr(miri, ignore)]`. **Fuzzing found two real defects within seconds**, both of which
      the differential test against Java structurally could not find, because Java had each of them
      too and the two implementations agreed on the same wrong answer:
      - `current += delta` in the decoder **wrapped silently in release**, where overflow checks are
        off. A wrapped location is usually small, so it passed the range check and the decoder
        emitted a plausible wrong neighbour id instead of reporting corruption
      - a group declaring **zero entries still wrote one**, because the first entry is emitted before
        the `1..count` loop — a phantom edge, and an untyped `ArrayIndexOutOfBoundsException` on the
        Java side when the destination was full

      Both are fixed in both implementations with regression tests, and the decoder now survives 104
      million fuzz iterations
- [ ] **Cross-compilation to a second target.** The packaging layout is per classifier so that adding
      one is a build change rather than a code change, but no other toolchain is installed, so a jar
      built here carries linux-x86_64 and says so
- [x] **Prefetch wired into the traversal frontier**, and **off by default on a measurement**.
      `SegmentSet.prefetch` advises the pages of the nodes queued behind the current one, which is
      the only point at which a mapped data path can have more than one fetch in flight. Warm, it is
      **2.8x slower** — 1,967 ns per edge against 701 — because advising costs a syscall per node and
      there is no fault for it to overlap. It can only pay on genuinely cold pages, and this
      filesystem cannot produce those: `posix_fadvise` evicts nothing here, so the cost is measurable
      and the benefit is not. Enable with `-Dmetaloom.graph.prefetch.traversal=true`

**The fallback rule still holds after all of this.** The one change that could have broken it was lz4:
a compressed blob that only the crate could read would have made the native layer load-bearing for
reading a store. The decompressor is therefore pure Java and the compressor is the only optional half.
Everything else — hole punching, `O_DIRECT`, io_uring, `madvise`, `statx`, reflink — degrades to a
store that keeps its blocks, writes buffered, faults one page at a time and cannot measure cold reads.
The whole suite passes with `metaloom.graph.native.enabled` both true and false.

### 4.7 Phase 7 — Indexes

- [x] **Label-scoped unique constraints** (`UniqueConstraintTest`). `createUniqueConstraint(label, key)`
      says "no two `:Person` share an email", which the store-wide form cannot express at all. It is a
      declared composite index plus a flag, so it is enforced on `addNodeLabel` as well as on a
      property write — adding the label is the other way to break it
- [x] **Unique property indexes** (`UniqueConstraintTest`). `createUniqueConstraint(key)` is durable,
      survives a reopen, is enforced on create and on update, and **verifies candidates against the
      stored value** before refusing — the index is keyed on a value hash, so an unverified conflict
      could be a collision between two different values, and refusing a legitimate write is as bad as
      accepting an illegitimate one. Declaring a constraint the data already breaks is refused rather
      than recorded
- [x] **The device-read exit criterion is now measurable**, via `PageFaults` and cache eviction.
      `DeviceReadBudgetTest` asserts ≤ 4 device reads for an indexed lookup, ≤ 1 for a node read and
      ≤ 1 for a low-degree traversal — see the caveat below, which is the important part
- [ ] Move the dictionaries onto the B+tree — they are an append-only file plus an on-heap map today
- [ ] Online index build with a WAL-logged progress marker, resumable after a crash. A build is
      currently one transaction
- [ ] Optional string intern table for property dedup
- [ ] B+tree adjacency for extreme supernodes — **and now measured not to be needed.**
      `SupernodeBenchmark` builds a single node of 200,000 relationships: insertion costs **1.73 µs
      per edge in the last thousand against a 1.60 µs best**, and reading costs **0.019 µs per edge
      at degree 200,000 against 0.024 at degree 2,000**. Both improve with degree. The chain tier is
      the ceiling in theory; in practice nothing has reached it. Revisit only if a measurement, not
      an intuition, says so — [GRAPH_BENCHMARK.md](GRAPH_BENCHMARK.md) section 8
- [x] **Vector search — HNSW, implemented here rather than delegated.** All six stages of
      [VECTOR.md §7](VECTOR.md) are built: the `vector.*` distance functions and an exact
      `nearestByScan`; a `VEC` kind whose record interleaves a vector with its ANN links, so a visited
      node costs one page read; a declared index whose membership the hooks maintain; the HNSW graph
      with entry-point repair; a resumable batched build; and `CALL vector.query(...) YIELD node,
      score`, whose real value is that the following `MATCH` verifies an already-bound node — a graph
      predicate applied to a vector search with no planner change. Format version 4, written **only**
      by a store that has vectors, so every existing store still opens
- [ ] Concurrent split/merge under jcstress — jcstress is not in the offline repository
- [ ] Crash-during-build test — needs the resumable build above

**The device-read numbers cannot be taken on this machine, and the test says so instead of pretending.**
`posix_fadvise(DONTNEED)` is *accepted* on this overlay filesystem and evicts nothing: touching 16,384
pages of a freshly evicted 64 MiB file produced **one** major fault. The first version of the test
believed it and reported 0.15 device reads per cold node read — a warm cache wearing a cold label, and
a much more flattering number than the truth. `DeviceReadBudgetTest` now proves eviction works before
trusting anything that depends on it, and skips when it does not. On ext4 or xfs it will measure.

### 4.8 Phase 8 — Compaction

- [x] **Fixed: a compaction interleaved with a foreground writer could leave a `NODE` page whose
      checksum does not match its contents.** `CompactionTest.testCompactionInterleavesWithForegroundWrites`
      fails on its closing `verify(FULL)` with `PageCorrupt: Checksum mismatch in .../node-000000.seg
      page N`, N varying per run, at a **measured 6 failures in 12 runs on a clean checkout of
      `0970e6b`**. It passes far more often inside a full `mvn test` than run alone, which is why the
      reactor has been green over it — a green run is not evidence here. This is the race between
      batched relocation and a concurrent writer, and a checksum that disagrees with its page is
      precisely what the checksum exists to catch, so it should be read as an engine defect until
      shown otherwise rather than as a flaky test. **Diagnose it with a worktree and interleaved runs**:
      a sequential A-then-B comparison of this test measures the machine's state as much as the code,
      and gave 8/8 against 3/8 where interleaving gave 6/12 against 9/12.
      <br>**The cause.** `SegmentSet.reclaim()` reaches `SegmentFile.force()`, which calls
      `flushChecksums()` — and that walks the segment's dirty-page `BitSet` and then *clears* it.
      `Compactor` called `reclaim()` outside `runWrite()`, so it ran on the compaction thread while a
      foreground writer was still setting dirty marks under the writer lock. The clear discarded marks
      whose checksums had not been recomputed, and those pages kept a checksum that no longer described
      their bytes. The failing assertion was the closing `verify(FULL)`, which is the checksum doing
      exactly its job — one page behind.
      <br>**Why it looked dormant.** It first appeared not to reproduce at all: 0 of 8 at `0970e6b`,
      0 of 7 interleaved at `95860b1`, 0 of 12 at `4007617`. The reason was in the test rather than the
      machine. Its writer thread created a fixed 200 nodes, which finish in a few milliseconds — long
      before compaction reaches the reclaim phase at the *end* of the pass, which is the only phase that
      calls `reclaim()`. **The test named for an interleaving was not interleaving with the code that
      had the bug.** Holding the writer open for the whole `compact()` call reproduces it at once.
      <br>**Measured**, with the writer held open throughout: **8 failures in 10** against the pre-fix
      engine, and **0 in 12** with the locking fix — then 0 in 12 again over the whole of
      `CompactionTest`.
      <br>**The fix** is the one this file already called for: `Compactor` wraps `set.reclaim()` and
      `set.retireEmptySegments()` in `runner.runWrite(...)`, so every path that reaches
      `flushChecksums()` holds the writer lock and no second thread can be setting marks. The related
      `verify()` defect above is the same mechanism reached through the public API, and was fixed with it.
      <br>**And the durability gap it was blocking is now closed too.** `reclaimEmptyPages()` deliberately
      omitted a trailing `force()`, because adding one made the race measurably worse — a punch takes
      effect immediately while the metadata describing it does not, so a crash in between lost both the
      checksum fix-up and the generation floor. With the locking fixed that `force()` is safe and is
      back. It costs nothing measurable: `graph-core` runs in ~50 s with and without it, against a
      run-to-run variance of 50–84 s on the same host.
      <br>`CompactionTest`'s writer now runs for the whole compaction rather than 200 nodes, so the test
      exercises what its name claims.
- [ ] Foreground p99 latency budget during compaction. Compaction takes the writer lock one batch at a
      time and `CompactionTest` shows a concurrent writer making progress throughout, but a p99 claim
      needs a real latency harness — and the defect above has to be settled first, since it is the same
      interleaving.
- [ ] `StaleIdException` slow-path fallback through the forwarding map — **deliberately not built**.
      Making a batch atomic removes the need for it, which is what keeps the engine free of any
      indirection table.

### 4.9 Phase 9 — Hardening

- [~] **JMH.** Its annotation processor is not in the offline repository. `PerformanceGateTest` is a
      warmup-measure-compare substitute with floors at roughly 40 percent of the reference measurement
      — low enough not to fail under machine load, therefore too low to catch a small drift. The
      structural gates beside it are exact and carry the weight.
- [x] **`FaultInjectingIoEngine` and the crash matrix** — level 2 of
      [TESTING.md §6](TESTING.md#6-crash-and-fault-injection), which was the largest single gap in the
      project. All six device faults are covered, split by what they actually are:
      - **Four are disk states** — lost, torn, corrupt and reordered writes — and `CrashMatrixTest`
        produces each of them at **every record in the log**, mechanically, then reopens and checks
        five properties: the survivors are a prefix, no transaction is partial, the store opens or
        fails in the engine's own vocabulary, whatever recovered verifies, and recovery is idempotent
      - **Two are behaviours** and no file on disk can express them — a failing write, and a write
        that succeeds followed by a failing fsync. `WalFaultTest` injects those through an engine that
        misbehaves while the log is being written. The rule they defend: `sync()` may not return
        normally unless the bytes are on the device, and `durableLsn` may not advance past what was
        forced

      The matrix is non-vacuous by assertion, not by hope: it fails if no case ever loses a
      transaction. The workload restores its data files from a pre-workload snapshot, because a commit
      here applies its pages as soon as the log is fsynced — without that the data files are already
      current, damaging the log changes nothing, and every property passes while testing nothing.

      **Measured at 24 transactions: 2,394 cases over 404 records, 240 s, every property held.**
      Recovery depth ranged from 0 to 24 transactions and **2,290 cases lost at least one**, so the
      damage was doing real work. The result worth quoting is the one nobody had evidence for before:
      **not one case failed to open.** Under every fault at every record, the log degraded to a
      shorter prefix rather than becoming unreadable — which is what the CRC plus self-identifying LSN
      was designed to guarantee and had never been shown to do. The default is 12 transactions so
      `-Pcrash` stays about a minute; `-Dgraph.crash.transactions=24` reproduces the run above.
- [~] **The long runs have not been run.** The soak harness has run for 25 seconds, not 12 hours. The
      `kill -9` loop ran 8 iterations clean, not a week of nightlies. Both are one command.
- [~] **The manual power-loss test is written and untested.**
      [OPERATIONS.md §8](OPERATIONS.md#8-the-manual-power-loss-test) is a full procedure, down to
      checking whether the device's write cache is honest. Until it is performed, the assumption
      underneath every durability claim here is documented but unverified.
- [x] **Online logical backup**, with a CLI that ships inside both container images and compiles to a native
      binary of its own ([BACKUP.md](BACKUP.md), [GRAALVM.md §10](GRAALVM.md#10-the-second-binary)). A cold copy
      is still the only thing that preserves ids and index structures byte for byte.
- [ ] **No incremental or physical backup.** The log is trimmed at each checkpoint, so a dump cannot be rolled
      forward, and `NativeLib.reflink` is still bound and called by nothing.
- [ ] **The API is not frozen**, and [reports/API_REVIEW.md](reports/API_REVIEW.md) recommends against
      freezing it: MVCC, cursors and backup would each change the surface.

**Performance targets** from [TESTING.md §7](TESTING.md#7-benchmarks), scored:

| Target | Result |
|---|---|
| Node read by id, warm: > 5M ops/s/core, 0 device reads | **Met** — 9.0M single threaded, 280M across 32 threads, 1 record touch, 0 index probes |
| Neighbour enumeration, degree 2: 0 adjacency reads | **Met**, gated exactly |
| Neighbour enumeration, degree 4000: 1 adjacency read | **Met**, gated exactly |
| Bytes per node with two small properties: < 140 | **Met** — 128 |
| Bytes per property-free edge: < 4 | **Met** — 2.0 to 2.5 |
| Write amplification, mixed workload: < 3x | **Met** — 1.70x |
| Node insert, durable, group commit: > 200k ops/s | **Missed by an order of magnitude** — 17k. Structural; gated on Phase 5 |
| Node read by id, cold: 1 device read, p99 < 200 µs | **Not measured** — needs Phase 6 |
| Recovery after 1 GiB of WAL: < 30 s | **Not measured** — needs Phase 6 |

---

## 5. The Backlog, In The Order That Buys The Most

1. **Run the long runs** (§4.9). Two commands, no new code, and they either confirm the leak and
   fragmentation bounds or find something. Still the largest remaining gap, because it is the only one
   that could still be hiding a correctness bug.
2. **Verify page checksums on read** (§4.4). The field is already written; this is a read-path check
   and a test. Note that the cost lands on the hottest path in the engine — a node read by id is 21 ns
   — so it needs the same measure-then-default treatment the traversal prefetch got.
3. **The manual power-loss test** (§4.9). `WalFaultTest` establishes that a device which lies about
   writing defeats the log and that nothing in the format can tell. That is not a defect to fix; it is
   the reason the manual procedure exists, and it is still unperformed.
4. **Collapse redundant retained versions** (§4.5). Measured: a view held across 60,000 commits
   retains 200.6 MiB in 990,899 entries, of which the reader needs 192 KiB. A page every commit
   touches collects one entry per commit when only the oldest can ever be needed. Worth doing, and
   deliberately not done in a hurry — the dedup rule has to be exactly right or a reader gets a
   silently wrong page, and the retention budget already bounds the damage.
5. **Concurrent writers** (§4.5), if and only if they become a requirement. The largest piece of work
   left in the plan, and the one that gates the group-commit throughput miss. It is a rewrite of the
   write path, not an addition: page overlays resolve two writers on one page by last-writer-wins.

**Done and off this list**, each of which held the top slot in turn:

- **The crash matrix** (§4.9) — 2,394 fault cases, every property held, one engine defect found.
- **The middle adjacency tier**. A block is now a run of 512 byte cells that doubles as the node
  fills, which took a 10M-node random graph from 61 GiB to 6.2 GiB and its load from 43 minutes to 11
  ([reports/SCALE.md](reports/SCALE.md)).
- **Phase 6's `IoEngine`**, and with it `O_DIRECT`, io_uring, `madvise` and `statx`.
- **`cargo miri` and `cargo fuzz`**, which found two decoder defects the differential test against
  Java structurally could not, because Java had both of them too and the two agreed on the same wrong
  answer.

**Also not on this list any more**: compiling the Rust crate, and writing N1 and N2. All three are
done. The measurement says the crate's codecs earn nothing — 1.38x on a codec that is not the
bottleneck, 7.8x *slower* than the JDK on CRC32C — and that the case for native code rests entirely
on io_uring and `O_DIRECT`, which are now built and measured at **12.4x** on a batch of scattered
reads. What remains is wiring that batch to a caller: nothing in the engine issues one yet.

---

## 6. Phase 10 — MetaLoom Integration

Built in the MetaLoom repository, not this one. Feature spec:
[metaloom/spec/features/graph/ASSET_GRAPH.md](../../metaloom/spec/features/graph/ASSET_GRAPH.md).

- [x] Identify the use case with the best fit — the **asset relationship graph**: assets, tags,
      collections, remixes and people across four link tables, queried with a union self-join
- [x] `AssetGraphIndex` SPI, mirroring MetaLoom's existing `VectorIndex` contract
- [x] `GraphStoreAssetGraphIndex` over this engine; everything uuid-addressed, so compaction — the one
      operation that invalidates ids — cannot reach a caller
- [x] `NoopAssetGraphIndex` and a Dagger binding that cannot fail boot; `LOOM_ASSET_GRAPH_PROVIDER`
      defaults to `none`
- [x] Differential check against a **real** Postgres across insertions, deletions, a full rebuild and a
      compaction — `AssetGraphDifferentialTest`, 6 cases, byte-identical answers
- [x] Benchmark at three scales against both a naive and a tuned SQL formulation
- [x] Decide on measured results — **do not adopt**
- [x] Publish to the MetaLoom Maven repository **if adopted** — not adopted, so not published

| assets / edges | naive SQL | tuned SQL | this engine | vs naive | vs tuned |
|---|---|---|---|---|---|
| 1,000 / 8,000 | 1,684 µs | 388 µs | 165 µs | 10.2x | **2.4x** |
| 10,000 / 80,000 | 14,204 µs | 303 µs | 127 µs | 111.6x | **2.4x** |
| 50,000 / 400,000 | 126,755 µs | 456 µs | 193 µs | 657.1x | **2.4x** |

Against the obvious query this engine is 657x faster and pulls away as the graph grows. Against the
same query written properly it is 2.4x faster and stays there. Only the second number is a reason to
do anything, and 2.4x on a 0.3 ms query does not pay for a second copy of every relationship, a
projector to maintain it, an orphan sweep, and a backend with one writer and no backup.

**Revisit when** the union grows past six or seven branches, when assets routinely carry high-degree
intermediates, or when the question changes shape — three hops, or path-finding between two assets.
That last is the most likely trigger and the least like the query measured here.

---

## 7. What Will Not Be Done

Recorded so nobody re-opens them as oversights:

- **An indirection table**, in any form, including the compaction forwarding map on disk. See
  [ID_ENCODING.md §6](ID_ENCODING.md#6-why-not-an-indirection-table).
- **ARIES steal/no-force logging**, unless the data path moves off `mmap` first. See
  [reports/PHASE_STATUS.md §1](reports/PHASE_STATUS.md#1-phase-4--what-was-built).
- **Replacing Postgres as MetaLoom's system of record.** The vector index is the precedent: Postgres
  is the source of truth and the specialised store is a derived index with a defined rebuild path.
- **A 1.0 API freeze**, until at least the cursor surface exists. The backup half of this is now built
  ([BACKUP.md](BACKUP.md)), and it added `options()` and the paced `forEachNodeWhile` to the public API.

---

## Authentication and authorization

Built. [AUTH.md](AUTH.md) is the design; what follows is what moved.

- [x] `Privilege` (15), `Grant` (privilege + database, `*` wildcard), `Principal`, `User`, `Role` in `core.api`
- [x] `Ast.Query.requiredPrivileges()` — an exhaustive switch over the sealed `Clause`, so a twelfth clause is a
      compile error until its privilege is declared
- [x] `<root>/.auth/` — a binary, CRC32C-checked file rewritten whole under a short-lived lock, with a hostile-input
      oracle that refuses **every single-bit mutation**
- [x] PBKDF2-HMAC-SHA512 at 210,000 iterations, parameters stored per user so the cost can be raised later
- [x] GraalVM reachability metadata for the JCA provider, and a CLI smoke test that catches its absence — which no
      green `mvn verify` can
- [x] `metaloom-graph-cli auth reset-admin | list | user | role | grant | revoke`, offline only, working against a
      root a live server has open
- [x] HTTP: `POST /api/login`, `POST /api/logout`, `GET /api/whoami`, a filter on `/api/*`, sessions keyed by a hash
      of the token, a login throttle ahead of the derivation
- [x] Privilege checks on every existing route; `GET /api/databases` filtered rather than refused
- [x] User and role administration over REST, with `MANAGE_USERS` deliberately not implying `MANAGE_ROLES`
- [x] Protocol version **4**: `AUTH_REQUIRED`/`AUTH`, `UNAUTHENTICATED`/`UNAUTHORIZED`, and a password that is only
      ever sent to a server that asked for it
- [x] `ClientOptions.withCredentials`, `GraphClient.login`, and grants reported in `WELCOME` as strings
- [x] MCP: bearer on all three transports, and the write gate's two enforcement points extended to privileges
- [x] The browser UI: a login screen, a users-and-roles panel, and write affordances driven by the grant as well as
      by the flag
- [x] Authentication **on by default** in `main`, off in the programmatic constructors; the images and the chart
- [x] TLS on the **binary** port — `--wire-tls-cert`, a second listener on 8084, `--wire-tls-only` to close the
      cleartext one, and a client that verifies by default ([WIRE.md §7.1](WIRE.md))
- [ ] TLS on the **HTTP and MCP** ports. Both are HTTP and expect an ingress or a proxy; without one a password still
      crosses them in cleartext — `AUTH-1` in [TASKS.md](TASKS.md)
- [ ] Certificate reload without a restart, and client certificates as an identity (`AUTH-8`)
- [ ] An audit log — `AUTH-2`
- [ ] An external identity provider — `AUTH-3`
- [ ] Authorization below a database — `AUTH-4`
- [ ] A shared session store, so a second replica honours a token the first issued — `AUTH-6`
- [ ] `graph_auth_logins_total` and a session gauge. Deliberately not added with the rest: a new meter means a row in
      [METRICS.md](METRICS.md) §3 *and* a line in `MetricsCatalogExercise`, because `MetricsCatalogScrapeTest` scrapes
      a real registry and fails on a documented meter that never fires. The refusals are counted today through the
      existing `graph_queries_rejected{reason}` with two new label values, which needs no catalogue change — `AUTH-7`

**The stale line this change also fixes**: this file said "Protocol version 2: the database is chosen per connection at
`HELLO`". It was 3 before this work and is 4 now.

---

_Git HEAD revision: `58cd33e`_
_Last updated: 2026-08-18 (TLS on the binary port)_
