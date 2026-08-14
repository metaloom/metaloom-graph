//! The C ABI surface of the metaloom graph storage native layer.
//!
//! This module contains no logic: it converts raw pointers to slices, calls into the safe modules and maps results to
//! an `i32`. Every fallible function returns 0 on success or a negated errno. No function may panic across the
//! boundary, so each entry point is wrapped in `catch_unwind`.
//!
//! Status: not yet wired into the Java build. See spec/NATIVE_FFM.md.

mod codec;

use std::panic::catch_unwind;
use std::slice;

/// Bumped when the ABI changes. The Java side checks it at load and falls back on a mismatch.
pub const MLG_ABI_VERSION: u32 = 1;

pub const CAP_CRC32C: u64 = 1 << 0;
pub const CAP_EDGE_CODEC: u64 = 1 << 1;

const EFAULT: i32 = -14;
const EINVAL: i32 = -22;

#[no_mangle]
pub extern "C" fn mlg_version() -> u32 {
    MLG_ABI_VERSION
}

#[no_mangle]
pub extern "C" fn mlg_capabilities() -> u64 {
    CAP_CRC32C | CAP_EDGE_CODEC
}

/// CRC32C over a byte range. Matches `java.util.zip.CRC32C` exactly; the Java implementation stays the reference.
#[no_mangle]
pub extern "C" fn mlg_crc32c(data: *const u8, len: usize, seed: u32) -> u32 {
    if data.is_null() && len != 0 {
        return 0;
    }
    let result = catch_unwind(|| {
        let bytes = if len == 0 {
            &[][..]
        } else {
            // SAFETY: the caller guarantees `data` points to at least `len` readable bytes for the duration of the
            // call, and never mutates them concurrently.
            unsafe { slice::from_raw_parts(data, len) }
        };
        codec::crc32c(bytes, seed)
    });
    result.unwrap_or(0)
}

/// Encodes packed adjacency entries. Returns the bytes written, or the negated required capacity when `dst` is too
/// small. Byte-for-byte identical to the Java `EdgeCodec`.
#[no_mangle]
pub extern "C" fn mlg_edges_encode(src: *const u64, count: u32, dst: *mut u8, dst_cap: u32) -> i32 {
    if src.is_null() || dst.is_null() {
        return EFAULT;
    }
    catch_unwind(|| {
        // SAFETY: the caller guarantees both ranges are valid and do not overlap.
        let entries = unsafe { slice::from_raw_parts(src, count as usize) };
        let out = unsafe { slice::from_raw_parts_mut(dst, dst_cap as usize) };
        match codec::encode_edges(entries, out) {
            Ok(written) => written as i32,
            Err(needed) => -(needed as i32),
        }
    })
    .unwrap_or(EFAULT)
}

/// Decodes packed adjacency entries. Returns the number of entries written, or a negative errno. Never reads past
/// `src_len`: a corrupt block must produce an error, not an out-of-bounds read.
#[no_mangle]
pub extern "C" fn mlg_edges_decode(src: *const u8, src_len: u32, dst: *mut u64, dst_cap: u32) -> i32 {
    if src.is_null() || dst.is_null() {
        return EFAULT;
    }
    catch_unwind(|| {
        // SAFETY: as above.
        let bytes = unsafe { slice::from_raw_parts(src, src_len as usize) };
        let out = unsafe { slice::from_raw_parts_mut(dst, dst_cap as usize) };
        match codec::decode_edges(bytes, out) {
            Ok(written) => written as i32,
            Err(()) => EINVAL,
        }
    })
    .unwrap_or(EFAULT)
}
