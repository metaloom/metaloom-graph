# Phase Status

Where every phase of [PLAN.md](../PLAN.md) actually stands, and **why** each gap exists. The
checklist of what is done and what is left is [PROGRESS.md](../PROGRESS.md); this file is the
reasoning behind it, and it is deliberately blunt about what is not done.

---

## Summary

| Phase | State | Evidence |
|-------|-------|----------|
| 0 — Baseline and harness | **Done** | `mvn test`, `-Pbench`, [BASELINE.md](BASELINE.md) |
| 1 — Addressing and segments | **Done** | `ElementIdTest`, `GeometryTest`, `SegmentLifecycleTest` |
| 2 — Record stores and catalog | **Done** except `MAP`/`DURATION` value types | `ValueCodecTest`, `PropertyRoundTripTest` |
| 3 — Adjacency and traversal | **Done** except streaming cursors. Blocks are cell-based runs since [SCALE.md](SCALE.md) measured what a page-per-node cost | `AdjacencyTierTest`, `EdgeCodecTest`, `SpaceEfficiencyTest` |
| 4 — WAL, recovery, checkpoints | **Done** | `RecoveryTest` |
| 5 — Transactions | **Partial**: ACID, serialised writers, **snapshot isolation for readers** with a horizon, a retention budget and vacuum. **No concurrent writers** | `VisibilityTest`, `SnapshotIsolationTest`, `VacuumTest`, `ConcurrentReaderTest`, `RollbackTest`, `LongRunningReaderTest` |
| 6 — Native layer | **Done** but for `miri`/`fuzz` (uninstallable offline) and a second build target. io_uring, `O_DIRECT` with the log on it, `madvise`, `statx`, reflink, lz4 blobs, ABI layout assertions, in-jar packaging | `NativeCodecTest`, `UringIoEngineTest`, `DirectWalTest`, `BlobCompressionTest`, `StatxTest`, `PrefetchTest`, `NativeBindingsTest` |
| 7 — Indexes | **Done** for label and property B+trees, unique constraints included. The device-read exit criterion is measurable but not measurable *here* | `IndexTest`, `UniqueConstraintTest`, `DeviceReadBudgetTest` |
| 8 — Compaction and reclamation | **Done**, except the p99 latency budget, which needs Phase 9 | `CompactionTest`, `CompactionCrashTest`, `NativeReclaimTest` |
| 9 — Hardening | **Partial**: budgets, differential harness, hostile input, scaling, soak, `kill -9`, the crash matrix, gates, CI, operations doc. **No JMH, no executed power-loss test, long runs not run** | §6 below |
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

## 2. Phase 5 — What was built, and what the plan got wrong

Implemented: atomicity, durability, rollback, serialised writers, and **snapshot isolation for readers** —
`store.beginRead()` fixes what every read on that thread sees until it is closed, however many commits land
underneath. Readers do not block the writer and the writer does not block readers.

**The mechanism is not the one this repository's spec described**, and the substitution is the interesting part.
[TRANSACTIONS.md §5](../TRANSACTIONS.md) specified undo as 64-byte records in an on-disk `UNDO` segment, chained per
element through `version_ptr`, with `xmin` on each record resolving visibility. What was built is per-**page**
before-images held in memory, resolved at one choke point — `SegmentFile.readSlice`. Two reasons, and the first
decided it:

1. **Record-level undo cannot cover what a reader actually reads.** A traversal touches adjacency blocks; a lookup
   touches index pages; *every* read of anything consults the free-slot bitmap and the page-metadata array. None of
   those has an `xmin`, and giving each one its own visibility rule means the allocator, the B+tree, the catalog and
   all four stores each grow one. The sharpest case is in `VisibilityTest`: a view must still resolve a node whose
   slot has since been freed **and handed out again**, which is a question about the bitmap and the generation
   counter, not about the record.
2. **Undo does not need to be durable under no-steal.** Uncommitted data structurally cannot reach a data file,
   recovery is redo-only, and a crash leaves no live snapshots. Undo is needed exactly as long as the oldest open
   reader, which is a process-lifetime concern — so it costs no log record, no segment kind, and no format version
   bump. `xmin` and `version_ptr` stay in the layouts, written as zero.

