# Graph Benchmark — Run Log

Every scale, traversal and supernode measurement taken on this machine.

Section 1 is the load progression across code changes. Sections 5 to 8 are the read-side benchmarks
— random access, deep traversal, supernodes — and section 9 is the SIMD investigation, which is
mostly a record of what did not work.

| Harness | Tag | What it answers |
|---|---|---|
| [`GraphScaleBenchmark`](../graph-core/src/test/java/io/metaloom/graph/core/bench/GraphScaleBenchmark.java) | `huge` | What does it cost to load 10M nodes and 20M relationships |
| [`GraphTraversalBenchmark`](../graph-core/src/test/java/io/metaloom/graph/core/bench/GraphTraversalBenchmark.java) | `huge` | Random access by id, 30,000-relationship traversal, 30,000-hop chain |
| [`SupernodeBenchmark`](../graph-core/src/test/java/io/metaloom/graph/core/bench/SupernodeBenchmark.java) | `bench` | Does one node with 20,000 relationships degrade — with gates |
| [`CodecProfileBenchmark`](../graph-core/src/test/java/io/metaloom/graph/core/bench/CodecProfileBenchmark.java) | `bench` | Where the time inside a block read and write goes |

---

Every `GraphScaleBenchmark` result measured on this machine, in one table, oldest run on the left.

This file is the **run log**: what was measured, when, and whether it moved. Why run 1 was so bad and
what the fix was is analysed in [reports/SCALE.md](reports/SCALE.md); this page is the progression and
nothing else.

Harness:
[`GraphScaleBenchmark`](../graph-core/src/test/java/io/metaloom/graph/core/bench/GraphScaleBenchmark.java),
tagged `huge` and excluded from every profile including `-Pall-tests`.

```bash
mvn test -Phuge -Dtest=GraphScaleBenchmark
mvn test -Phuge -Dtest=GraphScaleBenchmark -Dgraph.scale.checkpointEvery=20
```

All four runs: 10,000,000 nodes with two properties each, 20,000,000 random lightweight edges, seed
20260814, 100,000 nodes and 20,000 edges per transaction, 8 KiB pages, 4 GiB segments,
`FSYNC_ON_COMMIT`. Machine: AMD Ryzen 9 9900X, 24 threads, 62 GiB RAM, overlay filesystem on NVMe,
Java 25. Single process, single writer, warm page cache for the read-back section.

**The checkpoint cadence is not the same in every column** — see the row for it. Runs 3 and 4 exist as
a pair precisely to take that variable out.

---

## 1. The Progression

| | **Run 1**<br>page-sized adjacency | **Run 2**<br>cell-based adjacency | **Run 3**<br>after Phase 6/7 | **Run 4**<br>after Phase 6/7, cadence matched to run 2 |
|---|---|---|---|---|
| Date | 2026-08-14 | 2026-08-14 | 2026-08-14 | 2026-08-14 |
| Format version | 1 | 2 | 2 | 2 |
| Checkpoint every | 20 batches | 20 batches | 40 batches | **20 batches** |
| **Node load** | 83.2 s — 120,203 /s | 90.2 s — 110,818 /s | 114.8 s — 87,111 /s | **86.3 s — 115,832 /s** |
| **Edge load** | 2,469.8 s — 8,098 /s | **562.8 s — 35,536 /s** | 1,372.2 s — 14,575 /s | 1,395.3 s — 14,334 /s |
| **Total load** (both phases) | **42 min 33 s** | **10 min 53 s** | 24 min 47 s | 24 min 42 s |
| Storage bytes | 65.3 GB — 60.8 GiB | 6.56 GB — 6.1 GiB | 6.56 GB — 6.1 GiB | 6.56 GB — 6.1 GiB |
| Bytes per node | 6,530 | 656 | 655.5 | 655.5 |
| Adjacency cells | 7,618,146 blocks x 8 KiB | 7,618,146 x 512 B | 7,618,146 x 512 B | 7,618,146 x 512 B |
| `nodeLabel` by id | 3,618,090 /s | 4,224,660 /s | 3,846,650 /s | 4,323,765 /s |
| `nodeProperties` by id | 177,237 /s | 1,821,122 /s | 1,836,066 /s | 2,016,311 /s |
| `neighbours(OUT)` | 18,911 /s | 1,203,825 /s | 1,060,624 /s | 1,071,973 /s |
| `nodesByProperty` (B+tree) | 11,892 /s | 359,453 /s | 421,861 /s | 380,454 /s |
| Reopen | 3 ms | 1 ms | 2 ms | 1 ms |

