//! The C ABI surface of the metaloom graph storage native layer.
//!
//! This module contains no logic: it converts raw pointers to slices, calls into the safe modules and maps results to
//! an `i32`. Every fallible function returns 0 on success or a negated errno. No function may panic across the
//! boundary, so each entry point is wrapped in `catch_unwind`.
//!
//! Every entry point is `unsafe` because it dereferences caller-supplied pointers. That changes nothing about the
//! symbol or the ABI — the Java side reaches these through the FFM linker, which has no notion of Rust safety — and
//! it is what lets `cargo clippy -D warnings` pass without silencing the lint that exists to catch exactly this.
//!
//! See spec/NATIVE_FFM.md.

mod codec;
mod ring;

use std::panic::catch_unwind;
use std::slice;

/// Bumped when the ABI changes. The Java side checks it at load and falls back on a mismatch.
pub const MLG_ABI_VERSION: u32 = 2;

pub const CAP_CRC32C: u64 = 1 << 0;
pub const CAP_EDGE_CODEC: u64 = 1 << 1;
pub const CAP_XXH3: u64 = 1 << 2;
pub const CAP_LZ4: u64 = 1 << 3;
pub const CAP_URING: u64 = 1 << 4;

const EFAULT: i32 = -14;
const EINVAL: i32 = -22;

#[no_mangle]
pub extern "C" fn mlg_version() -> u32 {
    MLG_ABI_VERSION
}

/// What this build can actually do. `CAP_URING` is reported only when a ring can really be created here: the crate
/// may be built on a machine with io_uring and run on a kernel or in a container without it.
#[no_mangle]
pub extern "C" fn mlg_capabilities() -> u64 {
    let mut caps = CAP_CRC32C | CAP_EDGE_CODEC | CAP_XXH3 | CAP_LZ4;
    if ring::Ring::new(4).is_ok() {
        caps |= CAP_URING;
    }
    caps
}

// ---------------------------------------------------------------- N4: hashes

/// CRC32C over a byte range. Matches `java.util.zip.CRC32C` exactly; the Java implementation stays the reference.
///
/// # Safety
///
/// `data` must point to at least `len` readable bytes.
#[no_mangle]
pub unsafe extern "C" fn mlg_crc32c(data: *const u8, len: usize, seed: u32) -> u32 {
    if data.is_null() && len != 0 {
        return 0;
    }
    catch_unwind(|| {
        let bytes = if len == 0 {
            &[][..]
        } else {
            // SAFETY: the caller guarantees `data` points to at least `len` readable bytes for the duration of the
            // call, and never mutates them concurrently.
            unsafe { slice::from_raw_parts(data, len) }
        };
        codec::crc::crc32c(bytes, seed)
    })
    .unwrap_or(0)
}

/// XXH3-64. Opaque to the Java side and never persisted.
///
/// # Safety
///
/// `data` must point to at least `len` readable bytes.
#[no_mangle]
pub unsafe extern "C" fn mlg_xxh3(data: *const u8, len: usize, seed: u64) -> u64 {
    if data.is_null() && len != 0 {
        return 0;
    }
    catch_unwind(|| {
        let bytes = if len == 0 {
            &[][..]
        } else {
            // SAFETY: as above.
            unsafe { slice::from_raw_parts(data, len) }
        };
        codec::crc::xxh3(bytes, seed)
    })
    .unwrap_or(0)
}

// ---------------------------------------------------------------- N4: the edge codec

/// Encodes packed adjacency entries. Returns the bytes written, or the negated required capacity when `dst` is too
/// small. Byte-for-byte identical to the Java `EdgeCodec`.
///
/// # Safety
///
/// `src` must point to `count` readable `u64`s and `dst` to `dst_cap` writable bytes; the ranges must not overlap.
#[no_mangle]
pub unsafe extern "C" fn mlg_edges_encode(
    src: *const u64,
    count: u32,
    dst: *mut u8,
    dst_cap: u32,
) -> i32 {
    if src.is_null() || dst.is_null() {
        return EFAULT;
    }
    catch_unwind(|| {
        // SAFETY: the caller guarantees both ranges are valid and do not overlap.
        let entries = unsafe { slice::from_raw_parts(src, count as usize) };
        let out = unsafe { slice::from_raw_parts_mut(dst, dst_cap as usize) };
        match codec::edges::encode_edges(entries, out) {
            Ok(written) => written as i32,
            Err(needed) => -(needed as i32),
        }
    })
    .unwrap_or(EFAULT)
}

