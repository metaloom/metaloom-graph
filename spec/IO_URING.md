# io_uring

Capability **N1** of [NATIVE_FFM.md](NATIVE_FFM.md): batched, asynchronous, high-queue-depth I/O
through the kernel's `io_uring` interface, reached from Java through the Rust crate.

**It is built, tested, and measured, and nothing in the engine calls it.** That sentence is the whole
status, and every section below is either evidence for one half of it or a plan for changing the
other.

| | |
|---|---|
| **Why it exists** | It is the only capability on the native list the JVM cannot approach at all, and the only one that still justifies having a Rust crate |
| **What it buys** | 64 scattered 4 KiB `O_DIRECT` reads: **2,942 µs through a `pread` loop, 195 µs through the ring — 15.1x** |
| **What calls it** | `UringIoEngineTest`. Nothing in `src/main` |
| **Why not** | The data path is a memory mapping. A traversal takes page faults; it never issues a read for the ring to receive |
| **What would** | The write-ahead log under `GROUP_COMMIT`, and the data path on the day it stops being mapped |

Quoting the 15x as though a query were getting it would be wrong in two ways: nothing calls it, and
the caller it would serve does not exist yet.

---

## 1. What the JVM Cannot Do

A `FileChannel.read` and a page fault are both **synchronous**. Fetching eight pages costs eight round
trips, one after another, and the thread waits through each. There is no JDK API for "fetch these
eight and tell me as they land" — not `AsynchronousFileChannel`, which is a thread pool wearing a
callback, and not virtual threads, which unmount on a socket but not on a major page fault.

A ring is a pair of shared-memory queues. The caller pushes submission entries, tells the kernel once
that they are there, and later collects completions. On an NVMe device with real queue depth, eight
scattered reads cost close to one.

This is the argument that the native layer is worth having at all. Two of the other three capabilities
that were supposed to need Rust — `O_DIRECT` and hole punching — turned out to be libc calls that FFM
reaches directly ([NATIVE_FFM.md §9](NATIVE_FFM.md)). Wrapping a ring is genuinely more: shared-memory
queue management, submission and completion state, and buffer lifetime rules the caller has to obey.

## 2. The Measurement

`UringIoEngineTest.testHowMuchBatchingBuys`: 64 scattered 4 KiB reads through `O_DIRECT`, 200 rounds
after 20 warm-up rounds, the same offsets and the same buffers for both engines.

| | Time per round |
|---|---|
| `pread` loop (`DirectIoEngine`) | 2,942 µs |
| **`io_uring` batch (`UringIoEngine`)** | **195 µs** |
| | **15.1x** |

An earlier run on the same machine reported 3,103 µs against 250 µs, or 12.4x. Both are the same
result; the spread is what a shared machine does to a microbenchmark, and neither number should be
quoted to three significant figures.

**The test prints this rather than asserting it**, deliberately. What the ring wins depends on the
device and on whether the pages are resident: against a warm page cache there is nothing to overlap
and the ring wins nothing. A gate on the speedup would be measuring the machine, not the engine.

### 2.1 What the number is not

**It is not available to a traversal, and no amount of wiring would make it so.** A chain walk costs
94 ns per hop ([GRAPH_BENCHMARK.md §7](GRAPH_BENCHMARK.md)), and every hop has to complete before the
next address is known. Queue depth is worth nothing when you can only ask one question at a time. Only
a cheaper hop helps a pointer chase.

The ring pays where the reads are **independent and known in advance**: a batch of pages a scan is
about to want, a group of log blocks, a set of segments to check. That is a real shape in this engine
— it is just not the shape of the code that exists today.

## 3. What Is Built

### 3.1 The Rust side

[`native-rust-lib/src/ring.rs`](../graph-core/native-rust-lib/src/ring.rs), on the `io-uring` crate,
about 260 lines. `Ring` holds the ring and an `in_flight` count; `Cqe` is a 16-byte `#[repr(C)]`
completion of `{ u64 user_data; i32 result; u32 flags; }`.

```rust
Ring::new(depth)                              // clamped to 1..=4096, rounded up to a power of two
submit_read(fd, buf, len, offset, user_data)  // false when the queue is full — backpressure, not an error
submit_write(fd, buf, len, offset, user_data)
submit_fsync(fd, user_data)
submit() / submit_and_wait(min_complete)
reap(out, max)                                // never blocks
```

**The surface owns no policy.** Create a ring, submit, reap. Which pages to fetch, in what order, and
what to do with a short read all stay in Java. That is what keeps the Rust side small enough to review
and means a change of strategy is not a change of language.

