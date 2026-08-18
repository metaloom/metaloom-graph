# Native Layer — Rust via the Foreign Function and Memory API

The project is a **native-code backed** graph storage layer. This file defines what lives in native
code, why, and the rules for the Java-to-native boundary.

**The native language is Rust.** The existing `native-rust-lib` crate is the seed. The C sources
that used to sit under `native-c-lib/` were an unused `mmap` demo from early exploration and have
been deleted.

**Status**: the crate is **built, tested, linted and measured**. `cargo test` and
`cargo clippy -D warnings` run inside `mvn test`; `NativeCodecTest` and `UringIoEngineTest` hold the
Rust side to the Java reference and to the claims below. The ABI is version **2**.

**N1, io_uring, is built and it is the one that pays.** It now has a file of its own —
[IO_URING.md](IO_URING.md) — covering the ring, the engine, the measurement and the caller it is
waiting for. 64 scattered 4 KiB reads through `O_DIRECT`:

| | Time |
|---|---|
| `pread` loop | 2,942 µs |
| **io_uring batch** | **195 µs — 15.1x** |

(An earlier run of the same test on the same machine gave 3,103 µs against 250 µs, or 12.4x. The
spread is what a shared machine does to a microbenchmark.)

That upholds the claim in section 1 that this is the largest lever in the design, and it is the only
capability here the JVM cannot approach: a page fault and a `FileChannel.read` are both synchronous,
so eight pages cost eight round trips whatever language sits above them. `UringIoEngine.readBatch` is
the whole interface; single-shot reads go through it for consistency and gain nothing.

**But nothing in the engine issues a batch, and on the data path nothing can.** The data path is a
mapping, so a traversal never reads through a descriptor — it touches addresses and takes page faults.
There is no read for `readBatch` to receive. The primitive that overlaps faults on a mapping is
`madvise(MADV_WILLNEED)`, which is capability N3 and is now bound and exposed as `IoEngine.prefetch`
(`PrefetchTest`). **The ring is the right answer for the write-ahead log, which does read and write
through a descriptor, and it becomes the right answer for the data path on the day that path stops
being memory mapped — not before.** Quoting 12.4x as though it were available to a query today would
be wrong in two ways: nothing calls it, and the caller it would serve does not exist.

**A dependent walk cannot use it at all.** A 30,000 hop chain traversal costs 94 ns per hop
([GRAPH_BENCHMARK.md](GRAPH_BENCHMARK.md) section 7), and every hop has to complete before the next
address is known. No queue depth improves a pointer chase; only a lower per-node cost does.

**N2 is built, and it did not need the crate.** `open` with `O_DIRECT`, `pread`, `pwrite`, `fsync`
and `posix_fadvise` are libc calls that FFM reaches directly. `DirectIoEngine` and `MmapIoEngine` are
held to byte-for-byte equivalence by `IoEngineTest`. **The write-ahead log now runs on it**
(`DirectWalTest`), through an aligned staging buffer that writes whole blocks and fills the tail of
the last one with a `TYPE_PADDING` record, so a partly filled block is never rewritten and a torn
write cannot destroy an acknowledged commit. The padding is a real CRC-covered record rather than a
gap, because an LSN is a byte offset and a gap would make one ambiguous.

It is **not** the default. Under `FSYNC_ON_COMMIT` every commit rounds up to a block, wasting up to
4,095 bytes each, so `AUTO` still resolves to buffered; direct is worth choosing with group commit, or
when the log is competing with the data path for page cache. Bypassing the cache for a file nobody
reads twice is right in principle and costs write amplification in practice, and which of those wins
depends on the commit size.

**N4 still does not justify itself, and its headline number had to be corrected:**

| Hot loop | Java | Rust | Verdict |
|---|---|---|---|
| Edge codec, 1000 entries | 6,882 ns | 4,978 ns | 1.38x faster, boundary crossing included |
| CRC32C, 8 KiB page | 113 ns | 886 ns | **7.8x slower** |
| lz4, 64 KiB of document-shaped data | — | 26.9 percent of original | 3.7x smaller; the JDK has no lz4, and this one is **wired into `BlobStore`** |