/// Decodes packed adjacency entries. Returns the number of entries written, or a negative errno. Never reads past
/// `src_len`: a corrupt block must produce an error, not an out-of-bounds read.
///
/// # Safety
///
/// `src` must point to `src_len` readable bytes and `dst` to `dst_cap` writable `u64`s.
#[no_mangle]
pub unsafe extern "C" fn mlg_edges_decode(
    src: *const u8,
    src_len: u32,
    dst: *mut u64,
    dst_cap: u32,
) -> i32 {
    if src.is_null() || dst.is_null() {
        return EFAULT;
    }
    catch_unwind(|| {
        // SAFETY: as above.
        let bytes = unsafe { slice::from_raw_parts(src, src_len as usize) };
        let out = unsafe { slice::from_raw_parts_mut(dst, dst_cap as usize) };
        match codec::edges::decode_edges(bytes, out) {
            Ok(written) => written as i32,
            Err(()) => EINVAL,
        }
    })
    .unwrap_or(EFAULT)
}

// ---------------------------------------------------------------- N4: lz4

/// Compresses into `dst`, returning the bytes written or a negative errno. The block format carries no length header,
/// so the caller must remember the uncompressed size — which the blob header already stores.
///
/// # Safety
///
/// `src` must point to `src_len` readable bytes and `dst` to `dst_cap` writable bytes.
#[no_mangle]
pub unsafe extern "C" fn mlg_lz4_compress(
    src: *const u8,
    src_len: usize,
    dst: *mut u8,
    dst_cap: usize,
) -> isize {
    if src.is_null() || dst.is_null() {
        return EFAULT as isize;
    }
    catch_unwind(|| {
        // SAFETY: the caller guarantees both ranges.
        let input = unsafe { slice::from_raw_parts(src, src_len) };
        let output = unsafe { slice::from_raw_parts_mut(dst, dst_cap) };
        match lz4_flex::block::compress_into(input, output) {
            Ok(written) => written as isize,
            Err(_) => EINVAL as isize,
        }
    })
    .unwrap_or(EFAULT as isize)
}

/// Decompresses into `dst`, returning the bytes written or a negative errno.
///
/// # Safety
///
/// `src` must point to `src_len` readable bytes and `dst` to `dst_cap` writable bytes.
#[no_mangle]
pub unsafe extern "C" fn mlg_lz4_decompress(
    src: *const u8,
    src_len: usize,
    dst: *mut u8,
    dst_cap: usize,
) -> isize {
    if src.is_null() || dst.is_null() {
        return EFAULT as isize;
    }
    catch_unwind(|| {
        // SAFETY: as above.
        let input = unsafe { slice::from_raw_parts(src, src_len) };
        let output = unsafe { slice::from_raw_parts_mut(dst, dst_cap) };
        match lz4_flex::block::decompress_into(input, output) {
            Ok(written) => written as isize,
            Err(_) => EINVAL as isize,
        }
    })
    .unwrap_or(EFAULT as isize)
}

// ---------------------------------------------------------------- ABI self description

/// Byte size of the completion struct the Java side mirrors, so it can assert rather than assume.
///
/// `UringIoEngine` builds a `MemoryLayout` for `Cqe` by hand and reaps completions into an array of them. If the two
/// descriptions ever drift — a field added here, an alignment rule differing between compilers — the Java side would
/// read completions at the wrong stride and attribute results to the wrong requests, silently and only under load.
/// Exporting the size turns that into a startup failure.
#[no_mangle]
pub extern "C" fn mlg_cqe_size() -> usize {
    core::mem::size_of::<ring::Cqe>()
}

/// Byte offset of a field of the completion struct. 0 = user_data, 1 = result, 2 = flags; anything else is -1.
#[no_mangle]
pub extern "C" fn mlg_cqe_offset(field: u32) -> isize {
    let base = core::mem::MaybeUninit::<ring::Cqe>::uninit();
    let ptr = base.as_ptr();
    // SAFETY: addr_of! on an uninitialised struct only computes addresses; no field is read.
    unsafe {
        match field {
            0 => (core::ptr::addr_of!((*ptr).user_data) as isize) - (ptr as isize),
            1 => (core::ptr::addr_of!((*ptr).result) as isize) - (ptr as isize),
            2 => (core::ptr::addr_of!((*ptr).flags) as isize) - (ptr as isize),
            _ => -1,
        }
    }
}

// ---------------------------------------------------------------- N1: io_uring