**Several planned items turned out to have nothing to do**, which is different from being unfinished:

- `SERIALIZABLE` via SSI, and the `LockTable` of write intents. Writers are serialised, and a writer reads the
  current mapping rather than any view, so two writers never overlap and neither can decide on stale input. Write
  skew and lost updates are therefore unreachable — asserted under real concurrency in `SnapshotIsolationTest`, not
  argued. SSI detects dangerous structures between concurrent writers; there are none.
- The `xmin` placeholder scheme and its recent-commit map. There is no window in which a commit is numbered but not
  observable: under no-steal a commit becomes visible at exactly one instant, when its pages are applied. The
  in-flight set the spec's visibility rule needs does not exist either.

**What the plan got right, and it bit.** "Version growth under update-heavy workloads is the classic MVCC failure
mode" — reclamation originally walked every chain on every commit to discover there was nothing to cut, which is
quadratic while a view is pinned and made a 60,000-write test not finish. Each chain now carries the LSN of its
oldest entry, so vacuum declines in one comparison.

**Still missing: concurrent writers**, and this is the honest reason. Page overlays resolve two writers on one page
by last-writer-wins across the whole page, losing each other's records. Fixing that needs record-granular overlays,
or per-writer allocation arenas plus commit-time validation — a rewrite of the write path rather than an addition to
it. It remains what gates the group-commit throughput target in §5: one fsync amortised across time instead of
across writers, because there is one writer.

**Measured cost.** Nothing is retained while no view is open, so a write-only or read-committed workload pays
nothing. With a view open across 60,000 property updates: 200.6 MiB peak retained over 990,899 entries on 392 pages,
against 192 KiB actually rebuilt for the reader. That gap names the next optimisation — a page every commit touches
collects one entry per commit when only the oldest can be needed — and it is recorded rather than taken, because the
rule has to be exactly right or a reader gets a silently wrong page, and the retention budget already bounds the
damage.

## 3. Phase 6 — What is native and what is not

`NativeLib` reaches libc through FFM downcalls and provides **hole punching** (`fallocate` with
`FALLOC_FL_PUNCH_HOLE`), which the JDK cannot express at all. `NativeReclaimTest` proves it returns real blocks to
the filesystem.

The Rust crate (`native-rust-lib/`) is written and now **compiled and verified**. `NativeCodecTest` loads
`libmetaloom_graph_native.so` through the FFM linker and proves the two codecs are byte-identical to the Java
reference in both directions, and that a truncated block is rejected rather than over-read. io_uring is not written
at all.

**The N4 measurements still argue against shipping the hot loops, and one of them had to be corrected:**

| Hot loop | Java | Rust | Verdict |
|---|---|---|---|
| Edge codec, 1000 entries | 6,882 ns | 4,978 ns | **1.38x** faster, boundary crossing included |
| CRC32C, 8 KiB page | 113 ns | 886 ns | **7.8x slower** |
| lz4, 64 KiB of document-shaped data | no JDK equivalent | 26.9 percent of original | **3.7x smaller** |

**The earlier "200x slower" CRC figure was measuring the wrong thing and has been withdrawn.** The crate computed
CRC32C with a bitwise loop while `java.util.zip.CRC32C` uses the hardware CRC32 instruction; that comparison said
something about the two implementations and nothing about the two languages. The crate now uses the `crc32c` crate,
which uses the same instruction — the comparison spec/NATIVE_FFM.md section 2.1 asks for by requiring hashes come
from maintained crates. Rust is **still slower**, by 7.8x rather than 200x, and the residue is the FFM downcall
against an intrinsic the JIT inlines. The conclusion is unchanged; the number was wrong and is now right.

**io_uring is a different story, and it is the one that pays:**

| 64 scattered 4 KiB reads, `O_DIRECT` | Time |
|---|---|
| `pread` loop | 3,103 µs |
| **io_uring batch** | **250 µs** |
| | **12.4x** |

