# I/O Strategy — Optimising for SSD and Avoiding Bogus Seeks

The requirement is explicit: **optimise for SSD storage and avoid pointless read seeks**. This file
is the I/O contract that the rest of the design serves.

---

## 1. What "Optimise for SSD" Actually Means

Rotational-disk intuitions mislead here. On NVMe:

| Property | Consequence for this design |
|----------|-----------------------------|
| Random 4 KiB reads are fast (~10 us) but **not free** — each costs an IOP | Minimise the *number* of reads, not the seek distance |
| A single thread cannot saturate the device; concurrency does | Keep many requests in flight — queue depth is the throughput lever |
| Reads below 4 KiB cost the same as 4 KiB | Never issue a 64-byte read; read the page |
| Writes happen in erase blocks far larger than 4 KiB | Batch writes; avoid rewriting the same page repeatedly |
| Write amplification shortens device life and throughput | Log small, checkpoint large, never write a full page for a 64-byte change |
| The FTL prefers sequential and clustered writes | Allocate new records densely; write back in page order |
| TRIM matters | Punch holes on free so the FTL can reclaim |

So the design goals become: **fewest possible IOPs per logical operation**, **high queue depth**,
**4 KiB alignment always**, **sequential where the access pattern allows it**.

---

## 2. The Seek-Avoidance Rules

Each rule maps to a concrete mechanism elsewhere in the spec.