/// Creates a ring. Returns null when io_uring is unavailable, which the Java side treats as "fall back", not as an
/// error: a container or an old kernel legitimately has none.
#[no_mangle]
pub extern "C" fn mlg_ring_new(depth: u32) -> *mut ring::Ring {
    match catch_unwind(|| ring::Ring::new(depth)) {
        Ok(Ok(r)) => Box::into_raw(Box::new(r)),
        _ => std::ptr::null_mut(),
    }
}

/// # Safety
///
/// `r` must come from `mlg_ring_new` and must not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn mlg_ring_free(r: *mut ring::Ring) {
    if !r.is_null() {
        // SAFETY: the caller guarantees this pointer came from Box::into_raw in mlg_ring_new.
        drop(unsafe { Box::from_raw(r) });
    }
}

/// Queues a read. Returns 1 when accepted, 0 when the submission queue is full.
///
/// # Safety
///
/// `buf` must point to `len` writable bytes that stay alive and unmoved until the completion is reaped.
#[no_mangle]
pub unsafe extern "C" fn mlg_ring_submit_read(
    r: *mut ring::Ring,
    fd: i32,
    buf: *mut u8,
    len: u32,
    offset: u64,
    user_data: u64,
) -> i32 {
    if r.is_null() || buf.is_null() {
        return EFAULT;
    }
    // SAFETY: the caller guarantees the ring pointer and the buffer lifetime.
    let ring = unsafe { &mut *r };
    i32::from(unsafe { ring.submit_read(fd, buf, len, offset, user_data) })
}

/// # Safety
///
/// As `mlg_ring_submit_read`, with a readable buffer.
#[no_mangle]
pub unsafe extern "C" fn mlg_ring_submit_write(
    r: *mut ring::Ring,
    fd: i32,
    buf: *const u8,
    len: u32,
    offset: u64,
    user_data: u64,
) -> i32 {
    if r.is_null() || buf.is_null() {
        return EFAULT;
    }
    // SAFETY: as above.
    let ring = unsafe { &mut *r };
    i32::from(unsafe { ring.submit_write(fd, buf, len, offset, user_data) })
}

/// # Safety
///
/// `r` must come from `mlg_ring_new`.
#[no_mangle]
pub unsafe extern "C" fn mlg_ring_submit_fsync(r: *mut ring::Ring, fd: i32, user_data: u64) -> i32 {
    if r.is_null() {
        return EFAULT;
    }
    // SAFETY: the caller guarantees the ring pointer.
    let ring = unsafe { &mut *r };
    i32::from(ring.submit_fsync(fd, user_data))
}

/// Hands everything queued to the kernel, waiting for `min_complete` entries. Returns the number submitted, or a
/// negative errno.
///
/// # Safety
///
/// `r` must come from `mlg_ring_new`.
#[no_mangle]
pub unsafe extern "C" fn mlg_ring_submit(r: *mut ring::Ring, min_complete: u32) -> i32 {
    if r.is_null() {
        return EFAULT;
    }
    // SAFETY: the caller guarantees the ring pointer.
    let ring = unsafe { &mut *r };
    let result = if min_complete == 0 {
        ring.submit()
    } else {
        ring.submit_and_wait(min_complete as usize)
    };
    match result {
        Ok(submitted) => submitted as i32,
        Err(e) => -(e.raw_os_error().unwrap_or(5)),
    }
}

/// Collects up to `max` completions into `out`, which must have room for `max` 16-byte entries. Never blocks.
///
/// # Safety
///
/// `out` must point to `max` writable `Cqe`-sized entries.
#[no_mangle]
pub unsafe extern "C" fn mlg_ring_reap(r: *mut ring::Ring, out: *mut ring::Cqe, max: u32) -> i32 {
    if r.is_null() || out.is_null() {
        return EFAULT;
    }
    // SAFETY: the caller guarantees the ring pointer and the output range.
    let ring = unsafe { &mut *r };
    let slots = unsafe { slice::from_raw_parts_mut(out, max as usize) };
    ring.reap(slots, max as usize) as i32
}

// ---------------------------------------------------------------- FFI entry point tests

/// The `extern "C"` surface, called the way Java calls it: through raw pointers.
///
/// The rest of the crate's tests exercise the safe Rust internals, which is not where undefined behaviour lives. It
/// lives in these entry points, where a caller's pointer and length are turned into a slice — and a bad bound, an
/// aliasing mistake or a read past the end is invisible to a normal test run because it usually produces the right
/// answer anyway.
///
/// This module is what `cargo +nightly miri test` interprets. Miri has no kernel, so the ring is out of its reach and
/// is ignored under it; everything here is pure computation over memory, which is precisely what miri checks.
#[cfg(test)]
mod ffi_tests {
    use super::*;

