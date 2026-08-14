# Phase Status

Where every phase of [PLAN.md](../PLAN.md) actually stands. The per-file progress checkboxes are the detail; this is
the summary, and it is deliberately blunt about what is not done.

---

## Summary

| Phase | State | Evidence |
|-------|-------|----------|
| 0 — Baseline and harness | **Done** | `mvn test`, `-Pbench`, [BASELINE.md](BASELINE.md) |
| 1 — Addressing and segments | **Done** | `ElementIdTest`, `GeometryTest`, `SegmentLifecycleTest` |
| 2 — Record stores and catalog | **Done** except `MAP`/`DURATION` value types | `ValueCodecTest`, `PropertyRoundTripTest` |
| 3 — Adjacency and traversal | **Done** except streaming cursors | `AdjacencyTierTest`, `EdgeCodecTest`, `SpaceEfficiencyTest` |
| 4 — WAL, recovery, checkpoints | **Done** | `RecoveryTest` |
| 5 — Transactions | **Partial**: ACID with single-writer serialisable isolation. **No MVCC.** | `RecoveryTest`, `InvariantTest` |
| 6 — Native layer | **Partial**: libc via FFM works; the Rust crate is written but unbuilt | `NativeReclaimTest` |
| 7 — Indexes | **Done** for label and property B+trees | `IndexTest` |
| 8 — Compaction and reclamation | **Done**, except the p99 latency budget, which needs Phase 9 | `CompactionTest`, `CompactionCrashTest`, `NativeReclaimTest` |
| 9 — Hardening | **Partial**: budgets, differential harness, hostile input, scaling, soak, `kill -9`, gates, CI, operations doc. **No JMH, no crash matrix, no executed power-loss test** | §6 below |
| 10 — MetaLoom integration | **Built and measured**; recommendation is not to adopt | §7 below |

---

## 1. Phase 4 — What was built

**No-steal, force-at-commit redo logging.** A writer never touches the mapped file: the first write to a page copies
it into a private overlay, and at commit the redo records are fsynced *before* the copies are applied. Uncommitted
data is therefore structurally incapable of reaching the data files, which is why recovery is redo-only and there is
no undo pass.

This is a deliberate departure from the ARIES steal/no-force design in [WAL.md](../WAL.md). The trade:

| | Spec'd (steal, no-force) | Built (no-steal, force-at-commit) |
|---|---|---|
| Recovery | analysis, redo, undo | redo only |
| Transaction size | unbounded | bounded by memory |
| Rollback | undo records | discard the overlay, free |
| Uncommitted data on disk | possible, undone at recovery | impossible |
| Correctness under mmap | needs control over when pages are written, which mmap does not give | not needed |

The last row is why. With a `MAP_SHARED` mapping the kernel may write a dirty page at any moment, so a steal policy
would need an undo record to be durable before a page the engine does not control could reach the device — a
guarantee mmap cannot provide. Making uncommitted data unreachable sidesteps the problem entirely. Revisiting this
requires the direct-I/O engine, and the spec has been updated to say so.

**Two real bugs the recovery tests caught**, both of which would have silently corrupted a store:

1. After a replay, the in-memory superblock still pointed at the *old* A/B copy, so the free-slot bitmap reflected the
   recovered allocations while the counters did not. Fixed by re-picking the active copy by sequence after recovery
   (`Superblock.selectActive`).
2. The dictionary was fsynced on every commit even when nothing had been interned, costing an fsync per commit for
   nothing. Group-commit throughput went from 911 to 17,651 ops/s once that was conditional.

## 2. Phase 5 — What is missing

Implemented: atomicity, durability, rollback, and **single-writer serialisable** isolation — one writer at a time, any
number of concurrent readers, and a reader never observes half of a commit (the apply step holds a write latch).

Not implemented, and not pretended to be:

- **MVCC.** No version chains, no undo segments, no `xmin` visibility checks. The `version_ptr` and `xmin` fields
  exist in the record layouts and are written as `NULL`/0.
- **Concurrent writers.** They serialise on one lock rather than conflicting and aborting.
- **Snapshot isolation** and `SERIALIZABLE` via SSI. A long-running reader sees commits as they land, not a fixed
  snapshot.
- **Vacuum.** Nothing reclaims old versions, because there are none.

For a single-writer embedded store this is a coherent and useful point to stop at. For concurrent writers it is not,
and [TRANSACTIONS.md](../TRANSACTIONS.md) remains the design to build.