### 3.2 The Java side

[`UringIoEngine`](../graph-core/src/main/java/io/metaloom/graph/core/io/UringIoEngine.java) implements
`IoEngine` and delegates `map`, `open`, `alignment` and the counters straight to `DirectIoEngine`.
**The only method it actually overrides is `readBatch`**, and that is the entire point of the class:
the default `readBatch` on the interface is a loop over `read`, so the batch method exists on the
interface only because this implementation can do better.

Three details are load-bearing:

- **Waves.** A caller may pass any number of requests. More than the ring is deep is submitted in
  waves rather than refused, and a submission queue that fills early is treated as backpressure —
  submit what is queued, reap, continue.
- **A zero reap is not an end condition.** Everything submitted has already been waited for, so zero
  completions means they are not yet visible; the engine asks the kernel again rather than spinning
  on the queue.
- **`user_data` is the request index**, so a completion is matched to its buffer without a map and
  out-of-order completion is free.

Single-shot `read` goes through the ring too, for consistency, and gains nothing.

### 3.3 The ABI check

`NativeBindings` declares the `Cqe` layout in Java and asserts it against `mlg_cqe_size` and
`mlg_cqe_offset` probes at startup. A disagreement **disables the engine** rather than being trusted:
reaping at the wrong stride would attribute results to the wrong requests, silently, and a read that
returns the wrong bytes into the wrong buffer is the worst failure this codebase can have.

`UringIoEngine.isSupported()` is the whole negotiation — the crate loaded, the ABI agrees, and
`O_DIRECT` works here. An old kernel, a restricted container, or a build without cargo all answer
false, and the caller uses another engine.

## 4. Why Nothing Calls It

**The data path is a mapping.** `SegmentFile` maps each segment file and hands out slices; a traversal
touches an address and takes a page fault. There is no `read` for `readBatch` to receive, so there is
nothing to batch — not because the wiring is missing, but because the operation does not exist on that
path.

The primitive that *does* overlap faults on a mapping is `madvise(MADV_WILLNEED)`, which is capability
N3, a plain libc call through FFM, exposed as `IoEngine.prefetch` and used by `SegmentFile` and
`SegmentSet`. So the prefetch job people assume the ring is doing is being done — by a different
primitive, for a different reason.

## 5. The Gaps, Stated Plainly

- **`IoEngineKind` has `MMAP`, `DIRECT` and `AUTO`.** There is no `URING`. The ring cannot be selected
  by configuration; it can only be constructed directly, which only a test does.
- **The Java binding covers five of the eight ring functions.** `mlg_ring_new`, `mlg_ring_free`,
  `mlg_ring_submit_read`, `mlg_ring_submit` and `mlg_ring_reap` are bound. `mlg_ring_submit_write`
  and `mlg_ring_submit_fsync` **are exported by the crate and not looked up in Java** — the write path
  is available in Rust and unreachable from Java.
- **The crate has no ordering primitive.** Neither `submit_write` nor `submit_fsync` sets
  `IOSQE_IO_LINK`, so the two cannot yet be submitted as an ordered pair. For a log that is a
  correctness gap and not an optimisation gap — see [TASKS.md URING-3](TASKS.md).
- **No completion is ever reaped on another thread.** `readBatch` submits and waits inside one call,
  which is a batch and not asynchrony. Real overlap with computation needs a caller that submits, does
  something else, and reaps later — and no such caller exists.
- **Never run against a device under load**, or against anything but this machine's NVMe with a warm
  file. The 15x is a best case measured in favourable conditions.

**The work items behind this list live in [TASKS.md](TASKS.md), URING-1 through URING-7** — what each
gap would cost to close, what would change the verdict, and what "done" means. They are not repeated
here.

## 6. The Caller It Is Waiting For

### 6.1 The write-ahead log, under group commit

The WAL is the one part of the system that does I/O **through a descriptor** rather than through a
mapping. Its direct path writes whole aligned blocks through `DirectIoEngine`
([WAL.md](WAL.md), `DirectWalTest`), and `WalSyncMode.GROUP_COMMIT` already exists.

That is the ring's shape — several transactions' blocks plus the `fsync`, submitted together — but
**the log as it stands does not present it**, in three ways that [TASKS.md §1](TASKS.md) sets out with
the code references:

- the direct path is **off by default** (`AUTO` resolves to buffered, deliberately, because
  pad-to-block costs a block per fsync);
- `DirectLog` already coalesces records into one aligned buffer, so a commit is typically one `pwrite`
  and one `fsync` rather than the N scattered writes a ring merges;