    #[test]
    fn crc32c_over_a_raw_pointer_matches_a_slice() {
        let data: Vec<u8> = (0..1024u32).map(|i| (i % 251) as u8).collect();
        let through_ffi = unsafe { mlg_crc32c(data.as_ptr(), data.len(), 0) };
        assert_eq!(through_ffi, crc32c::crc32c(&data));
        // A zero length must not dereference the pointer at all.
        assert_eq!(unsafe { mlg_crc32c(data.as_ptr(), 0, 0) }, 0);
    }

    #[test]
    fn a_null_pointer_is_refused_rather_than_dereferenced() {
        assert_eq!(unsafe { mlg_crc32c(core::ptr::null(), 16, 0) }, 0);
        assert_eq!(unsafe { mlg_xxh3(core::ptr::null(), 16, 0) }, 0);
        let mut out = [0u8; 16];
        assert!(unsafe { mlg_lz4_compress(core::ptr::null(), 16, out.as_mut_ptr(), out.len()) } < 0);
        assert!(unsafe { mlg_lz4_decompress(core::ptr::null(), 16, out.as_mut_ptr(), out.len()) } < 0);
    }

    #[test]
    fn the_edge_codec_round_trips_through_raw_pointers() {
        // Grouped in runs and ascending inside each, which is the shape a real block holds: the group key is written
        // once per run and the locations delta-encode to a byte or two. A key that changed on every entry would make
        // every entry its own group, which is a worst case rather than a round trip.
        let edges: Vec<u64> = (0..500u64)
            .map(|i| ((i / 125) % 4) << 48 | (i * 37 + 11))
            .collect();
        let mut encoded = vec![0u8; 64 + edges.len() * 12];
        let written = unsafe {
            mlg_edges_encode(
                edges.as_ptr(),
                edges.len() as u32,
                encoded.as_mut_ptr(),
                encoded.len() as u32,
            )
        };
        assert!(written > 0, "encode returned {written}");

        let mut decoded = vec![0u64; edges.len()];
        let count = unsafe {
            mlg_edges_decode(
                encoded.as_ptr(),
                written as u32,
                decoded.as_mut_ptr(),
                decoded.len() as u32,
            )
        };
        assert_eq!(count, edges.len() as i32, "decode returned {count}");

        let mut expected = edges.clone();
        expected.sort_unstable();
        assert_eq!(decoded, expected);
    }

    /// A destination too small must be refused before anything is written past its end.
    #[test]
    fn an_undersized_destination_is_refused() {
        let edges: Vec<u64> = (0..64u64).map(|i| i * 7 + 1).collect();
        let mut tiny = [0u8; 4];
        assert!(
            unsafe {
                mlg_edges_encode(
                    edges.as_ptr(),
                    edges.len() as u32,
                    tiny.as_mut_ptr(),
                    tiny.len() as u32,
                )
            } < 0
        );
    }

    #[test]
    fn lz4_round_trips_through_raw_pointers() {
        let payload: Vec<u8> = b"metaloom graph storage "
            .repeat(400)
            .to_vec();
        let mut compressed = vec![0u8; payload.len() * 2 + 64];
        let written = unsafe {
            mlg_lz4_compress(
                payload.as_ptr(),
                payload.len(),
                compressed.as_mut_ptr(),
                compressed.len(),
            )
        };
        assert!(written > 0 && (written as usize) < payload.len());

        let mut restored = vec![0u8; payload.len()];
        let back = unsafe {
            mlg_lz4_decompress(
                compressed.as_ptr(),
                written as usize,
                restored.as_mut_ptr(),
                restored.len(),
            )
        };
        assert_eq!(back, payload.len() as isize);
        assert_eq!(restored, payload);
    }

    /// The layout the Java side hard-codes. A mismatch here is silent corruption on the reap path.
    #[test]
    fn the_completion_layout_is_what_java_assumes() {
        assert_eq!(mlg_cqe_size(), 16);
        assert_eq!(mlg_cqe_offset(0), 0);
        assert_eq!(mlg_cqe_offset(1), 8);
        assert_eq!(mlg_cqe_offset(2), 12);
        assert_eq!(mlg_cqe_offset(3), -1);
    }
}