The earlier figure of **200x slower** for CRC32C is withdrawn: it compared a hardware intrinsic
against a bitwise loop, which measured the two implementations and not the two languages. Both sides
now use the CRC32 instruction and Rust still loses, by the cost of the downcall against a call the
JIT inlines. Only lz4 is worth anything, and it is a space win rather than a speed one — measured,
not wired in, because compressing blobs is a format change and blobs are 615 MiB of a 6.2 GiB store.

---

## 1. Why Native At All

The JVM is excellent at most of this work. Native code is justified only where the JVM cannot
express the operation, or where the gap is large and measured. Four such places:

| # | Capability | Why the JVM cannot do it |
|---|-----------|--------------------------|
| N1 | **io_uring** — batched, asynchronous, high-queue-depth I/O | No JDK API. `FileChannel` and mmap page faults are synchronous and pin a carrier thread; a traversal cannot express "fetch these 8 pages concurrently". This is the single largest performance lever in the design ([SSD_IO.md §4](SSD_IO.md#4-io-engines)). |
| N2 | **`O_DIRECT` with aligned buffers** | `FileChannel` cannot open with `O_DIRECT`, and the JDK gives no guarantee of the 4 KiB buffer alignment it requires. Needed for the WAL and the direct engine. |
| N3 | **Filesystem control**: `fallocate`, `FALLOC_FL_PUNCH_HOLE`, `FICLONE`, `madvise`, `statx` block counts | None are exposed by the JDK. Sparse segments, TRIM and O(1) reflink snapshots all depend on them. |
| N4 | **SIMD hot loops**: CRC32C, xxh3, the adjacency delta+varint codec, lz4 | The JDK has `CRC32C` (hardware-backed) but no vectorised varint codec. The Vector API remains an incubator module; Rust with `std::arch` or a maintained crate is the pragmatic path. This one must be **justified by measurement** before it ships. |

Everything else — record encoding, page cache management, transaction logic, B-trees — stays in
Java. Moving it to native code would trade the JIT and the JDK's memory safety for maintenance
burden and a JNI-shaped boundary cost.

---

## 2. The Native Surface

One crate, `metaloom-graph-native`, one `cdylib`, a deliberately small C ABI. Every function is
`#[no_mangle] pub extern "C"`, takes and returns only primitives and raw pointers, and never
allocates memory that Java must free except through an explicit paired free.

```rust
// ---- lifecycle -------------------------------------------------------------
pub extern "C" fn mlg_version() -> u32;
pub extern "C" fn mlg_capabilities() -> u64;      // bitmask: URING, DIRECT, FICLONE, CRC_HW, ...

// ---- N2/N3: files ----------------------------------------------------------
pub extern "C" fn mlg_open(path: *const u8, len: usize, flags: u32) -> i32;   // returns fd
pub extern "C" fn mlg_close(fd: i32) -> i32;
pub extern "C" fn mlg_fallocate(fd: i32, offset: u64, len: u64, mode: u32) -> i32;
pub extern "C" fn mlg_punch_hole(fd: i32, offset: u64, len: u64) -> i32;
pub extern "C" fn mlg_reflink(src_fd: i32, dst_fd: i32) -> i32;               // FICLONE
pub extern "C" fn mlg_madvise(addr: *mut u8, len: usize, advice: i32) -> i32;
pub extern "C" fn mlg_allocated_blocks(fd: i32, out: *mut u64) -> i32;        // statx
pub extern "C" fn mlg_alloc_aligned(len: usize, align: usize) -> *mut u8;
pub extern "C" fn mlg_free_aligned(ptr: *mut u8, len: usize, align: usize);

// ---- N1: io_uring ----------------------------------------------------------
pub extern "C" fn mlg_ring_new(depth: u32, flags: u32) -> *mut MlgRing;
pub extern "C" fn mlg_ring_free(ring: *mut MlgRing);
pub extern "C" fn mlg_ring_submit_read(ring: *mut MlgRing, fd: i32, buf: *mut u8,
                                       len: u32, offset: u64, user_data: u64) -> i32;
pub extern "C" fn mlg_ring_submit_write(ring: *mut MlgRing, fd: i32, buf: *const u8,
                                        len: u32, offset: u64, user_data: u64) -> i32;
pub extern "C" fn mlg_ring_submit_fsync(ring: *mut MlgRing, fd: i32, user_data: u64) -> i32;
pub extern "C" fn mlg_ring_submit(ring: *mut MlgRing) -> i32;
pub extern "C" fn mlg_ring_reap(ring: *mut MlgRing, out: *mut MlgCqe,
                                max: u32, min_complete: u32, timeout_ns: u64) -> i32;

// ---- N4: hot loops ---------------------------------------------------------
pub extern "C" fn mlg_crc32c(data: *const u8, len: usize, seed: u32) -> u32;
pub extern "C" fn mlg_xxh3(data: *const u8, len: usize, seed: u64) -> u64;
pub extern "C" fn mlg_edges_encode(src: *const u64, count: u32,
                                   dst: *mut u8, dst_cap: u32) -> i32;   // bytes written, or -needed
pub extern "C" fn mlg_edges_decode(src: *const u8, src_len: u32,
                                   dst: *mut u64, dst_cap: u32) -> i32;  // entries written, or -needed
pub extern "C" fn mlg_lz4_compress(src: *const u8, src_len: usize,
                                   dst: *mut u8, dst_cap: usize) -> isize;
pub extern "C" fn mlg_lz4_decompress(src: *const u8, src_len: usize,
                                     dst: *mut u8, dst_cap: usize) -> isize;
```

`MlgRing` is opaque to Java. `MlgCqe` is a 16-byte `#[repr(C)]` struct of `{ u64 user_data; i32
result; u32 flags; }`, read on the Java side with a `MemoryLayout`.

### 2.1 Rust crate structure

```
native-rust-lib/                      (renamed: metaloom-graph-native)
  Cargo.toml                          crate-type = ["cdylib"]
  build.rs                            capability probing, target-feature detection
  src/
    lib.rs                            the extern "C" surface only; no logic
    ffi.rs                            pointer/slice conversion helpers, error mapping
    fs.rs                             N2, N3
    ring.rs                           N1
    codec/
      mod.rs
      crc.rs                          crc32c, xxh3
      edges.rs                        delta + varint, scalar + SIMD paths
      lz4.rs
```

Dependency policy: **as few crates as possible**, all vendored and license-audited. `io-uring` and
`libc` are expected; the compression and hash implementations should be crates rather than hand-
written (`lz4_flex`, `xxhash-rust`, `crc32c`), because hand-rolling them is how you get subtle
corruption.

`lib.rs` contains no logic — it is a translation layer that converts raw pointers to slices, calls
into the safe modules, and maps `Result` to an `i32`. All `unsafe` is confined to `ffi.rs` and the
two syscall modules, each block carrying a `// SAFETY:` comment stating the invariant the caller
must uphold.

### 2.2 Error convention

Every fallible function returns `i32`: `0` on success, or a **negated errno** (`-EINVAL`, `-EIO`).
Never `panic!` across the FFI boundary — a panic through `extern "C"` is undefined behaviour. Every
entry point that could panic is wrapped in `std::panic::catch_unwind` and returns `-EFAULT` instead.
The crate sets `panic = "abort"` in the release profile as a second line of defence.

---

## 3. The Java Binding

```java
final class NativeLib {
    private static final SymbolLookup LOOKUP;
    private static final Linker LINKER = Linker.nativeLinker();

    static final MethodHandle CRC32C = LINKER.downcallHandle(
        LOOKUP.findOrThrow("mlg_crc32c"),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT),
        Linker.Option.critical(true));                 // no thread-state transition
    ...
}
```

Rules:

- **`static final MethodHandle`, resolved once at class init.** Never look a symbol up per call. The
  PoC's `FFMLibraryTest` re-resolves the linker, the library and three handles inside the test body;
  that is fine for a demo and wrong for a hot path.
- **`Linker.Option.critical(true)`** for short, non-blocking, pure-computation calls (`mlg_crc32c`,
  `mlg_edges_decode`). It removes the Java-to-native thread-state transition and allows passing
  heap segments directly. **Never** use it for anything that can block or that calls back into the
  JVM — that includes every syscall wrapper.
- **`Arena` discipline**: store-scoped `Arena.ofShared()` for mappings and for the ring; a
  per-operation `Arena.ofConfined()` for scratch. Never `Arena.ofAuto()` for anything with a native
  peer, because the free then depends on the GC.
- **Every native allocation has an owning Java object with a `close()`**, and the pairing is checked
  in tests by a leak counter exported from Rust (`mlg_live_allocations()` in debug builds).
- **`MemorySegment.reinterpret(len, arena, cleanup)`** is the correct way to attach a lifetime to a
  pointer returned from Rust. The PoC's `pointer.reinterpret(4)` without an arena leaks by design.
- **Layouts for `#[repr(C)]` structs are declared once**, in `NativeBindings`, with explicit
  little-endian value layouts and explicit padding, and are asserted against `mlg_sizeof_*` probes at
  startup so that a Rust layout change cannot silently misalign the Java side.

---

## 4. The Fallback Contract

**Every native function has a pure-Java fallback, and the store is fully functional without the
native library.** This is non-negotiable: it keeps the project testable on any platform, it makes
the native layer optional for embedders, and it gives every native implementation a differential
test partner.

| Native | Java fallback |
|--------|---------------|
| `mlg_crc32c` | `java.util.zip.CRC32C` |
| `mlg_xxh3` | A scalar Java xxh3, or `CRC32C` where the hash is only used for bucketing |
| `mlg_edges_encode/decode` | Scalar Java varint loop, roughly 3x slower |
| `mlg_lz4_*` | No compression (the flag in the blob header records that it is uncompressed) |
| `mlg_fallocate` / `mlg_punch_hole` | No-op; segments are simply not sparse and space is not returned |
| `mlg_reflink` | Byte copy |
| `mlg_ring_*` | The `mmap` engine, no queue depth |
| `mlg_alloc_aligned` | `Arena.allocate(len, align)` |
| `mlg_madvise` / `mlg_allocated_blocks` | No-op / `Files.size()` |

Selection is per capability, not all-or-nothing: a platform with `io_uring` but without `FICLONE`
uses the ring and falls back on the reflink. `mlg_capabilities()` reports the bitmask; the store logs
exactly which path each capability took at open.

`native.enabled=false` forces the fallback everywhere, and CI runs the **whole suite twice**, once
each way, asserting identical results.

The contract holds in a GraalVM native image too: an image built from a jar with no `.so` in it starts,
serves and queries on the pure-Java path, and one built with the crate reports `ABI 2` and all five
capabilities from inside the binary. See [GRAALVM.md §8.2](GRAALVM.md).

---

## 5. Build and Packaging

| Item | Decision |
|------|----------|
| Build tool | `cargo`, driven from Maven by `exec-maven-plugin` in `graph-core`, at four phases: `generate-resources` builds, `process-classes` packages the `.so`, `test` runs the crate's tests and clippy, `clean` runs `cargo clean` |
| Toolchain | Pinned via `rust-toolchain.toml`; stable channel |
| Targets | `x86_64-unknown-linux-gnu` and `aarch64-unknown-linux-gnu` initially; macOS and Windows drop io_uring and use the fallback |
| Packaging | The `.so` is placed in `src/main/resources/native/<os>-<arch>/libmetaloom_graph_native.so` and shipped inside the jar |
| Loading | Extract to a temp dir on first use, then `SymbolLookup.libraryLookup(path, arena)`. Honour `-Dmetaloom.graph.native.path` for a developer override |
| Absence | A missing or unloadable library is **logged at INFO and falls back**, never fatal, unless `-Dmetaloom.graph.native.required=true`, which `NativeBindingsTest` turns into a failing test rather than a skipped one. It is off by default and set by the native CI leg, whose whole subject is the native path and which otherwise passes unchanged when the crate did not build |
| Ahead of time | Every downcall signature is declared in `META-INF/native-image/.../reachability-metadata.json` so the engine works inside a GraalVM binary. Adding a binding without adding its shape is caught by `ForeignDowncallMetadataTest` in the ordinary `mvn test` — see [GRAALVM.md](GRAALVM.md) |
| CI | The `rust` job builds and tests the crate and runs `cargo clippy -D warnings`, on `x86_64-unknown-linux-gnu` only — one target, not a per-target matrix, and `cargo miri test` is a manual command in `tools/verify-native.sh` rather than a CI step. The Java `test` job runs the suite twice, once with `-Dmetaloom.graph.native.required=true` and once with `-Dnative.skip=true` |

**Absence must never be fatal.** The build detects a missing `cargo`, says so, and lets the Java build
and the full suite proceed on the fallback path — a build that hard-failed without Rust would make the
project unbuildable for a contributor who only touches Java.

That path is also reachable deliberately: `mvn test -Dnative.skip=true` turns the native layer off
even where cargo *is* present, which is how the fallback is actually checked rather than assumed.
Measured, running both CI matrix legs back to back: `graph-core` reports **522 tests passing with 27
skipped rather than 3** — the extra 24 are the native-specific tests declining to run, which is the
correct behaviour and is visible in the count. That the difference is *only* visible in the count is
the reason `-Dmetaloom.graph.native.required=true` exists: a skipped test and a passing one exit the
same way.

The flag also stops the tests **loading** a library an earlier build left behind, which it did not
always do. `NativeCapabilities` searches paths relative to the working directory before it searches
the classpath, so on any machine that had ever built the crate, `-Dnative.skip=true` skipped the build
and then loaded `native-rust-lib/target/release/libmetaloom_graph_native.so` anyway: the run that was
meant to prove the pure-Java path exercised the native one. `NativeBindingsTest` caught it by failing
on a library that was loaded but not packaged. The flag now sets
`metaloom.graph.native.enabled=false` for surefire as well, and a command-line
`-Dmetaloom.graph.native.enabled=...` still outranks it.

`-Poffline` passes cargo's offline flag for a warm registry with no network, and `-DskipTests` skips
the crate's tests along with the Java ones rather than only half of what its name implies.

The PoC's `native-c-lib/build.sh` (a bare `gcc src/main.c`, which does not even produce a shared
library) and `main.c` are deleted in Phase 6.

---

## 6. Safety and Review Rules

- All `unsafe` blocks carry a `// SAFETY:` comment naming the invariant.
- No `extern "C"` function may panic; `catch_unwind` at every boundary plus `panic = "abort"`.
- Pointers from Java are validated for null and for length before use; a zero-length slice is
  represented as a null pointer with length 0 and handled explicitly (`slice::from_raw_parts` with a
  null pointer is UB even at length 0).
- No Rust code retains a Java-supplied pointer beyond the call, except the ring, whose buffers are
  explicitly pinned by the Java caller for the lifetime of the submission.
- `cargo miri test` runs over the codec modules in CI; `cargo fuzz` targets exist for
  `mlg_edges_decode` and `mlg_lz4_decompress`, because those two parse untrusted-in-principle bytes
  from disk.
- The codec's decode path must be **robust against corrupt input**: never trust a length read from a
  page. A corrupt block must produce an error, never an out-of-bounds read.

---

## 7. Conventions and Gotchas

- **`critical(true)` is a footgun.** It is correct for `mlg_crc32c`; it will deadlock or crash the
  VM if applied to anything that blocks, allocates, or takes a lock held elsewhere.
- **The io_uring buffer lifetime is the submission, not the call.** A buffer submitted for a read
  must remain valid and unmoved until its completion is reaped. Java-side, that means a segment from
  a shared arena, never a heap segment.
- **Do not pass Java heap segments to syscall wrappers.** Only `critical` calls may take heap
  segments, and only for pure computation.
- **`errno` is thread-local.** Return the negated errno from Rust; do not attempt to read `errno`
  from Java after the call.
- **Alignment for `O_DIRECT` is device-dependent.** 4 KiB is safe in practice; query the logical
  block size where possible and record it in the manifest.
- **Feature-detect at runtime, not at compile time.** A binary built on a machine with AVX-512 must
  not crash on one without it; use `is_x86_feature_detected!`.
- **Version the ABI.** `mlg_version()` is checked at load; a mismatch falls back to Java rather than
  crashing.

---

## 8. Test Setup

- **Differential tests**: for every N4 function, a property-based test asserting the native and Java
  implementations agree byte for byte over random and adversarial inputs. These are the highest-value
  tests in the whole project.
- **Full-suite dual run**: CI runs the entire test suite with `native.enabled` true and false.
- **Leak test**: after a full suite run, `mlg_live_allocations()` must be zero.
- **Corrupt-input test**: feed `mlg_edges_decode` and `mlg_lz4_decompress` random and mutated bytes;
  assert an error return and no crash, under `miri` and `fuzz`.
- **Capability matrix test**: force each capability off individually and assert the store still opens
  and passes the functional suite.
- **Ring test**: submit 10^6 reads at depth 128 against a known file, assert every completion matches
  the expected bytes and `user_data`.
- **ABI test**: assert every Java `MemoryLayout` size and offset matches the Rust `sizeof`/`offsetof`
  probes.

---

## 9. Progress Assessment

- [x] `NativeLib`: `fallocate` punch-hole, `O_DIRECT` open, `pread`, `pwrite`, `fsync`,
      `posix_fadvise`, all through FFM downcalls against libc
- [x] `IoEngine`, `MmapIoEngine`, `DirectIoEngine` — the seam deferred from Phase 1, with the two
      implementations held to byte-for-byte equivalence by `IoEngineTest`
- [x] `PageFaults`: the process major fault counter, which is the only honest device-read counter on
      a memory-mapped data path
- [x] `NativeCapabilities`: version checked before any other symbol, a mismatched ABI refused
- [x] N4 codecs written in Rust, verified byte-identical to Java, and **measured to be not worth
      shipping** — see the header of this file
- [x] Graceful skip of the cargo build; the whole suite green with the native path disabled
- [ ] The WAL on the direct engine. Needs an aligned block buffer and a padding record type; a partly
      filled block must never be rewritten in place, or a torn write destroys an acknowledged commit
- [x] N1: io_uring — the crate's ring, `UringIoEngine.readBatch`, the ABI-checked completion layout, and
      the 15x measurement. **Built and called by nothing but its own test**, because the data path is a
      mapping and issues no reads; the write-ahead log under `GROUP_COMMIT` is the caller it is waiting
      for, and `mlg_ring_submit_write`/`mlg_ring_submit_fsync` are exported by the crate and not yet
      bound in Java. [IO_URING.md](IO_URING.md)
- [ ] Cursor prefetch through the ring — needs a data path that is not memory mapped, which is a design
      change rather than a wiring one
- [ ] N3: `madvise`, `statx` block counts, `FICLONE` reflink snapshots
- [ ] `mlg_xxh3`, SIMD codec paths, lz4 into `BlobStore` — blocked on `cargo`
- [ ] Crate restructure, `module-info.java`, `cargo clippy`/`miri`/`fuzz` in CI — blocked on `cargo`

**What the phase established**: the JVM-cannot-express argument holds for exactly three things here —
hole punching, `O_DIRECT` and io_uring — and two of them turned out to be libc calls that FFM reaches
without a line of Rust. The crate is left in place for io_uring, which is the one that needs it.

---

## 10. Key Classes Reference (target)

| Class | Package | Purpose |
|-------|---------|---------|
| `NativeLib` | `io.metaloom.graph.core.native` | Library loading, capability probing, `MethodHandle` constants |
| `NativeBindings` | `io.metaloom.graph.core.native` | `MemoryLayout`s for `#[repr(C)]` structs, ABI assertions |
| `Crc` | `io.metaloom.graph.core.util` | CRC32C with native and JDK paths |
| `EdgeCodec` | `io.metaloom.graph.core.store.adj` | Adjacency codec with native and Java paths |
| `UringIoEngine` | `io.metaloom.graph.core.io` | Batched I/O over the ring |

---

_Git HEAD revision: `c016a41`_
_Last updated: 2026-08-13 (synced with the Phase 0 to 3 implementation)_