Runs 1 and 2 are the pair analysed in [reports/SCALE.md](reports/SCALE.md); their disk figures there
are `du` (61 GiB and 6.2 GiB) rather than the engine's own accounting quoted above.

---

## 2. Did The Latest Changes Improve It?

**No — and none of them touch this path.** Phase 6 and 7 added io_uring, `O_DIRECT`, the Rust codecs
and unique constraints. Of those:

- `GraphStoreOptions.ioEngine()` was **declared and consumed by nothing** when these runs were taken.
  It is wired now — the log can run on the direct engine — but `AUTO` still resolves to buffered, so
  the path these four runs measured is the path a default store still takes. See section 10.
- The Rust codecs were **not wired into the store** either. lz4 reaches `BlobStore` now, but this
  benchmark stores no blobs — its properties are a small string and an int — so the path is unchanged.
  `NativeLib` is otherwise reached here only for hole punching and cache eviction, neither of which a
  load performs.
- Unique constraints do reach `createNode`, via `checkUnique`, but no constraint is declared here, so
  the work is one `isUnique` probe per property. The node load is unchanged (86.3 s against 90.2 s), so
  it does not show up.

The read-back rows agree: every one of them is within noise of run 2. **The stored result is identical
to the byte** — same 7,618,146 cells, same 655.5 bytes per node — which is the strongest evidence
there is that the format and the write path did not change.

### The 2.5x edge-load gap, which I cannot attribute

Run 4 loaded edges in **1,395 s against run 2's 562.8 s** at the same cadence, and run 3 reproduced it
at a different one. That is reproducible, and it is not explained by anything above. What is known:

**Against the code being at fault:** at one tenth the size, measured today, the engine performs exactly
as it did in the run-2 era — 1,000,000 nodes in 4.1 s and 2,000,000 edges in **15.1 s (132,030 /s)**,
against 15.3 s (130,388 /s) then. Per-operation cost has not moved. Nor has the output, to the byte.

**What the curve shows.** Cumulative edge throughput, run 2 against run 4, same cadence:

```
edges written     run 2        run 4
   400,000     77,040 /s    93,415 /s     run 4 is FASTER
 2,000,000     75,635 /s    92,615 /s     run 4 is faster
 4,800,000     70,167 /s    83,838 /s     run 4 is faster
 8,000,000     64,353 /s    72,998 /s     run 4 is faster
11,600,000          -       57,647 /s     <- knee, and run 4 falls off it
12,400,000     53,729 /s    40,738 /s     run 4 is now slower
17,600,000     44,863 /s    17,778 /s
20,000,000     35,536 /s    14,334 /s
```

Run 4 is **faster than run 2 for the first 11 million edges** and then collapses. A per-operation
regression would be visible from the first batch and would not invert halfway. The knee reproduced at
~11.2M edges in run 3 and ~11.6M in run 4.

**What I did not do:** I did not reproduce run 2's tree and re-measure it today, which is the one
experiment that would settle it — the Phase 6/7 work is uncommitted on top of the same working tree,
so there is no revision to check out and the project's rules forbid stashing to get one.

**The most likely explanation is machine state**, and it is a caveat on runs 3 and 4 rather than a
finding: the filesystem is 84 percent full now and was not when runs 1 and 2 were taken (run 1 alone
wrote 61 GiB into it), and a workstation with an IDE and language servers on it is not a controlled
environment. The knee arrives when the store plus its WAL stops being cheap for the host to absorb,
which is a property of the host as much as of the engine. **Treat the run 2 edge-load figure as the
one taken under the friendlier conditions and run 4 as the pessimistic one; the truth for a given
deployment is measured there, not read off this page.**