- commits **serialize on the writer lock**, so there is never more than one commit's bytes pending;
  `GROUP_COMMIT` today skips fsyncs on an interval rather than grouping committers.

So the WAL is still the most plausible first caller, and it is further away than "bind two functions".
The prerequisite is [URING-2](TASKS.md) — durability outside the writer lock — which is worth doing
for its own sake and would be, before the ring is involved at all.

### 6.2 A data path that is not a mapping

A mapping ties the engine to the OS page cache and its eviction policy, and gives no way to express
"fetch these eight". If that ever becomes the binding constraint at scale, a buffer-pool data path
would make every page fetch a real read through a descriptor — and then `readBatch` has a caller on
the hottest path in the system, and the 15x becomes a number about queries.

That is a design change, not a wiring change, and it is not planned
([TASKS.md URING-7](TASKS.md), gated on the measurement in URING-6). The ring is kept because it is
cheap to keep and expensive to rediscover.

## 7. Safety Rules

- **A buffer handed to the ring must stay alive and unmoved until its completion is reaped.** The
  kernel writes into it asynchronously. Java-side that means a segment from an `Arena` via
  `FileHandle.buffer`, **never** a heap segment, where a copying collector could move it under an
  in-flight read.
- **Never `Linker.Option.critical(true)` on a ring call.** Critical is for short, non-blocking, pure
  computation. Every function here can block or enter the kernel.
- **`mlg_ring_free` is paired with the owning Java object's `close()`**, and the ring's `Arena` is
  store-scoped and shared, never `ofAuto` — a native peer freed by the GC is a use-after-free waiting
  for a schedule.
- **A negative completion result is an errno**, not a short read. `readBatch` raises it as an
  `IOException` naming the request index rather than returning a wrong byte count.

## 8. Tests

| Test | Covers |
|---|---|
| `UringIoEngineTest.testTheRingReadsWhatTheOtherEnginesWrote` | 16 scattered reads through the ring return byte-for-byte what the file holds — the differential check against `DirectIoEngine` and `MmapIoEngine` |
| `UringIoEngineTest.testABatchLargerThanTheRingIsServedInWaves` | 40 requests at depth 8: all served, none refused |
| `UringIoEngineTest.testHowMuchBatchingBuys` | The measurement in §2. Prints, does not gate |
| `ring.rs` unit tests | Read and write through a real ring, from Rust, run by `cargo test` inside `mvn test` |
| `NativeBindingsTest` | The `Cqe` layout matches the crate's own `sizeof`/`offsetof` |

Every one of these **skips rather than fails** where `io_uring` is unavailable — an old kernel or a
restricted container is a legitimate environment, and the Java side falls back. `miri` cannot run any
of the ring tests: it interprets Rust and has no kernel, so `io_uring_setup` is unsupported rather
than failing. The raw-pointer entry points are covered under `miri` separately, in `lib.rs`.

## 9. In a Native Image

The ring survives ahead-of-time compilation. A GraalVM binary reports
`capabilities = crc32c,edge-codec,xxh3,lz4,io_uring` — the crate is unpacked from the image's
resources, `dlopen`ed, ABI-checked and found to support the ring, exactly as on the JVM. Its five
bound functions are registered in graph-core's `reachability-metadata.json` along with everything
else ([GRAALVM.md §3](GRAALVM.md)).

**It has not been exercised there**, for the same reason it is not exercised on the JVM: no caller.

## 10. Key Files

| File | Purpose |
|---|---|
| `graph-core/native-rust-lib/src/ring.rs` | The ring: submit read/write/fsync, submit, reap |
| `graph-core/native-rust-lib/src/lib.rs` | The `extern "C"` surface, `mlg_ring_*` |
| `graph-core/src/main/java/…/io/UringIoEngine.java` | `readBatch`, waves, completion matching |
| `graph-core/src/main/java/…/nativelib/NativeBindings.java` | The `Cqe` layout and its ABI assertion |
| `graph-core/src/test/java/…/nativelib/UringIoEngineTest.java` | Correctness and the measurement |

Related: [TASKS.md](TASKS.md) for the work items behind §5 and §6,
[NATIVE_FFM.md](NATIVE_FFM.md) for the capability list and the fallback contract,
[SSD_IO.md](SSD_IO.md) for where the engines sit in the I/O strategy, [WAL.md](WAL.md) for the caller
in §6.1, [GRAALVM.md](GRAALVM.md) for the native binary.

---

_Git HEAD revision: `0970e6b`_
_Last updated: 2026-08-16 (work items deferred to TASKS.md; §6.1 corrected against the code)_
