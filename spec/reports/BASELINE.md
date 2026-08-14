# Baseline Report

Measured numbers, recorded as each phase lands. Required by [PLAN.md](../PLAN.md) rule 4: a phase with no
measurement is a phase with no evidence.

Machine: Linux 6.19, Temurin 25, AMD Ryzen 9 9900X (12 cores, 24 threads), ext4 on NVMe. Warm cache. These are timing
loops, not JMH: no forks, no warmup control beyond one pass, no confidence intervals, so the read microbenchmarks vary
by a factor of two between runs. **Each benchmark below was run in its own JVM** (`-Dtest=BulkBenchmark#<method>`);
running the whole suite in one fork lowers the write numbers by roughly a third through heap pressure alone. JMH itself
is unavailable offline — see [PHASE_STATUS.md §6](PHASE_STATUS.md#6-phase-9--what-is-missing).

---

## 1. Phases 0 to 9 (this build)

### 1.1 Throughput

| Operation | Rate | Note |
|-----------|------|------|
| `nodeLabel` by id, single threaded | 9,014,000 ops/s | the addressing invariant: no index probe, no seek |
| `nodeLabel` by id, **32 threads** | **280,000,000 ops/s** | see §1.2; this was 4,400,000 before Phase 9 |
| `nodeProperties` (full block decode) | 5,421,000 ops/s | one block read, all properties |
| `createNode`, batched in one transaction | 309,000 ops/s | includes index maintenance |
| `createNode`, **one durable commit each** | **278 to 980 ops/s** | one fsync per commit; varies with device load more than anything else here |
| `createNode`, group commit at 20 ms | 16,900 ops/s | bounded loss window, never corrupt. **Target was 200k; see below** |
| `createEdge` (lightweight), batched | 1,189,000 ops/s | both endpoints updated |
| `createEdge` into a supernode | 1,167,000 ops/s | 500,000 edges on one node |
| `neighbours`, degree 1 | 2,673,000 ops/s | zero adjacency reads, edge is inline |
| supernode scan, 500,000 neighbours | 29 ms | 62 blocks decoded |
| `nodesByProperty` (B+tree) | 441,000 ops/s | over 100,000 indexed nodes |
| `nodesByLabel`, 50,000 matches | under 1 ms | leaf-chained range scan |
| `checkpoint` of a 200,000 node store | 63 ms | |

**Durable commit rate is still the number to look at.** A few hundred commits per second is one fsync each on this
device, and it is why the API exposes explicit transactions: batching a load into one transaction is three orders of
magnitude faster.

**`GROUP_COMMIT` misses its target by an order of magnitude and that is structural.** 17k against a 200k goal. Group
commit amortises one fsync across *time*; the way databases reach hundreds of thousands of durable inserts a second is
by amortising it across *writers*, queueing many committers behind one fsync. There is one writer, so there is nothing
to queue. This number moves when MVCC does, and not before.

### 1.2 Read scaling

Point reads by id, 50,000 node store, threads sharing one open store:

| Threads | Before Phase 9 | After | |
|---------|---------------|-------|---|
| 1 | 21,700,000 ops/s | 27,300,000 ops/s | 1.00x |
| 2 | 11,100,000 | 58,800,000 | 2.15x |
| 4 | 6,500,000 | 92,300,000 | 3.38x |
| 8 | 5,400,000 | 222,400,000 | 8.14x |
| 16 | 4,800,000 | 253,800,000 | 9.29x |
| 32 | 4,400,000 | **279,800,000** | **10.24x** |

Reads used to get **slower** as threads were added. Three causes, in the order they were found and removed:

1. **`Dictionary.resolve` was `synchronized`** — a single global monitor on the path every `nodeLabel` call takes. The
   table is append-only, so reads need no lock: a volatile array reference and an index.
2. **A shared `counter++`** on the record read path, added earlier in Phase 9 to make the reads-per-operation budgets
   measurable. A plain increment on a shared field is a write to a shared cache line, and from every reader at once it
   cost more than the lock beside it. Now a `LongAdder`.
3. **A `ReentrantReadWriteLock`** taken on every read to exclude the rare commit apply step. Acquiring a read lock
   writes to a shared counter. Now an optimistic `StampedLock` stamp, validated after the fact and retried under a
   real lock only when an apply actually happened — worth 1.4x at two threads.

Writes are flat by design: 300 to 1,000 ops/s at any thread count, because there is one writer lock and no MVCC. The
knee is at two threads and it is where the design says it should be.

### 1.3 Reads per operation

Exact, and enforced as build failures by `ReadBudgetTest` and the gates in `-Pbench`:

| Operation | NODE | PROP | ADJ | INDEX |
|-----------|------|------|-----|-------|
| `nodeLabel(id)` | 1 | 0 | 0 | 0 |
| `nodeExists(id)` | 1 | 0 | 0 | 0 |
| `nodeProperties(id)` | 1 | 1 | 0 | 0 |
| `neighbours`, degree 2 | 1 | 0 | **0** | 0 |
| `neighbours`, degree 4000 | 1 | 0 | **1** | 0 |
| `nodesByProperty`, one hit | 2 | 1 | 0 | about 2x tree depth |

The zeros are the design claim, and they are what a change adding a lookup to the read path would break. Three
redundant record reads were removed in Phase 9 because these budgets made them visible: the generation check
re-acquired the record it was about to return, and a property block was acquired twice, once for its length and once
for its payload.

### 1.4 Write amplification

Bytes written per byte of record payload, from `WriteAmplificationTest`. The floor is 2: a no-steal log writes every
change once to the log and once to the data file.

| Workload | Amplification |
|----------|---------------|
| Bulk load, one transaction | 1.97x |
| Mixed 80/20 read/write | **3.50x** — see note |
| One node per transaction | 8.03x |

The last row is not a failure; it is the cost of per-operation durability, measured so that the batching advice in
[OPERATIONS.md §3](../OPERATIONS.md#3-tuning) is a number rather than an opinion.

**The mixed figure used to read 1.70x, and the change was in the denominator rather than the engine.** Amplification is
measured against `recordBytes()`, which counts *allocated* record space, and an adjacency block used to be a whole
8 KiB page from the third edge onwards — so a workload that promoted a few hundred nodes credited itself with megabytes
of "payload" holding a few thousand edges. With 512 byte cells the denominator halved and the ratio doubled, while the
bytes written moved by less than half a percent: **1,458,816 before against 1,452,282 after**, same workload, only the
cell size changed. The gate for this workload is 4x and the reason is page-image granularity — 20,000 operations
scattered over 5,000 nodes means almost every 64 byte write carries an 8 KiB page into the log.

### 1.5 Compaction

200,000 nodes with one property each, churned down to every tenth node, then `compact(CompactionOptions.always())`:

| Measurement | Value |
|-------------|-------|
| **Liveness** | **0.105 -> 0.947** |
| Records relocated | 36,000 (18,000 nodes, 18,000 property blocks) |
| Batches (one commit each) | 17 |
| Slots the high water marks came down by | 360,009 |
| Pages punched | 4,348 |
| Index pages given back by the rebuild | 2,476 |
| Committed bytes | 66.1 MB -> 49.5 MB (25.1 percent) |
| Wall clock | 485 ms, so about 74,000 relocations/s |
| `verify(FULL)` over the result | 48 ms for 20,000 live nodes |

The Phase 8 exit criterion was "a store churned to 10 percent liveness compacts to over 90 percent". 0.105 to 0.947
meets it.

The 25 percent figure for committed bytes understates the record-level win, and it is worth knowing why.
`storageBytes` counts, per segment, the free slot bitmap and page metadata array in full plus everything below the
high water mark. Those two regions are sized for the segment's whole slot capacity, so with the 4 GiB default segment
size they are a fixed floor of roughly 6 MB per segment regardless of how much data is in it. The record regions
themselves shrank by the 360,009 slots the marks came down by. `slotsReclaimed` is the honest measure of what
compaction recovered; `storageBytes` is the honest measure of what the store costs.

### 1.5a Device reads

`DeviceReadBudgetTest` counts **major faults** — faults the kernel had to serve from the device —
around an operation on a store whose pages have been evicted. It is the only unit in which the
"1 device read" and "at most 4 device reads" targets are stated, and until the Phase 6 I/O engine
landed nothing could count it.

**It cannot be taken on this machine, and the test skips rather than reporting a number.**
`posix_fadvise(POSIX_FADV_DONTNEED)` returns success on this overlay filesystem and evicts nothing:
touching 16,384 pages of a freshly evicted 64 MiB file produced **one** major fault. An earlier
version of the test believed the success code and reported 0.15 device reads per cold node read,
which is not a good result — it is a warm cache with a cold label. The test now proves eviction works
before trusting anything downstream of it.

On a filesystem that honours the advice (ext4, xfs) the three assertions are ≤ 1 device read for a
node read by id, ≤ 4 for an indexed lookup, and ≤ 1 for a degree-two traversal.

### 1.6 Space

| Measurement | Value |
|-------------|-------|
| Node with two small properties | **128 bytes** (64 node + 64 property block) |
| Secondary index cost, same workload | 133 bytes per node (one label entry + two property entries) |
| Directed adjacency entry, 20,000 edge hub | **1.23 bytes** |
| Directed adjacency entry, 500,000 edge hub | **1.02 bytes** (8065 entries per 8 KiB block) |
| Directed adjacency entry, degree 3 | **170 bytes** (one 512 byte cell) — was 2,730, one 8 KiB page |
| Directed adjacency entry, degree 400 | **1.28 bytes** (still one 512 byte cell) |
| Adjacency blocks for a chain graph (degree <= 2) | **0** — every edge stays inline |
| Relationship records for property-free edges | **0** |

The index is a deliberate space-for-lookup trade and is reported separately; index maintenance is currently
unconditional, and making indexes opt-in is open work.

### 1.7 Against the proof of concept at `c016a41`

Structural comparison, computed from the old layouts in [CURRENT_STATE.md](../CURRENT_STATE.md) section 2. The old
timings were not re-measured.

| Measurement | PoC | Now | Factor |
|-------------|-----|-----|--------|
| Node record | 1080 B | 64 B | 16.9x |
| Node plus two properties | ~1144 B | 128 B | 8.9x |
| Directed adjacency entry | 32 B | 1.0 to 1.23 B | 26x to 32x |
| Reads to enumerate d neighbours | d | 0 for d <= 2, `ceil(d/8000)` otherwise | — |
| Memory mappings for N record accesses | N | 1 per segment | — |
| Durability | none | fsync before any data page is touched | — |
| Space returned after mass deletion | none | hole punching, plus compaction to over 94 percent liveness | — |

### 1.8 Soak

25 seconds, 584,500 operations, live set held near 4,000 nodes and a stable edge count, compacting and verifying every
20 rounds:

| | Bytes per live element | Segment files | Heap after GC |
|---|---|---|---|
| Settled (round 100) | 1,240 | 5 | 5.8 MB |
| Final | 1,295 | 5 | 5.8 MB |

Flat. **The twelve hour run has not been performed**; `-Dsoak.seconds=43200` is the command.

### 1.9 Test suite

```
mvn test           240 tests, 0 failures, ~40 s
mvn test -Pslow    adds the scaling curve and the soak
mvn test -Pbench   adds the benchmarks and the performance gates
tools/kill9-loop.sh 20 3     8 iterations run clean
```

Both the native-enabled and native-disabled runs pass, which is what CI checks.

`mvn test -Pbench` did not previously run anything extra: surefire's default class-name patterns cover `*Test` and
`Test*` only, so `BulkBenchmark` was silently skipped and the benchmarks only ever ran when named explicitly with
`-Dtest`. The pom now includes `*Benchmark.java`, which is why the counts differ.

---

## 2. What These Numbers Do Not Show

- **Warm cache only.** Every benchmark fits in RAM, so it measures the engine, not the device. Cold-cache and
  larger-than-RAM numbers need the io_uring engine to be meaningful. The engine now exists and measures 12.4x on a
  batch of scattered reads ([../NATIVE_FFM.md](../NATIVE_FFM.md) section 1), but no traversal issues a batch through
  it yet, so nothing here reflects it.
- **Nothing at scale.** These are small-store numbers. A 10M node / 20M edge load, and how far it has moved across
  runs, is in [../GRAPH_BENCHMARK.md](../GRAPH_BENCHMARK.md).
- **No multi-writer number**, because there are no multi-writer transactions. The read scaling curve in §1.2 is real;
  the write curve is flat by construction.
- **No foreground latency budget during compaction.** `CompactionTest` shows a concurrent writer making progress
  throughout a compaction, but "p99 degrades by less than X" is a JMH question and is deferred to Phase 9.
- **Recovery time is not benchmarked.** Correctness is tested (`RecoveryTest`, `CompactionCrashTest`, the `kill -9`
  loop), speed is not. The TESTING target of "1 GiB of WAL in under 30 s" is unmeasured.
- **The fsync rate is device specific.** 753 commits/s reflects this machine's fsync latency, nothing about the
  engine.
- **The read microbenchmarks are noisy.** `nodeLabel` has been measured anywhere between 6.5 and 27 million ops/s
  single threaded on this machine, depending on JIT state and what else the box was doing. Treat the order of
  magnitude as the result, not the digits — which is also why the gates in `performance-gates.properties` sit at
  roughly 40 percent of these numbers.
- **These are not JMH numbers.** No forks, no warmup control beyond one pass, no confidence intervals.

---

_Git HEAD revision: `c016a41` (plus the Phase 0 to 9 working tree)_
_Last updated: 2026-08-13_