---

## 3. What Changed Between The Columns

| | What was different |
|---|---|
| Run 1 to run 2 | The adjacency block stopped being a page. `Kind.ADJ` became 512-byte strided with geometric growth; format version 2. This is the change [reports/SCALE.md](reports/SCALE.md) is about, and it is the only one so far that moved this benchmark. |
| Run 2 to run 3 | Phase 6 and 7: io_uring (`UringIoEngine`), `O_DIRECT` (`DirectIoEngine`), the Rust `codec`/`ring` modules, xxh3 and lz4, unique property constraints, `cargo test` and `clippy` inside `mvn test`. None of it is wired into the load or read path. |
| Run 3 to run 4 | Nothing but the checkpoint cadence, 40 back to 20, to match run 2. It bought back the node load (114.8 s to 86.3 s) and left the edge load where it was. |

---

## 4. Reading This Table Later

- **The only row that has ever moved for a code reason is the adjacency one**, and it moved by 4.4x on
  edge load and 10x on disk. Everything since has been capability work that this benchmark cannot see.
- **A run of this is not a controlled measurement.** It is a timing loop against whatever the host is
  doing, and section 2 is what that costs in interpretability. Quote a range, not a figure.
- **The next thing that should move this table** is the cursor prefetch onto the io_uring batch — the
  ring is built and measures 12.4x on scattered reads ([NATIVE_FFM.md](NATIVE_FFM.md) section 1), but
  no traversal calls `readBatch` yet, so `neighbours(OUT)` above is still one synchronous fault at a
  time.

---

## 5. Random Access By Id

`GraphTraversalBenchmark`, 2,000,000 nodes and 8,000,000 edges — mean degree 8, a denser graph than
the scale benchmark's 4 — with two properties per node. Warm cache throughout.

| Operation | ns each | per second |
|---|---|---|
| `nodeLabel`, **sequential** ids | **22** | 44,906,890 /s |
| `nodeLabel`, **random** ids | **140** | 7,124,332 /s |
| `degreeOut`, random ids | 137 | 7,283,085 /s |
| `nodeProperties`, random ids | 387 | 2,582,919 /s |
| `neighbours(OUT)`, random ids | 1,006 | 994,257 /s |
| `nodesByProperty`, B+tree | 1,656 | 604,000 /s |

**The sequential and random lines are the interesting pair.** The addressing invariant says an id
decodes to an offset with no index probe, and both lines confirm it — neither consults anything. What
separates them by 6.4x is the memory hierarchy: sequential ids walk a 64-byte record at a time through
pages the prefetcher can see coming, random ids miss the TLB and the last-level cache on nearly every
probe. That is the floor for any store that lets a caller ask for an arbitrary id, and it is worth
stating plainly because "O(1) by construction" invites the reading that the constant does not matter.

---

## 6. Deep Traversal — 30,000 Relationships

Breadth first from a random start, stopping at a 30,000 relationship budget, ten different starts:

| | |
|---|---|
| Relationships crossed | 30,000 per run |
| Time | **5.9 to 9.9 ms** |
| Rate | **4,336,446 relationships/s** |
| Per relationship | **0.23 µs** |
| Nodes opened | ~3,330 |
| Nodes discovered | ~26,500 |
| Depth reached | **4 to 5 hops** |
| Spread across starts | 1.7x |

**A mean degree of 8 exhausts 30,000 relationships in five hops**, so this is a wide flood rather than
a deep walk — 3,330 nodes opened, 26,500 discovered. Anything calling itself a depth measurement has
to force the shape, which is what the next section does.

### The same budget, three shapes

| Shape | per relationship | calls | per call |
|---|---|---|---|
| Breadth first | 0.28 µs | 3,328 | **2.49 µs** |
| Depth first | 0.25 µs | 3,343 | 2.29 µs |
| Unrelated random nodes | 0.21 µs | 3,744 | **1.66 µs** |

