# Testing, Benchmarking and Verification

A storage engine is only as good as its evidence. This file defines how the project proves that it
does not lose data.

The suite today is 240 tests running in about forty seconds, plus benchmarks, a soak, a scaling
harness and a `kill -9` loop behind profiles and scripts. The PoC suite it replaced had one red test,
three unbounded benchmarks living in the unit suite, and two tests that passed for reasons unrelated
to what they asserted ([CURRENT_STATE.md §5](CURRENT_STATE.md#5-test-reality-check)).

**What has actually caught bugs**, in order, so that effort goes where the return is:

| Test | Defects found |
|------|---------------|
| `HostileInputTest` | 7 — unbounded allocations, non-terminating chain walks, untyped exceptions from corrupt pointers |
| `ReferenceModelTest` | 2 — a false-positive invariant check, and materialised self-loops reading back as two outgoing edges |
| `ScalingTest` | 2 — a synchronized dictionary and a shared counter, together costing 63x aggregate read throughput |
| `ReadBudgetTest` | 3 redundant record reads on the hottest paths |
| `CompactionCrashTest`, `RecoveryTest` | 2 — a stale superblock after replay, an fsync per commit with nothing to sync |
| `UsageExampleTest` | 1 — an indexed lookup after a rolled-back *first* transaction threw `StaleIdException` on a healthy store |
| `GraphScaleBenchmark` | 1, and the largest: a node with degree 3 cost a whole 8 KiB adjacency block, worth 59 of 61 GiB on a mean-degree-4 graph. Fixed; the same graph is now 6.2 GiB and loads 3.9x faster ([reports/SCALE.md](reports/SCALE.md)) |
| The cell-based adjacency change itself | 1 — `flags` was declared one byte and written four, silently owning the reserved bytes beside it |
| `UniqueConstraintTest` | 1 — the unique-key array was placed at offset 28 of the index catalog page, over the generation field `Kind.INDEX` keeps at 30, which made every id naming that page stale |
| `DeviceReadBudgetTest`'s own self-check | 1, in the measurement rather than the engine: `posix_fadvise` is accepted and evicts nothing on an overlay filesystem, so the first version reported a warm cache as 0.15 cold device reads |
| `cargo clippy -D warnings` | 5 — every `extern "C"` entry point dereferencing a caller pointer was safe-by-signature |
| The corrected CRC32C benchmark | 1, in a published number rather than the code: "200x slower" compared a hardware intrinsic against a bitwise loop. It is 7.8x |
| Running `cargo test` in a loop | 1 — both ring tests built their temp file path from the pid alone, so they shared it across threads of one process and one truncated the file the other was reading. Failed about one run in five |
| `CodecProfileBenchmark` | 2, both in changes that looked like optimisations: a SWAR varint decoder 2.3x *slower* than the loop it replaced, and an allocation-free encoder 2.3x *slower* than the allocating one |
| `SupernodeBenchmark` | 0 so far, and it is the gate that would catch a quadratic term in adjacency growth before a user does |
| `cargo fuzz run edges_decode` | **2, within seconds of the target existing, and both were in *both* implementations** — a silently wrapping delta accumulation that turned a corrupt block into a plausible wrong neighbour id, and a zero-entry group that still wrote an entry. Neither was findable by the Rust-versus-Java differential test, because Java shared both defects and the two agreed on the wrong answer |
| `cargo +nightly miri test` | 0 so far. It needed an `ffi_tests` module to be worth running at all: the existing tests exercise safe internals, and undefined behaviour lives in the entry points where a caller's pointer becomes a slice |
| `CrashMatrixTest` | 1 — recovery against data files older than its log threw a raw `IllegalStateException` rather than a typed store failure, so a caller could not distinguish "this store is damaged" from "this code has a bug". Found on the matrix's first meaningful run |
| `RollbackTest` | **1, and it was a false alarm on healthy data** — `verify(FULL)` bounded a blob's extent-chain walk at `segmentCount() * slotsPerPage() + 1`. A BLOB extent is page sized, so that is 2 on a single-segment store: every blob past about 16 KiB was reported as a chain that "does not terminate", with its remaining extents reported as orphaned on top. Nothing caught it because no test had ever written a blob and then run `verify(FULL)`. Reporting corruption that is not there is worse than missing some: it sends somebody hunting, and it made full verification unusable on any store holding real blobs |
| `CypherVersusApiTest` and running the UI | 2 — a `RETURN *` that listed pattern variables out of order, and, found only by opening the browser, **64-bit ids losing precision as JSON numbers**: an id near 2^60 arrives in JavaScript rounded, so two nodes could collapse into one circle. Ids now cross as strings |
| `LongRunningReaderTest` | 1 — reclamation walked every version chain on every commit to discover there was nothing to cut, which is quadratic while a reader is pinned. The test did not fail, it *did not finish*, which is the failure mode a shorter test would have reported as merely slow |

The pattern is worth naming: **every one of these came from a test that compared the engine against
something outside it** — a model, a counter, a corrupt file, a thread count — rather than from a test
that asserted what its author already believed.

The fuzzer rows sharpen that into something more specific. A differential test compares two
implementations, and therefore finds only the mistakes they do not share. Both decoders here were
written from the same format description by the same person, so they shared their bugs; it took an
oracle that knew nothing about either — "this must not panic and must not write past its buffer" — to
find them.

---

## 1. Test Categories

| Category | Runs in | Duration budget | Purpose |
|----------|---------|-----------------|---------|
| **Unit** | every build | < 60 s total | Encoders, layouts, bit packing, pure logic |
| **Property-based** | every build | < 120 s | Round trips, invariants over generated inputs |
| **Integration** | every build | < 5 min | Whole-store workflows against a temp directory |
| **Concurrency** | every build (short), nightly (long) | 2 min / 60 min | Latches, visibility, conflict detection |
| **Crash / fault injection** | nightly, and on any change to WAL, page cache or a store | 30 min | Recovery correctness |
| **Fuzz** | nightly | 60 min | Codec and format parsers against corrupt input |
| **Benchmark** | nightly and on demand | 30 min | Performance and IOP budgets, with regression gates |
| **Soak** | weekly | 12 h | Leaks, unbounded growth, fragmentation, vacuum lag |

Surefire runs unit, property and integration. Everything else is a separate Maven profile or an
explicit JUnit tag, never mixed into the default build.

---

## 2. Layout

```
src/test/java/io/metaloom/graph/core/
  unit/          per-class tests, no filesystem beyond a temp dir
  property/      jqwik property-based tests
  integration/   whole-store scenarios
  example/       UsageExampleTest — the documented API, executable, so it cannot rot
  nativelib/     NativeCodecTest, IoEngineTest, UringIoEngineTest — the crate and the IO engines
  txn/           snapshot isolation: visibility, the anomaly matrix, vacuum, rollback, long readers

graph-cypher/src/test/java/io/metaloom/graph/cypher/
                 parser, null semantics, execution, the versus-API differential, variable-length paths
graph-server/src/test/java/io/metaloom/graph/server/
                 the HTTP contract, the two result shapes the UI needs, the command line, and
                 demo/DemoDataTest — the generated air route graph the browser suite drives
graph-server/src/main/frontend/src/model/*.test.ts
                 the UI's rules that are logic rather than appearance: what a merge does to node
                 identity, and that a label's colour is a function of its name. Run by `mvn test`
                 through npm, so they are in the same suite as everything else
graph-server/src/main/frontend/e2e/
                 Playwright, against the real server over a real store. Opt-in: `npm run e2e`
  concurrency/   jcstress and custom harnesses
  crash/         fault-injection recovery tests
  invariant/     the checkers themselves plus their tests
  bench/         BulkBenchmark, CodecProfileBenchmark and SupernodeBenchmark (tagged `bench`);
                 GraphScaleBenchmark and GraphTraversalBenchmark (tagged `huge`, opt-in only)
  fixture/       graph generators, temp store rule, deterministic RNG
```

JUnit 5 tags: `@Tag("unit")`, `@Tag("slow")`, `@Tag("crash")`, `@Tag("fuzz")`, `@Tag("bench")`,
`@Tag("huge")`. The default configuration excludes all but `unit`; **`huge` is excluded from
`-Pall-tests` as well**, because it writes gigabytes and runs for minutes. It is opt-in only, via
`-Phuge`.

---

## 3. Running the Suite

```bash
mvn test                         # unit + property + integration + budgets + hostile input, ~20 s
mvn test -Pslow                  # adds the scaling curve and the soak
mvn test -Pbench                 # adds the benchmarks and the performance gates
mvn test -Pall-tests             # everything
mvn test -Dmetaloom.graph.native.enabled=false   # force the pure-Java path

mvn test -Pslow -Dtest=SoakTest -Dsoak.seconds=43200   # the twelve hour soak
tools/kill9-loop.sh 20 3                               # the kill -9 loop

# 10M nodes + 20M relationships: 11 to 25 minutes depending on the host, 6.1 GiB.
# Every run measured so far, and what moved between them: GRAPH_BENCHMARK.md
mvn test -Phuge -Dtest=GraphScaleBenchmark
mvn test -Phuge -Dtest=GraphScaleBenchmark -Dgraph.scale.nodes=1000000 -Dgraph.scale.edges=2000000

# Read side: random access by id, a 30,000 relationship walk, a 30,000 hop chain. ~3 minutes.
mvn test -Phuge -Dtest=GraphTraversalBenchmark

# One node with 20,000 relationships, with gates asserting it does not degrade. Runs in -Pbench.
mvn test -Pbench -Dtest=SupernodeBenchmark
mvn test -Pbench -Dtest=SupernodeBenchmark -Dgraph.supernode.degree=200000

# Where the time inside a block read and write goes, which is how the SIMD question was settled.
mvn test -Pbench -Dtest=CodecProfileBenchmark

# The two checks on the Rust crate that cannot run inside `mvn test`: miri for undefined behaviour in
# the FFI entry points, and libFuzzer against the decoders. Needs a nightly toolchain and cargo-fuzz.
tools/verify-native.sh          # miri, then 60 seconds per fuzz target
tools/verify-native.sh 600      # a longer fuzz run
```

**Installing the two of them**, which the dev container now does at build time:

```bash
sudo apt-get install -y rustup     # replaces the Debian cargo/rustc packages
rustup toolchain install stable  --profile minimal --component clippy rustfmt rust-src
rustup toolchain install nightly --profile minimal --component miri rust-src
rustup default stable
cargo install cargo-fuzz --locked
```

CI runs `mvn test` twice, once with native enabled and once disabled, and asserts both pass; see
[.github/workflows/ci.yml](../.github/workflows/ci.yml). It also runs the slow profile with a one
minute soak, the `kill -9` loop, the performance gates, and a `cargo build` and `cargo test` of the
Rust crate. `NativeCodecTest` checks the built library against the Java reference and skips when it
is absent, which is the normal state of a machine without `cargo`.

The PoC suite is gone: its bulk loops became `bench/BulkBenchmark`, its FFM exploration tests were
deleted along with the code they exercised, and the traversal it could not perform is covered by
`GraphStoreTest#testBreadthFirstTraversal`.

`jqwik` is not present in the offline Maven repository, so the property-based tests of section 4 are
written as seeded JUnit loops that print their seed on failure. Substituting jqwik later is a
mechanical change.

---

## 4. Property-Based and Invariant Tests

`jqwik` is the tool. The highest-value properties:

| Property | Statement |
|----------|-----------|
| Id round trip | `decode(encode(k,s,l,g)) == (k,s,l,g)` for all valid inputs |
| Address determinism | `byteOffset(id)` depends only on `kind` and `slot` |
| Page containment | every record's byte range lies within one page |
| Property block | any `Map<key, typedValue>` encodes and decodes to an equal map |
| Edge codec | any multiset of packed edges encodes and decodes to itself |
| Native equivalence | native and Java codecs produce byte-identical output |
| WAL record | any record encodes and decodes to itself; any single-bit flip is detected |
| Store equivalence | a sequence of operations applied to the store and to an in-memory reference model produce the same observable state |

The **reference model** is built: `GraphModel` is a naive `HashMap`-based in-memory graph with the
same semantics, and `ReferenceModelTest` applies a seeded random operation sequence to both, comparing
labels, properties, degrees, neighbour multisets, both indexes and the live counts after every batch
— across rolled-back transactions, simulated crashes and a compaction.

It earned its reputation on the first run. Two defects, neither of which any hand-written test in the
suite had found:

1. `InvariantChecker` enumerated property blocks through the free slot bitmap, which cannot tell a
   block header from a continuation cell, and reported corruption on a healthy store whenever payload
   bytes happened to look like an owner id.
2. A **materialised self-loop** was stored as two byte-identical adjacency entries, and since a
   materialised entry resolves its direction from the relationship's endpoints, both read back as
   outgoing. The store disagreed with its own degree counters.

Comparing across a compaction needed one extra idea: ids are invalidated, so the model cannot be
keyed on them. The harness fingerprints the graph by a unique property instead, which is exactly what
an application holding ids across a compaction has to do.

---

## 5. Invariant Checkers

Executable predicates over a whole store, used by tests, by `store.verify(FULL)` and by the crash
harness.

| Checker | Asserts |
|---------|---------|
| `PageChecksums` | Every page's PMA checksum matches its content |
| `SlotAccounting` | FSB bits, PMA `live_count`, and superblock `live_count` all agree; every live slot has `gen >= 1` and `LIVE` set |
| `Bidirectionality` | Every out-edge on `a` has exactly one matching in-edge on `b`, and vice versa |
| `DegreeCounters` | `degree_out` / `degree_in` equal the actual adjacency entry counts |
| `NoDanglingRefs` | Every `prop_block_id`, `adj_head_id`, `version_ptr`, `from_id`, `to_id` and blob reference resolves to a live record of the right kind |
| `NoOrphanBlocks` | Every live property block, adjacency block and blob has a live owner (checked via `owner_id`) |
| `AdjacencyChains` | Every adjacency block is in exactly one node's chain, its header names that node, the chain terminates, and no live block is unreachable |
| `VersionChains` | Every `version_ptr` chain terminates, is ordered by descending `end_ts`, and contains no cycle |
| `DictionaryIntegrity` | Every `label_id` / `key_id` referenced by a record exists in the dictionary |
| `WalContinuity` | Every LSN between `wal_min_lsn` and the tail is present and self-consistent |
| `BlobChains` | Every blob a live property block references is live, unshared, and its extent chain terminates |
| `NoOrphanCells` | Every allocated property cell belongs to a block some live element points at |

Each checker reports every violation with segment, page and slot — not just the first — because a
corruption pattern is far more diagnostic than a single instance.

`verify(FULL, VerifyOptions.throttled())` runs the same checks against a live store, dropping the read
latch every 4096 elements so a writer waits for a batch rather than for the whole scan. The cost is
that the result is no longer a single instant: a concurrent write can produce a violation report for a
state that never existed as a whole. `VerifyOptions.consistentSnapshot()` is the opt-out and is what
tests use. A scan cut short by its violation limit or time budget says so in its output, because
"no violations found" from a scan that stopped early is the one result a verification tool must never
produce.

---

## 6. Crash and Fault Injection

Three levels, weakest to strongest:

**Level 1 — logical crash.** Abandon the `GraphStore` object without closing, discard in-memory
state, reopen from the same directory. Catches missing WAL records and bad recovery logic. Fast
enough to run per record type in the default suite.

**Level 2 — simulated device faults.** *Built. `CrashMatrixTest` and `WalFaultTest`.*

The six faults split into two kinds, and the split decides how each is produced:

- **Four are disk states**: a lost, torn, corrupt or reordered write leaves bytes that differ from
  what the engine wrote, and those bytes can be produced directly. `CrashMatrixTest` writes a serial
  workload, locates every record in the log by scanning it, and applies every fault to every record —
  then restores the data files to a pre-workload snapshot so that recovery has to rebuild from the log,
  reopens, and checks the five post-conditions below.
- **Two are behaviours**: a write that fails, and a write that succeeds followed by an fsync that
  fails. Neither is a state a file can be in, so they need an engine that misbehaves while the log is
  being written. That is what `FaultInjectingIoEngine` is, and it is a test fixture — nothing in the
  engine should be able to ask for a broken device.

A `FaultInjectingIoEngine` decorator that can, at a chosen
operation index:

- drop a write entirely (lost write),
- write only the first half of a page (torn write),
- write garbage (corrupt write),
- reorder two writes,
- fail with `ENOSPC` or `EIO`,
- succeed the write but fail the subsequent fsync.

The crash matrix runs a fixed workload and injects each fault at **every** I/O index, reopening and
running the invariant checkers each time. This is the core of the durability evidence and it is
mechanically generated, not hand-written.

**Level 3 — real process and power loss.**

- `kill -9` under load, in a loop, with a post-condition checker. Run nightly.
- A VM or physical machine with the write cache honestly configured, cut under load. Documented as a
  manual procedure with a checklist, run before any release that claims durability.

**The post-condition, in all three cases:**

1. The store opens.
2. All invariant checkers pass.
3. Every transaction the client observed as committed is present.
4. No transaction the client did not observe as committed is present *unless* it committed
   concurrently (the ambiguous window is explicitly allowed and is bounded by the sync mode).
5. Recovery is idempotent: crashing recovery itself and re-running converges to the same state.

Point 3 requires the workload to record its own commit acknowledgements to a separate, independently
synced file — otherwise the test cannot tell "lost a commit" from "never committed".

---

## 7. Benchmarks

**JMH is not available.** Its annotation processor is not in this project's offline Maven repository, so `@Benchmark`
classes cannot be generated and the suite below cannot be built as specified. What exists instead is
`PerformanceGateTest`: a warmup pass, a measured pass, and a comparison against gates checked into
`src/test/resources/performance-gates.properties`, run by `mvn test -Pbench`. It has none of JMH's statistical rigour
and the throughput floors are set at roughly 40 percent of the reference measurement to match — low enough not to fail
on machine load, which means low enough to miss a fifteen percent drift. It catches the class of regression that
matters: a lookup added to the read path, a lock on a hot path, an fsync per operation.

The **structural gates are exact**, because a read count is deterministic, and they are the half that carries the
weight. TESTING's own rule below — that a change raising throughput while raising reads per operation must fail —
is only enforceable on that side, and it is.

The scenarios below remain the target for a real JMH suite.

| Scenario | Measures |
|----------|----------|
| `NodeInsert` | Single-threaded and N-threaded node creation throughput |
| `NodeReadById` | Random point reads, cold and warm cache |
| `EdgeInsert` | Lightweight and materialised edge insertion |
| `Traverse1Hop` | Neighbour enumeration by degree bucket (2, 10, 100, 1000, 10^6) |
| `TraverseBFS` | Depth-3 breadth-first over a scale-free graph |
| `PropertyReadWrite` | Property block read and update by block size |
| `MixedOltp` | 80/20 read/write mix at various concurrency levels |
| `BulkLoad` | Ingest rate for a 10^8-edge graph |
| `RecoveryTime` | Time to recover after a crash at a given WAL depth |

Every scenario reports the [SSD_IO.md §8](SSD_IO.md#8-measurement) metrics, not just ops/sec. The
gate is on **both**: a change that raises throughput while raising reads-per-operation fails.

**Baselines.** Record the PoC's numbers once, at `c016a41`, as the historical floor. Then record
each phase's numbers. Absolute targets to aim at, on a mid-range NVMe device, and where they stand:

| Operation | Target | Measured |
|-----------|--------|----------|
| Node read by id, warm | > 5M ops/s/core, 0 device reads | **9.0M single threaded, 280M across 32 threads; 1 record touch, 0 index probes** |
| Node read by id, cold | 1 device read, p99 under 200 us | **Not measured.** Needs cold-cache instrumentation and the direct-I/O engine (Phase 6) |
| Node insert, durable, group commit | > 200k ops/s | **17k.** Missed by an order of magnitude; group commit still fsyncs once per interval and does not batch across writers, because there is only one writer |
| Neighbour enumeration, degree 2 | 0 adjacency reads | **0**, gated |
| Neighbour enumeration, degree 4000 | 1 adjacency read | **1**, gated |
| Bytes per property-free edge | < 4 (both directions, amortised) | **2.0 to 2.5** |
| Bytes per node with two small properties | < 140 | **128** |
| Write amplification, mixed workload | < 3x | **1.70x**, gated |
| Recovery after 1 GiB of WAL | < 30 s | **Not measured.** Correctness is tested; speed is not |

Three of these are not met and are recorded as such rather than revised. The group commit target is
the substantive miss: reaching 200k durable inserts per second needs many writers sharing one fsync,
and there is one writer.

These are targets to design against and to revise with evidence, not promises.

---

## 8. Fixtures

`fixture/` provides deterministic, seeded generators — every test that uses randomness must be
reproducible from a printed seed:

| Generator | Shape |
|-----------|-------|
| `ChainGraph(n)` | A path; the worst case for traversal locality |
| `StarGraph(n)` | One supernode; exercises the dense adjacency tier |
| `ScaleFree(n, m)` | Barabasi-Albert; realistic degree distribution |
| `GridGraph(w, h)` | Predictable neighbours; easy to assert against |
| `RandomOps(seed, weights)` | A weighted random operation sequence for the reference-model harness |

Of these, `GraphModel` (the reference oracle) is built; the shape generators are not, and the
randomised operation sequence lives inside `ReferenceModelTest` rather than in a reusable
`RandomOps`. Plus a `TempStore` JUnit extension providing an isolated store directory per test,
deleted on success and **retained on failure** with its path printed — a corrupted store is the primary
diagnostic artefact and deleting it on failure is the most frustrating thing a test suite can do.

---

## 9. Conventions and Gotchas

- **No test writes to a shared path.** The PoC writes to `target/nodes.bin`, `target/rels.mmap` and
  friends from several test classes; `RelationshipStorageTest` even points `path` and `nodeRelPath`
  at the same file. Parallel execution would corrupt them.
- **A green test that cannot fail is worse than no test.** `GraphStorageTest#testBulkNode` calls
  `st.readNode` and discards the result with the assertion commented out.
- **Assert on structure, not on incidental values.** The property-count assertions in
  `GraphStorageTest` hold only because of a bug; assert what the API promises.
- **Seeds are printed, always.** A flaky property test that cannot be reproduced is noise.
- **Crash tests must verify the acknowledgement, not the intent.** See §6 point 3.
- **Benchmarks never run in the same JVM as correctness tests**, and never on a machine doing
  anything else.
- **The `vm.max_map_count` note in `GraphStorageTest` disappears with D1.** If a future test needs it
  again, that is a design regression, not an environment issue.

---

## 10. Progress Assessment

- [x] Test source-root reorganisation per §2 and JUnit tags
- [x] Maven profiles: default, `slow`, `crash`, `bench`, `all-tests`
- [x] `TempStore` extension with retain-on-failure
- [x] Seeded, deterministic randomised tests (plain JUnit; jqwik is not in the offline repository,
      so properties are expressed as seeded loops with the seed printed on failure)
- [x] `store.verify()` covering page checksums and slot accounting, with a corruption test that
      flips a byte on disk and asserts the failure
- [x] Baseline numbers recorded in [reports/BASELINE.md](reports/BASELINE.md)
- [x] Smoke benchmarks in `bench/`, excluded by default
- [x] The PoC's exploration tests deleted along with the code they exercised
- [x] `CompactionTest` and `CompactionCrashTest`: survivors re-found without any held id, held ids
      proven stale-or-correct but never misresolved, and a failure injected at every compaction batch
- [x] Reference-model differential harness (`GraphModel`, `ReferenceModelTest`), across rollbacks,
      crashes and a compaction
- [x] Reads-per-operation budgets and write amplification, enforced as build failures
- [x] Hostile and corrupt input against every format parser (`HostileInputTest`)
- [x] Concurrency scaling curve to 32 threads, with the bottleneck identified at each knee
- [x] **Snapshot isolation** (`...core.txn`, 43 cases): the visibility matrix across every kind of
      state the engine holds, the anomaly matrix at both levels *including the anomalies read-committed
      is supposed to permit*, reclamation and the retention budget, sixteen concurrent views against a
      continuous writer, rollback to byte-identical files, rollback-equals-recovery with a control, and
      a reader held across 60,000 commits
- [x] Soak harness with leak and growth detection, at a configurable duration
- [x] `kill -9` loop harness (`tools/kill9-loop.sh`, `CrashWorkload`) with acknowledgement checking
- [x] Performance gates in `-Pbench`, throughput floors and exact structural ceilings
- [x] Dual-run CI (native on/off), plus slow, crash, gates and a Rust build
- [ ] Graph fixture generators (chain, star, scale-free, grid)
- [ ] The remaining invariant checkers as standalone classes
- [x] `FaultInjectingIoEngine` and the crash matrix (lost, torn, corrupt, reordered and failing writes,
      and a failing fsync) — `CrashMatrixTest`, `WalFaultTest`
- [ ] A real JMH suite; the annotation processor is not available offline
- [ ] The twelve hour soak and a week of nightly crash loops, both executed
- [ ] The manual power-loss test, executed and recorded (procedure in [OPERATIONS.md §8](OPERATIONS.md#8-the-manual-power-loss-test))

---

## 11. Key Classes Reference (target)

| Class | Package | Purpose |
|-------|---------|---------|
| `TempStore` | `...core.fixture` | JUnit extension: isolated store dir, retain on failure |
| `GraphGenerators` | `...core.fixture` | Deterministic graph shapes |
| `ReferenceGraph` | `...core.fixture` | In-memory model for differential testing |
| `FaultInjectingIoEngine` | `...core.fixture` | Lost, torn, corrupt, reordered and failing I/O |
| `CountingIoEngine` | `...core.io` | Device-operation counters for IOP budgets |
| `InvariantChecker` | `...core.invariant` | Interface; nine implementations per §5 |
| `CrashMatrixTest` | `...core.crash` | Generates and runs the fault matrix |
| `VisibilityTest`, `SnapshotIsolationTest` | `...core.txn` | What a view may and may not see, and the anomaly matrix |
| `VacuumTest`, `LongRunningReaderTest` | `...core.txn` | Reclamation, the horizon, and what a long reader costs |

---

_Git HEAD revision: `364b5f7`_
_Last updated: 2026-08-14_