That is the claim in section 1 — "the single largest performance lever in the design" — measured and upheld. It is
also the only capability on the list where the JVM has no answer at all: a page fault and a `FileChannel.read` are
both synchronous, so eight pages cost eight round trips whatever the language above them.

Nothing depends on any of it: every capability has a pure-Java fallback and the engine runs entirely on the Java path.

**The libc half of the native case is now built, and it did not need the crate.** `O_DIRECT`, `pread`, `pwrite`,
`fsync` and `posix_fadvise` are libc calls, so FFM reaches them directly and the Rust crate would have added a build
dependency and nothing else:

| Capability | Built | What it bought |
|---|---|---|
| `IoEngine` / `MmapIoEngine` / `DirectIoEngine` | yes | The seam deferred since Phase 1, and equivalence between the engines is now a test rather than an assumption |
| `O_DIRECT` open, aligned allocation | yes | Available to the log; **the log is not yet on it**, see below |
| `posix_fadvise(DONTNEED)` | yes | Cold-read measurement without root |
| `PageFaults` (`/proc/self/stat`) | yes | The only honest device-read counter on a mapped data path |
| Capability negotiation | yes | A wrong-ABI library is refused rather than trusted |
| **io_uring, `UringIoEngine`** | **yes** | **12.4x on a batch of scattered reads. The capability the phase was waiting for** |
| xxh3, lz4, hardware CRC32C | yes | lz4 compresses documents 3.7x; the hashes remain unused and now say so with the right number |
| SIMD codec paths, `madvise`, `statx`, reflink | no | Not written |

Moving the WAL onto the direct engine is deliberately not done. Log records are neither aligned nor block-sized, so it
needs an aligned block buffer and a padding record type — and a partly filled block must never be rewritten in place,
because a torn write there destroys an already-acknowledged commit. That is a WAL format change, and doing it
carelessly would trade a page-cache saving for the durability guarantee the whole engine rests on.

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

The deliverable of this phase was supposed to be confidence. What it produced was **fifteen defects**, which is a
more useful outcome and worth recording by where they came from:

| Found by | Defects |
|----------|---------|
| Hostile input against the format parsers (`HostileInputTest`) | 7 |
| The reference-model differential harness (`ReferenceModelTest`) | 2 |
| The concurrency scaling curve (`ScalingTest`) | 2 |
| The reads-per-operation budgets (`ReadBudgetTest`) | 3 |
| The executable usage example (`UsageExampleTest`) | 1 |

Every one came from comparing the engine against something outside it — a naive model, a counter, a corrupt file, a
thread count, the documented usage — rather than from a test asserting what its author already believed.

The last one is the cheapest test in the project and found a defect no other one had: writing the documented example
as a running test, in the order a *caller* meets the API rather than the order the engine was built in. A rolled-back
first transaction left the in-memory pointer to the index catalog page naming a slot whose allocation had been
discarded, so every indexed lookup afterwards threw `StaleIdException` — a corruption signal, on a healthy store.
Nothing in the suite had rolled back the very first transaction against a fresh store, because no test written from
the inside would think to.

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
- **The fault-injection crash matrix is built** — level 2 of
  [TESTING.md §6](../TESTING.md#6-crash-and-fault-injection), which was previously the largest gap here. Four of the
  six device faults are disk states and `CrashMatrixTest` produces each at every record in the log; two are behaviours
  that no file can express — a failing write, and a write followed by a failing fsync — and `WalFaultTest` injects
  those through `FaultInjectingIoEngine`. At 24 transactions: **2,394 cases over 404 records, and not one store failed
  to open.** Every damaged log degraded to a shorter prefix instead of becoming unreadable, which is what the CRC plus
  self-identifying LSN was designed for and had never been shown to do. It found one engine defect: recovery against
  data files older than its log threw a raw `IllegalStateException`, and now raises `PageCorruptException`.
  `WalFaultTest` also records the fault nothing can defend against — a device that reports writes it never performed
  loses the log entirely — which is the concrete argument for performing the manual power-loss test below.
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

_Git HEAD revision: `364b5f7`_
_Last updated: 2026-08-14_