## 3. Phase 6 — What is native and what is not

`NativeLib` reaches libc through FFM downcalls and provides **hole punching** (`fallocate` with
`FALLOC_FL_PUNCH_HOLE`), which the JDK cannot express at all. `NativeReclaimTest` proves it returns real blocks to
the filesystem.

The Rust crate (`native-rust-lib/`) is written — ABI surface, CRC32C, the edge codec with a matching test — and the
Maven build invokes `cargo` when it is present and prints a skip message when it is not. **It has never been
compiled**: `cargo` is not installed in this environment, so the crate is unverified source. io_uring is not written
at all.

Nothing depends on it: every capability has a pure-Java fallback and the engine runs entirely on the Java path today.

## 4. Phase 8 — What was built

Both halves of space reclamation now exist, and they are not interchangeable.

**Hole punching**, the cheap half: `store.reclaim()` punches the blocks of every all-free page. No id breaks, because
the pages stay addressable and read back as zeros, which is a valid empty page (invariant I6). Prefer it.

**Compaction**, the expensive half: `store.compact()` relocates live records downwards into the free slots below them,
rewrites every reference to them, frees the sources, and then pulls each segment's high water mark down to its topmost
live slot. A store churned to a tenth of its slots comes back to 0.947 liveness.

Two deliberate departures from [FILE_FORMAT.md](../FILE_FORMAT.md) section 9 as it was originally written, both now
documented there in section 9.1:

| | Originally specified | Built |
|---|---|---|
| Destination | a freshly allocated segment | the lowest free slot below the record |
| Forwarding map | on disk, in a temporary index segment | in memory, for the life of one batch |
| Reader fallback | `StaleIdException` resolves through the map while a compaction is in flight | none |
| Atomicity | per step, WAL-logged and idempotent | one transaction per batch |

Relocating downwards is what actually reclaims space: the high water mark, not the live count, is what bounds
`storageBytes` and every full scan. And making a batch atomic — which the no-steal log gives for free — removes the
need for both an on-disk map and the reader fallback, which preserves the property the whole design rests on. There is
**no indirection table anywhere in the engine**, not even a bounded one.

**Two bugs this work surfaced**, both of which existed before compaction and both of which could corrupt a store:

1. **The free ring could hand out an already-allocated slot.** The in-memory ring is documented as a cache in front of
   the free slot bitmap, but `allocate()` trusted it blindly. Compaction allocates straight from the bitmap, so a slot
   it claimed was still sitting in the ring and got handed to a second record — two records at one id. Both ring paths
   now re-check the bitmap, and `claim()` throws rather than overwriting if a slot is already taken. Without that
   tripwire the failure showed up much later, as a superblock and bitmap disagreeing about a live count.
2. **`mvn test -Pbench` ran no benchmarks at all.** Surefire's default class-name patterns cover `*Test` and `Test*`,
   so `BulkBenchmark` was silently skipped; the benchmarks only ever ran when named explicitly with `-Dtest`.

**What is not done**: the exit criterion "foreground p99 latency degrades by less than a configured budget during
compaction". Compaction takes the writer lock one batch at a time rather than for the whole run, and `CompactionTest`
shows a concurrent writer completing throughout one, but a p99 claim needs Phase 9's JMH harness.

## 5. Phase 9 — What was built, and what it found

The deliverable of this phase was supposed to be confidence. What it produced was **fourteen defects**, which is a
more useful outcome and worth recording by where they came from:

| Found by | Defects |
|----------|---------|
| Hostile input against the format parsers (`HostileInputTest`) | 7 |
| The reference-model differential harness (`ReferenceModelTest`) | 2 |
| The concurrency scaling curve (`ScalingTest`) | 2 |
| The reads-per-operation budgets (`ReadBudgetTest`) | 3 |

Every one came from comparing the engine against something outside it — a naive model, a counter, a corrupt file, a
thread count — rather than from a test asserting what its author already believed.

### 5.1 The scaling finding

Read throughput at 32 threads was **4.4 million operations per second**. It is now **280 million**. The engine did not
get faster; two things were removed:

1. **`Dictionary.resolve` was `synchronized`.** Every `nodeLabel` call resolves a label id through it, so a single
   global monitor sat on the hottest read path. The table is append-only — an id never changes meaning — so reads need
   no lock at all: a volatile array reference and an index.
2. **A shared `counter++`**, added three hours earlier to make the reads-per-operation budgets measurable. A plain
   increment on a shared field is a write to a shared cache line; from every reader thread at once it cost more than
   the lock next to it. `LongAdder` spreads it across padded cells.