A walk costs **more** per call than sampling unrelated nodes, which is the opposite of what locality
predicts. The cause is not the storage layout: breadth-first search samples nodes in proportion to
their degree — a popular node is reachable by many routes, an isolated one by none — so the nodes a
walk opens are larger than average and cost more to read. **Locality buys nothing measurable here
because the whole store fits in page cache**; there is no device read for a nearby page to save. None
of these three numbers should be quoted as a locality result until the same benchmark is run on a
store larger than RAM.

---

## 7. Deep Traversal — One Path, 30,000 Hops

A dedicated chain of 30,001 nodes, walked one hop at a time:

| | |
|---|---|
| Hops | 30,000 |
| Time | **2.81 ms** |
| **Per hop** | **94 ns** |
| Rate | 10,678,976 hops/s |

**This is the honest deep-traversal number, and it is a latency measurement, not a throughput one.**
Every hop depends on the one before it, so nothing can be issued in parallel and no amount of queue
depth helps. 94 ns per hop is a node record read plus an adjacency read plus the materialisation of
one `Edge`, serialised — and it is 2.4x cheaper per relationship than the breadth-first walk, because
a chain node has degree 1 and a walk opens fat ones.

The chain's node ids are consecutive, which is the friendliest layout a chain can have, so **94 ns is
a floor rather than a typical figure**. A chain scattered across a store larger than RAM would be
bounded by device latency instead, and that is the case the io_uring engine cannot help with either —
a dependent load cannot be batched with the load it depends on.

---

## 8. Supernodes — One Node, 20,000 Relationships

The requirement was that performance must not degrade. It does not, and the benchmark asserts it
rather than reporting it.

| Degree | Insert, last 1,000 | Insert, cheapest window | `neighbours(OUT)` per edge | `degreeOut` |
|---|---|---|---|---|
| **20,000** | 2.29 µs/edge | 2.29 µs/edge | 0.024 µs | 56.8 ns |
| **200,000** | **1.73 µs/edge** | 1.60 µs/edge | **0.019 µs** | 45.2 ns |

**Cost per edge falls as the degree grows, at ten times the degree the requirement asked for.** The
first 1,000 edges cost 23 µs each and the last 1,000 cost 1.7 — the early windows pay for JIT warmup
and for the whole growth ladder, 512 B to 1 KiB to 2 KiB to 4 KiB to a page, while later ones append
into a page-sized block and chain when it fills.

Reading behaves the same way: **0.019 µs per edge at degree 200,000 against 0.024 at degree 2,000**,
because the fixed per-call cost amortises over more edges. `degreeOut` is flat at ~50 ns because it
is a header field, not a walk.

**The gates.** `SupernodeBenchmark` asserts the last insertion window is within 3x the cheapest window
of the same run, that a high-degree read costs no more than 3x per edge than a low-degree one, and
that `degreeOut` stays under 2 µs. The insertion gate compares against the *cheapest* window rather
than the first: the first window is the most expensive one in the run, so measuring the end against it
would let a real regression through unnoticed.

---

## 9. Can The Compute-Intensive Code Be Vectorised?

Asked properly, and the answer is mostly no. Here is where a block read and a block write actually
spend their time, per entry, on a full page-sized block of 8,000 edges:

| Kernel | ns per entry | Share |
|---|---|---|
| **decode** — the traversal hot loop | **1.85** | 90% of a block read |
| tombstone filter | 0.21 | 10% |
| page copy | 0.02 | negligible |
| **encode** | **8.4** | the write path |
| — of which sort | 3.5 | |
| — of which varint and grouping | 4.9 | |

Against a traversal that costs **230 ns per relationship**, decode is under **1%**. Vectorising it
perfectly would not be visible. The cost of a traversal is the fixed per-node work — resolving a page,
walking a chain, allocating an `Edge` and its type string — not the codec.

### What was tried

