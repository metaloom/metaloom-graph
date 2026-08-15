# Scale Report — 10 Million Nodes, 20 Million Relationships

What it costs to build a large random graph, measured rather than estimated.

Harness: [`GraphScaleBenchmark`](../../graph-core/src/test/java/io/metaloom/graph/core/bench/GraphScaleBenchmark.java),
tagged `huge` and excluded from every profile including `-Pall-tests`.

```bash
mvn test -Phuge -Dtest=GraphScaleBenchmark
mvn test -Phuge -Dtest=GraphScaleBenchmark -Dgraph.scale.nodes=1000000 -Dgraph.scale.edges=2000000
```

Machine: AMD Ryzen 9 9900X, 24 threads, 62 GiB RAM, overlay filesystem on NVMe. Java 25.
Store options: default 8 KiB pages, 4 GiB segments, `FSYNC_ON_COMMIT`. Nodes in batches of 100,000,
edges in batches of 20,000, checkpoint every 20 batches. Two properties per node.

---

## 1. The Headline

Measured twice: once against the original page-sized adjacency block, and once after the middle tier
of section 2 replaced it. Nothing else changed between the two runs.

> **The two runs below are an A/B taken back to back, and that is the only thing they are.** Two later
> runs of the same command produced the same store to the byte with an edge load 2.5x slower than run 2,
> for reasons that are not attributed. The absolute times here are not reproducible across hours on a
> shared machine; the *ratios* are what this report is about. Every run measured so far:
> [../GRAPH_BENCHMARK.md](../GRAPH_BENCHMARK.md).

| | Page-sized block | **Cell-based block** | |
|---|---|---|---|
| **10,000,000 nodes** | 83 s (120,203 /s) | **90 s** (110,818 /s) | — |
| **20,000,000 edges** | 2,470 s — 41 minutes | **563 s — 9.4 minutes** | **4.4x faster** |
| **Total** | 42 min 48 s | **10 min 54 s** | **3.9x faster** |
| On disk (`du`) | 61 GiB | **6.2 GiB** | **9.8x smaller** |
| of which adjacency | 59 GiB | **3.7 GiB** | **16x smaller** |
| Bytes per node | 6,530 | **656** | **10x** |
| Reopen | 3 ms | **1 ms** | — |
| `neighbours(OUT)` | 18,911 /s | **1,203,825 /s** | **64x** |

The nodes were never the story. The edges were, and the number that explained everything else was
**7,618,146 adjacency blocks** — one 8 KiB block for 76 percent of the nodes in the graph. There are
still 7,618,146 of them; each is now 512 bytes.

---

## 2. The Defect, And The Fix

### 2.1 Where the 61 GiB went

```
59 GiB   seg/adj      7,618,146 blocks x 8 KiB
1.3 GiB  seg/index    label and property B+trees
615 MiB  seg/node     10,000,000 x 64 B
615 MiB  seg/prop     19,990,000 property cells
43 MiB   wal          trimmed at every checkpoint
```

40 million directed adjacency entries occupied 59 GiB. That is **1,560 bytes per edge**, against the
1.0 to 1.2 bytes per entry that [RELATIONSHIP_STORAGE.md](../RELATIONSHIP_STORAGE.md) advertises and
that `SpaceEfficiencyTest` measures. Both numbers were correct. They described different graphs.

**The tiering had a hole, and a random graph fell straight into it:**

| Degree | Storage | Cost per edge |
|---|---|---|
| 0 to 2 | inline in the 64-byte node record | 8 B, no extra I/O |
| 3 to ~8,000 | **one 8 KiB block, however few entries it held** | **up to 4,096 B** |
| beyond ~8,000 | a chain of full blocks | 1.0 to 1.2 B |

A mean degree of 4 puts almost every node in the middle row, and the middle row was 1,000x worse per
edge than either neighbour. With Poisson(4) degrees 23.8 percent of nodes stay inline; the measured
figure was 7,618,146 of 10,000,000 promoted, which is that distribution exactly.