A third change, replacing the `ReentrantReadWriteLock` on the read path with an optimistic `StampedLock` stamp, was
worth 1.4x at two threads. Acquiring a read lock *writes* to a shared counter, so a lock taken only to exclude a rare
event was making the common case slower as threads were added.

The lesson is not about locks. It is that **the bottleneck was invisible until a curve was plotted**, and two of the
three causes looked entirely harmless in review.

### 5.2 The hostile-input findings

A store file is untrusted input: it can be corrupted by a device, truncated by a full disk, or written by someone
else. Seven defects, all of the same two shapes:

- **A length field read out of a page and used to allocate.** A property block's `used_bytes` (an out of bounds read),
  its `cell_count` (which `free` would have used to release 65,535 slots belonging to other blocks), an adjacency
  block's `sorted_count` (a `NegativeArraySizeException` from an integer overflow), and a blob's `total_length`
  (a two gigabyte allocation from one flipped byte).
- **A pointer followed without a bound.** An adjacency chain and a blob chain, each one flipped byte away from a
  process that never returns.

Plus corrupt pointers escaping as `IllegalArgumentException` from `ElementId.expect`, which meant a caller could not
distinguish a damaged store from its own mistake.

The contract is now explicit and tested: reading a corrupt store either returns correct data or throws one of the
engine's own exceptions; it never reads outside the mapping, never allocates from an unvalidated field, and always
terminates. Every case is wrapped in a hard timeout, because "the test hangs" is the failure being tested for.

### 5.3 What is measured and what is not