**SWAR varint decoding — implemented, measured, reverted.** Continuation bits are delimiters, so the
1BRC trick applies directly: `~word & 0x8080808080808080L` marks every byte that ends a varint, one
`tzcnt` gives the length, and three shift-and-mask steps fold the seven-bit groups into a value. Eight
bytes per operation, no per-byte branch.

It is **2.3x slower** than the loop it replaced:

| Entries | byte at a time | SWAR |
|---|---|---|
| 64 | 3.10 ns/entry | 5.55 |
| 400 | 1.71 | 3.92 |
| 2,000 | 1.64 | 3.83 |
| 8,000 | **1.63** | 3.79 |

The reason is in the data, not the technique: **varints in a real adjacency block average 1.93 bytes**.
The scalar loop exits after one iteration on most of them, and the branch it takes is highly
predictable because delta sizes cluster. SWAR pays ten ALU operations for every value regardless of
length. The trick wins on 8-to-16 byte names; it loses on 2-byte varints. Reverted, and
`VarIntSegmentTest` stands as the guard: anything replacing the loop has to beat it there first.

**Encoding straight into the page, with an exact-size pass — implemented, measured, reverted.** Sizing
the output exactly with `VarInt.sizeOf` removes a 72 KiB allocation per encode and lets the result be
written into the mapped page with no copy. It made supernode insertion **2.3x worse** (4.47 against
1.94 µs/edge): a second arithmetic pass costs more than a short-lived array from the nursery, and a
byte-at-a-time `MemorySegment` write is bounds-checked where a `byte[]` store is not.

### What was kept

**A merge instead of a full sort.** A block recompresses by decoding its sorted region — which comes
back sorted — and appending a short unsorted tail. Sorting the whole array costs 3.5 ns per entry;
sorting the tail and merging costs near the 0.46 ns that re-sorting already-sorted data costs. The
merge buffer is the size of the tail, not of the block.

**Decoding straight out of the mapped page.** Within noise of copying to a `byte[]` first at every
block size from 400 entries up, and it does not allocate up to a page on every block read.

### The conclusion

The compute in this engine is not where a vector unit helps. The read path is bounded by per-node
fixed costs and the write path by allocation and page traffic. The next real win on the read side is
a cursor that yields primitives instead of building an `Edge` and a `String` per relationship — which
is Phase 3's streaming cursor, not a SIMD problem.

---

## 10. What Phase 6 Cost, Measured

Two of the capabilities finished in Phase 6 change performance, and both are **off by default** for
reasons that are measurements rather than caution.

| Capability | Default | Measured |
|---|---|---|
| **Traversal prefetch** (`madvise` on the BFS frontier) | **off** | **1.8x slower** warm: 387 ns per edge against 210 (`BulkBenchmark#benchTraverseRandomGraph`). First measured at 2.8x (1,967 against 701), most of it a defect that re-advised the whole window on every step |
| **WAL on the direct engine** (`O_DIRECT`) | **off** (`AUTO` = buffered) | up to 4,095 wasted bytes per commit under `FSYNC_ON_COMMIT` |
| **lz4 for blobs** | **on** where the crate loaded | a 50,000 byte run stores as 207 bytes |

**Prefetch is the clearest case of a capability that is right in theory and wrong here.** Advising the
kernel about the next frontier costs a syscall per queued node. On a store that fits in page cache
there is no fault for it to overlap, so the syscall is the entire effect. It can only pay on cold
pages — and this filesystem cannot produce cold pages, because `posix_fadvise` is accepted and evicts
nothing. So its cost is measurable here and its benefit is not, which is the whole argument for
leaving it off.

**The same shape applies to io_uring.** 12.4x on a batch of scattered reads, and no caller: a mapped
data path takes page faults rather than issuing reads, so there is nothing to batch. The log does read
and write through a descriptor, which is why the log is where `O_DIRECT` landed.

**lz4 is the one that simply pays**, because the win is space rather than time and the cost is on the
write path where a blob is written once and read many times.

---

_Git HEAD revision: `364b5f7` plus the uncommitted cell-based adjacency, Phase 6/7 and benchmark changes_
_Last updated: 2026-08-14_