No existing test could see it: `SpaceEfficiencyTest` measures a supernode, where blocks are full and
the compression is real, and the budgets measure reads per operation, which the tiering did not hurt.

### 2.2 The fix: a block is a run of cells, and it grows

`ADJ` is no longer page-strided. Its allocation unit is a **512 byte cell**
([`Kind.ADJ_CELL_BYTES`](../../graph-core/src/main/java/io/metaloom/graph/core/id/Kind.java)) and a block is a
run of consecutive cells that **doubles as it fills** — 512 B, 1 KiB, 2 KiB, 4 KiB, one page — after
which the node starts a chain exactly as before. The run allocator already supported this: it is the
same page-straddle-avoiding `allocateRun` the property store uses.

| Degree | Storage | Cost per edge |
|---|---|---|
| 0 to 2 | inline in the node record | 8 B, no extra I/O |
| 3 to ~400 | **one 512 byte cell** | 1.3 B at degree 400, 170 B at degree 3 |
| ~400 to ~8,000 | a run that doubled to fit | 1.0 to 1.3 B |
| beyond ~8,000 | a chain of page-sized blocks | 1.0 to 1.2 B |

The measured middle row, from `AdjacencyTierTest#testASmallDegreeCostsASmallBlock`:

```
degree   20 ->  1 cell (25.60 bytes per directed entry)
degree   60 ->  1 cell ( 8.53 bytes per directed entry)
degree  150 ->  1 cell ( 3.41 bytes per directed entry)
degree  400 ->  1 cell ( 1.28 bytes per directed entry)
```

One 512 byte cell holds **400 directed entries**, because the delta-plus-varint codec compresses them
to just over a byte each. Growth therefore rarely triggers at all on ordinary graphs: at mean degree 4
every promoted node in the 10 million node run still fits its first cell.

Doubling costs one copy per doubling — five copies to reach a page, amortised O(1) per edge — and it
is preferred over chaining wherever there is room in the page, because a chain costs a read per block
on every traversal while a larger run costs none.

**Three things this touched, worth knowing about:**

1. **`flags` was written four bytes wide** although the header documents it as one, so it silently
   owned the three reserved bytes beside it. The new `cells` field lives at offset 37, and the first
   run of the new code erased it on every write. Now a byte, as documented.
2. **The free-slot bitmap is per cell**, so a sweep over live `ADJ` slots sees continuation cells as
   well as block headers — and a continuation cell holds compressed edge data, so nothing in it marks
   it as one. Enumeration goes through the owners instead, exactly as the property store already does.
3. **`StoreStats.adjacencyBlockCount` is now `adjacencyCellCount`**, because that is what it counts.
   A block is a run of one or more.

This is a format change, so `FORMAT_VERSION` is **2**. There is no converter: a version 1 store is
rejected at open with `FormatVersionException`.

---

## 3. Why The Load Slowed Down, And What Is Left Of It

Edge throughput over the run, cumulative, before and after:

| Edges written | Page-sized block | Cell-based block |
|---|---|---|
| 400,000 | 99,489 /s | 77,040 /s |
| 2,000,000 | 80,114 /s | 75,635 /s |
| 4,800,000 | 57,810 /s | 70,167 /s |
| 8,000,000 | 34,177 /s | 64,353 /s |
| 12,400,000 | 17,144 /s | 53,729 /s |
| 17,600,000 | 9,388 /s | 44,863 /s |
| 20,000,000 | 8,098 /s | **35,536 /s** |

The old curve fell 12x across one load; the new one falls **2.2x**. It also *starts* slower — 77,040
against 99,489 — which is real and worth naming: a 512 byte block fills sooner, so the first edges of
a promoted node pay a recompaction the old format deferred by having 8 KiB of room. That trade buys
back an order of magnitude by the end of the load, and 10x the disk. The dominant cause of the decay
is gone:

1. **Mean degree crossing 2 used to cost 16 KiB per edge.** Once both endpoints had three edges,
   every 16-byte edge read, modified and wrote a whole 8 KiB block *and* logged an 8 KiB page image.
   At 512 bytes a cell, sixteen blocks now share a page, so a promoted node's edge dirties the same
   page as fifteen of its neighbours' — and the log carries that page once.
2. **The store outgrowing the page cache** is what remains. 6.2 GiB fits in RAM comfortably, so what
   is left of the curve is the B+tree index growing deeper and the random node access spreading over
   615 MiB. The residual 3x is worth attacking next; the 20x was the format.

---

## 4. Reading It Back

| Operation | Page-sized block (61 GiB) | Cell-based block (6.2 GiB) |
|---|---|---|
| `nodeLabel` by id | 3,618,090 /s | 4,224,660 /s |
| `nodeProperties` by id | 177,237 /s | 1,821,122 /s |
| `neighbours(OUT)` random node | **18,911 /s** | **1,203,825 /s** |
| `nodesByProperty` (B+tree) | 11,892 /s | 359,453 /s |
| Reopen the store | 3 ms | 1 ms |

`neighbours` was the same defect seen from the read side: each probe pulled an 8 KiB block out of a
59 GiB region to read about five entries — 1,600x read amplification, and one device read where the
budget assumes zero. At 512 bytes the whole adjacency region is 3.7 GiB and stays in cache, and the
call runs at the rate the 1M-node store always managed. **The 64x was page cache, and the page cache
pressure was the format.**

`nodeLabel` was fast in both, which is the addressing invariant doing what it promises: an id decodes
to an offset, the node region is 615 MiB either way, and no index is consulted.

Reopen is worth stating plainly: opening the store maps segments and validates superblocks; it does
not scan. Store size does not enter into it.

---

## 5. For Comparison, One Tenth The Size

At 1M nodes / 2M edges, before and after:

| | Page-sized block | Cell-based block |
|---|---|---|
| Node load | 4.2 s (235,624 /s) | 4.0 s (252,932 /s) |
| Edge load | 83 s (24,064 /s) | **15.3 s (130,388 /s)** |
| `storageBytes` | 6.1 GiB | **0.6 GiB** |
| `neighbours(OUT)` | 1,182,199 /s | 1,217,847 /s |

And the two scales, after the fix:

| | 1M / 2M | 10M / 20M |
|---|---|---|
| Node load | 4.0 s | 90 s |
| Edge load | 15.3 s | 563 s |
| On disk | 0.6 GiB | 6.2 GiB |
| `neighbours(OUT)` | 1,217,847 /s | 1,203,825 /s |

Storage now scales linearly and so does read throughput. Edge load is 10x the graph for 37x the time,
against 30x before — the remaining superlinearity is the index and the cache, not the format.

---

## 6. What To Take From This

- **The defect was one number in an enum.** `ADJ` was page-strided; it is 512-byte-strided. Everything
  else — the growth, the run allocation, the compression, the chaining — was already in the engine or
  came to under a hundred lines.
- **A benchmark found what nine test classes could not.** Every unit test used either a low-degree
  chain or a supernode, and the defect lives strictly between those two. The lesson is not "write more
  tests"; it is that a test written from inside the design tends to probe the cases the designer was
  already thinking about.
- **It cost one thing.** `FORMAT_VERSION` is 2 and there is no converter, so an existing store must be
  rebuilt. That is the price the version-bump rule in [PLAN.md](../PLAN.md) §1 exists to make explicit.
- **What is left is smaller and different.** The load curve still falls 3x over 20 million edges, but
  the cause is now the index depth and node-page locality rather than the adjacency format. That is
  the next thing to measure, not to guess at.

---

_Git HEAD revision: `364b5f7` plus the cell-based adjacency change_
_Last updated: 2026-08-14_