| Target ([TESTING.md §7](../TESTING.md#7-benchmarks)) | Result |
|---|---|
| Node read by id, warm: > 5M ops/s/core, 0 device reads | **Met.** 9.0M single threaded, 280M across 32 threads, 1 record touch, 0 index probes |
| Neighbour enumeration, degree 2: 0 adjacency reads | **Met**, gated exactly |
| Neighbour enumeration, degree 4000: 1 adjacency read | **Met**, gated exactly |
| Bytes per node with two small properties: < 140 | **Met**, 128 |
| Bytes per property-free edge: < 4 | **Met**, 2.0 to 2.5 |
| Write amplification, mixed workload: < 3x | **Met**, 1.70x against a structural floor of 2 |
| Node insert, durable, group commit: > 200k ops/s | **Missed by an order of magnitude**, 17k |
| Node read by id, cold: 1 device read, p99 < 200 us | **Not measured** |
| Recovery after 1 GiB of WAL: < 30 s | **Not measured** |

The group commit miss is structural rather than a tuning problem. Group commit amortises one fsync across *time*;
reaching 200k durable inserts per second means amortising it across *writers*, and there is one writer. It moves when
Phase 5 does. The two unmeasured rows both need cold-cache instrumentation and the direct-I/O engine of Phase 6.

## 6. Phase 9 — What is missing

- **No JMH.** Its annotation processor is not in the offline Maven repository, so a JMH suite cannot be built here.
  `PerformanceGateTest` is a warmup-measure-compare substitute with throughput floors set at roughly 40 percent of the
  reference measurement — low enough not to fail on machine load, therefore too low to catch a small drift. The
  structural gates beside it are exact and are what carry the weight.
- **No fault-injection crash matrix.** `FaultInjectingIoEngine` — lost writes, torn writes, reordered writes, a
  successful write followed by a failing fsync — is not built. The crash testing that exists is level 1 and level 3 of
  [TESTING.md §6](../TESTING.md#6-crash-and-fault-injection): abandon-and-reopen, and `kill -9`. Level 2 is the gap,
  and it is the level that would catch a device lying about ordering.
- **The long runs have not been run.** The soak harness asserts leak, growth and fragmentation bounds and has been run
  for 25 seconds, not 12 hours. The `kill -9` loop ran 8 iterations clean, not a week of nightlies. Both are one
  command; neither has been executed at length.
- **The manual power-loss test is written and untested.** [OPERATIONS.md §8](../OPERATIONS.md#8-the-manual-power-loss-test)
  is a full procedure, down to checking whether the device's write cache is honest. It has not been performed, which
  means the assumption underneath every durability claim in this repository is documented but unverified.
- **No backup mechanism**, which [OPERATIONS.md §5](../OPERATIONS.md#5-backup-and-restore) states plainly rather than
  working around. Cold copy with the process stopped is the only supported procedure.
- **The API is not frozen**, and [reports/API_REVIEW.md](API_REVIEW.md) recommends against freezing it: MVCC, cursors
  and backup would each change the surface. Four defects the review found are fixed; the rest are recorded.

## 7. Phase 10 — MetaLoom integration

**Built, measured, and the recommendation is: do not adopt.** That is a result, not a failure to reach one.

The MetaLoom side of this lives in
[metaloom/spec/features/graph/ASSET_GRAPH.md](../../../metaloom/spec/features/graph/ASSET_GRAPH.md); this section
records what it means for the engine.

### 7.1 What was built

The candidate identified in the earlier assessment — the asset relationship graph — was prototyped exactly as the plan
required: behind an interface, alongside Postgres, with a differential check.

| Deliverable | Where |
|---|---|
| `AssetGraphIndex` SPI, mirroring MetaLoom's `VectorIndex` contract | `loom-shared/api/.../api/graph/` |
| Implementation over this engine | `loom/services/graphstore/.../graph/store/GraphStoreAssetGraphIndex.java` |
| `NoopAssetGraphIndex` and a boot-safe Dagger binding | `loom/services/graphstore/`, `loom/core/.../dagger/` |
| Differential check against a real Postgres | `AssetGraphDifferentialTest`, 6 cases |
| Benchmark at three scales | `AssetGraphBenchmarkTest` |

23 tests, green. `LOOM_ASSET_GRAPH_PROVIDER` defaults to `none`; nothing in MetaLoom calls the SPI in production.

### 7.2 The measurement, and why the first version of it was wrong

| assets / edges | naive SQL | tuned SQL | this engine | vs naive | vs tuned |
|---|---|---|---|---|---|
| 1,000 / 8,000 | 1,684 us | 388 us | 165 us | 10.2x | **2.4x** |
| 10,000 / 80,000 | 14,204 us | 303 us | 127 us | 111.6x | **2.4x** |
| 50,000 / 400,000 | 126,755 us | 456 us | 193 us | 657.1x | **2.4x** |

The first version of this benchmark measured only the naive query — a CTE unioning all four link tables, self-joined.
A CTE referenced twice is materialised, so it scans every link row on every call, and its cost grows linearly with the
graph: 1.7 ms to 127 ms across a 50x increase. Against that, this engine looked **657x faster**, and that number would
have been very easy to report.

The tuned query pushes the asset filter into each branch first and joins on primary key leading columns. It is flat:
388, 303, 456 microseconds across the same 50x increase, no trend. Against that, this engine is **2.4x faster and
stays 2.4x faster**.

Postgres is not structurally disadvantaged at this query. The obvious way of writing it is.

### 7.3 The recommendation

**Do not adopt.** 2.4x on a query taking 0.3 ms does not pay for a second copy of every relationship, a projector to
maintain it, an orphan sweep, and a backend with one writer and no backup.

What the phase did establish is worth more than the throughput number:

- **The fit is real.** The traversal returns byte-identical answers to both SQL formulations, across insertions,
  deletions, a full rebuild and a compaction. There is no impedance mismatch between this engine's model and a DAM's.
- **The integration shape works.** Everything is uuid-addressed, so compaction — the one operation that invalidates
  ids — cannot reach a caller. Writes batch, so the single-writer limit never touches the pipeline. The binding cannot
  fail boot. Every objection raised in the earlier assessment is answered by the *index* framing rather than by
  changes to the engine.
- **The engine's own limits were not what stopped it.** Not MVCC, not the commit rate, not the backup gap. What
  stopped it is that the query it was going to accelerate is already fast enough.

**Revisit when** the union grows past six or seven branches, when assets routinely carry high-degree intermediates
(where the tuned SQL's flatness breaks down), or when the question changes shape — three hops, or path-finding
between two assets, which SQL expresses badly and a traversal expresses naturally. That last one is the most likely
trigger and the least like the query measured here.

**Not published** to the MetaLoom Maven repository, because nothing depends on it. It is installed to the local
repository so the module builds.

### 7.4 What changed in MetaLoom

One new module, one new SPI, one Dagger binding, one spec file, and a row in the spec router. Nothing existing was
modified except `LoomOptions` (one nested option), `LoomCoreComponent` (one module), and two `pom.xml` module lists.
No schema change, no route, no behaviour change with the default configuration.

---

_Git HEAD revision: `c016a41` (plus the Phase 0 to 10 working tree)_
_Last updated: 2026-08-13_
