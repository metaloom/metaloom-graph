# Native Layer — Rust via the Foreign Function and Memory API

The project is a **native-code backed** graph storage layer. This file defines what lives in native
code, why, and the rules for the Java-to-native boundary.

**The native language is Rust.** The existing `native-rust-lib` crate is the seed. The C sources
that used to sit under `native-c-lib/` were an unused `mmap` demo from early exploration and have
been deleted.

**Status**: not yet implemented. The engine runs entirely on the pure-Java fallback path described in
section 4, and `cargo` is not installed in the current development environment, so the Maven
integration must skip the native build gracefully rather than fail.

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

---

## 5. Build and Packaging

| Item | Decision |
|------|----------|
| Build tool | `cargo`, driven from Maven by `exec-maven-plugin` bound to `generate-resources` |
| Toolchain | Pinned via `rust-toolchain.toml`; stable channel |
| Targets | `x86_64-unknown-linux-gnu` and `aarch64-unknown-linux-gnu` initially; macOS and Windows drop io_uring and use the fallback |
| Packaging | The `.so` is placed in `src/main/resources/native/<os>-<arch>/libmetaloom_graph_native.so` and shipped inside the jar |
| Loading | Extract to a temp dir on first use, then `SymbolLookup.libraryLookup(path, arena)`. Honour `-Dmetaloom.graph.native.path` for a developer override |
| Absence | A missing or unloadable library is **logged at INFO and falls back**, never fatal, unless `native.required=true` |
| CI | A matrix that builds the crate per target; `cargo clippy -D warnings`, `cargo test`, `cargo miri test` on the codec modules |

**Prerequisite note**: `cargo` is not currently installed in this development environment. The Maven
build must therefore detect its absence, skip the native build, log it clearly, and let the Java
build and the full test suite proceed on the fallback path. A build that hard-fails without Rust
would make the project unbuildable for contributors who only touch Java.

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

- [x] `NativeLib`: `static final MethodHandle`s, capability probing, pure-Java fallback
- [x] N3 via libc: `punch_hole` (`fallocate`), proven to release real blocks by `NativeReclaimTest`
- [x] Maven integration with a graceful skip when `cargo` is absent
- [x] `metaloom.graph.native.enabled=false` forces the fallback everywhere
- [x] Crate renamed and restructured per §2.1
- [x] Delete `native-c-lib/`
- [~] `mlg_version` / `mlg_capabilities`, `mlg_crc32c`, `mlg_edges_encode` / `mlg_edges_decode`: **written in Rust,
      never compiled** — cargo is absent here, so the crate is unverified source and is not loaded by Java
- [ ] Differential tests of the Rust codec against the Java reference (blocked on a Rust toolchain)
- [ ] N4: SIMD paths, xxh3, lz4
- [ ] N3: `madvise`, `statx`, `reflink`
- [ ] N2: `O_DIRECT` open and aligned allocation
- [ ] N1: io_uring ring lifecycle, submit, reap
- [ ] `UringIoEngine` on top of N1
- [ ] Multi-target build and in-jar packaging
- [ ] `cargo clippy -D warnings`, `miri`, `fuzz` in CI

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