| # | Rule | Mechanism |
|---|------|-----------|
| S1 | Reading an element by id costs **zero** lookup reads | Id decodes to an offset — [ID_ENCODING.md §3](ID_ENCODING.md#3-the-address-function) |
| S2 | A record never straddles a page, so one record read is one page read | Strides divide `PAGE_SIZE` — [FILE_FORMAT.md §2](FILE_FORMAT.md#2-invariants) |
| S3 | Reading all properties of an element costs **one** read | Single property block — [PROPERTY_STORAGE.md §2](PROPERTY_STORAGE.md#2-property-block) |
| S4 | Enumerating a low-degree node's neighbours costs **zero** extra reads | Inline edges in the node record — [RELATIONSHIP_STORAGE.md §2](RELATIONSHIP_STORAGE.md#2-three-storage-tiers) |
| S5 | Enumerating a high-degree node costs one read per ~4000 edges | Compressed adjacency blocks |
| S6 | Filtered traversal skips blocks without reading them | Per-block min/max and type bitmap in the block header, which is in the block you already read |
| S7 | Verifying a page costs no extra read on the data path | Checksums live in the PMA, read as part of the page-metadata line |
| S8 | Commit costs **one sequential append**, never a scattered write | No-force WAL — [WAL.md §1](WAL.md#1-model) |
| S9 | Checkpoint writes are issued in segment and page order | Write-back sorted by physical offset |
| S10 | A traversal keeps the device busy | Prefetch and queue depth — §5 |

The PoC violates S1 (no, it satisfies S1 — that is its one strength), S2 (1080-byte stride), S3 (one
read per property, plus a whole-file remap), S4 and S5 (one read per edge), S7 (no checksums at all),
S8 (no log), S9 and S10 (no batching, no prefetch).

---

## 3. Alignment

| Thing | Alignment | Enforced by |
|-------|-----------|-------------|
| `PAGE_SIZE` | 4/8/16 KiB, a multiple of the device block size | Store creation, recorded in the manifest |
| `DATA_START[kind]` | multiple of `PAGE_SIZE` | Computed and asserted at segment creation |
| Record strides | divide `PAGE_SIZE` | Invariant I1, asserted in `SegmentLayoutTest` |
| WAL blocks | 4 KiB, `O_DIRECT` | Aligned buffers from the native allocator |
| Property blocks | never cross a page | Allocator skips to the next page rather than straddling |
| Blob extents | one page each | By construction |

Contrast the PoC: a 528-byte header followed by 1080-byte records means the *first* record spans
bytes 528..1608, crossing the 4096 boundary at... not quite, but record 4 spans 3768..4848 and does.
Roughly a quarter of node reads touch two pages for no reason.

---

## 4. I/O Engines

`IoEngine` is an interface with three implementations, selected by `io.engine`:

| Engine | Read | Write | When |
|--------|------|-------|------|
| `mmap` | Page fault on `MemorySegment` slice | Dirty page + `force()` | Default; simplest, excellent for read-heavy workloads that fit in RAM |
| `direct` | `pread` with `O_DIRECT` into aligned buffers | `pwrite` | When the working set exceeds RAM and page-cache double buffering hurts |
| `uring` | Batched `io_uring` submissions, several in flight | Batched, with linked fsync | Default on Linux once Phase 6 lands; the only way to reach device queue depth from one thread |

**Why not just mmap.** Memory mapping is wonderful until the working set exceeds RAM, at which point
every miss is a synchronous major page fault that blocks a carrier thread with no way to express
"fetch these eight pages concurrently". A traversal that touches 1000 scattered pages costs 1000
serialised faults at ~10 us = 10 ms, when the device could have served them in ~1 ms at queue depth
32. That single fact is the reason the io_uring engine exists and the reason the native layer exists
at all.

**Virtual thread note.** A major page fault pins the carrier thread; `io_uring` completions do not.
For a server embedding this store, the uring engine is what keeps virtual threads useful.

---

## 5. Prefetch and Queue Depth

Cursors are the prefetch drivers, because only a cursor knows what comes next.

- **Adjacency chains**: on decoding block *n*, submit a read for block *n+1*. With
  `traverse.prefetch.depth` (default 4) the cursor keeps four blocks in flight.
- **Multi-node lookups**: `readNodes(long[] ids)` sorts the ids by physical offset, coalesces reads
  that land on the same page, and submits the batch. Sorting turns a scatter into something the FTL
  and the read-ahead logic both like. This is why the API exposes a batch form — see
  [API.md §4](API.md#4-batch-and-cursor-apis).
- **Scans**: a full node scan reads in `scan.chunk.pages` (default 256 = 2 MiB) sequential chunks
  with `MADV_SEQUENTIAL` / `RWF_NOWAIT` hints.
- **Cold-start warmup**: optionally read the PMA of every segment at open (a few MiB) so that
  liveness and checksum data are hot without touching data pages.

---

## 6. Write Amplification

| Source | Mitigation |
|--------|------------|
| Logging | Physiological records: ~110 bytes of log for a 64-byte node write, not an 8 KiB page image |
| Repeated dirtying of one page | No-force plus write-back coalescing: a page dirtied 50 times between checkpoints is written once |
| Small scattered writes | Checkpoint write-back sorted by offset; the FIFO free-slot policy spreads allocations so that new data lands in fresh pages rather than repeatedly rewriting one |
| Full-page WAL records | Only `ADJ_REWRITE` uses one, only on block seal |
| Metadata churn | Preallocated WAL segments and `fallocate`d data segments mean no filesystem metadata writes on append |
| Undo | Before-images are the changed bytes, not whole records, wherever `payload_len` allows |

Target: **under 3x** total write amplification (bytes to device / logical bytes written) for a
mixed OLTP-style workload. This is a measured number in the benchmark harness, not an aspiration.

---

## 7. Space Reclamation and TRIM

1. Freeing a slot clears an FSB bit. Nothing is returned to the OS.
2. When a page's `live_count` reaches zero, the page is a candidate for
   `fallocate(FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE)`, which returns its blocks to the
   filesystem and lets the device TRIM them. **No ids break.**
3. Only when free space is scattered *within* pages does compaction run, which does break ids —
   [FILE_FORMAT.md §9](FILE_FORMAT.md#9-compaction). It punches first and lowers the high water marks
   afterwards, in that order: a page emptied by the relocation and left above the new mark would
   otherwise never be considered for punching.

Prefer step 2. It is nearly free and it is the reason the free-slot allocator prefers to fill fresh
pages rather than scatter allocations across half-empty ones.

---

## 8. Measurement

Nothing in this file is believable without measurement. The benchmark harness
([TESTING.md §7](TESTING.md#7-benchmarks)) reports, for every scenario:

| Metric | Source |
|--------|--------|
| Device reads and bytes | Instrumented `IoEngine` counters, cross-checked against `/proc/self/io` and `iostat` |
| Reads per logical operation | Counter / operation count — this is the S1..S6 scorecard |
| Write amplification | Device bytes written / logical bytes written |
| Queue depth achieved | io_uring in-flight histogram |
| p50/p99/p999 latency | HdrHistogram |
| Page cache hit rate | Cache counters |

A change that improves throughput while raising reads-per-operation is a regression in this project,
and the harness fails the build on it.

---

## 9. Conventions and Gotchas

- **Never read less than a page from the device.** If you find yourself calling `pread` with a
  64-byte length, you have bypassed the page cache.
- **Never write a partial page with `O_DIRECT`.** It becomes a read-modify-write and silently
  destroys the alignment guarantee.
- **`madvise` hints are advisory and vary by kernel.** Measure; do not assume `MADV_WILLNEED` did
  anything.
- **Growing a file by writing zeros materialises every block.** The PoC's
  `OffsetProvider.ensureFileCapacity` does exactly this. Use `fallocate` or nothing.
- **`Files.size()` on a sparse segment is the nominal size.** Report allocated blocks via the native
  `stat` binding when reporting disk usage.
- **Page cache double buffering is real.** With the mmap engine on a machine that is also running
  Postgres, the OS page cache is shared; the `direct` engine exists for the case where that matters.
- **Do not tune `PAGE_SIZE` up without measuring.** 16 KiB pages halve the PMA and improve scans, but
  quadruple the read amplification for random single-record access.
- **Benchmarks on a laptop SSD with a full FTL write cache mean nothing.** Bench on the target class
  of device, after filling it, with the cache in a steady state.

---

## 10. Test Setup

- `AlignmentTest`: for every kind and page size, assert every record's byte range lies within one
  page and that all region starts are 4 KiB aligned.
- `IoCountingTest`: an `IoEngine` decorator counting device-level reads; assert the S1..S6 budgets:
  read a node = 1 read; read a node's properties = 1 more; enumerate a degree-2 node = 0 more;
  enumerate a degree-4000 node = 1 more.
- `WriteAmplificationTest`: run a fixed mixed workload, assert amplification is under the threshold.
- `SparseTest`: create 100 segments, assert total allocated blocks are under 100 MiB.
- `HolePunchTest`: fill a page, free every record, run reclamation, assert allocated blocks drop and
  that every surviving id still resolves.
- `EngineEquivalenceTest`: run the same workload through `mmap`, `direct` and `uring`; assert
  byte-identical final segment contents.
- `QueueDepthTest`: instrumented traversal over a cold, larger-than-RAM store; assert the uring
  engine sustains a configured depth and beats the mmap engine by the expected margin.

---

## 11. Progress Assessment

- [ ] `IoEngine` interface and the counting decorator
- [ ] `mmap` engine (Phase 1)
- [ ] `direct` engine with aligned buffers (Phase 6)
- [ ] `uring` engine (Phase 6)
- [ ] Batch `readNodes` with offset sorting and page coalescing
- [ ] Cursor prefetch for adjacency chains
- [ ] Write-back sorted by physical offset
- [ ] Hole punching and TRIM on all-free pages
- [ ] Sparse segment creation via `fallocate`
- [ ] Metrics per §8 wired into the benchmark harness
- [ ] Reads-per-operation budgets enforced in CI

---

## 12. Key Classes Reference (target)

| Class | Package | Purpose |
|-------|---------|---------|
| `IoEngine` | `io.metaloom.graph.core.io` | Read, write, flush, prefetch, allocate, punch |
| `MmapIoEngine` | `io.metaloom.graph.core.io` | Mapped-segment engine |
| `DirectIoEngine` | `io.metaloom.graph.core.io` | `O_DIRECT` pread/pwrite with aligned buffers |
| `UringIoEngine` | `io.metaloom.graph.core.io` | Batched io_uring via the native layer |
| `CountingIoEngine` | `io.metaloom.graph.core.io` | Test decorator counting device operations |
| `WriteBackScheduler` | `io.metaloom.graph.core.page` | Offset-ordered, WAL-gated page write-back |

---

_Git HEAD revision: `c016a41`_
_Last updated: 2026-08-13 (synced with the Phase 0 to 3 implementation)_
