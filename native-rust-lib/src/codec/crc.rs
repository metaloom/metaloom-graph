//! CRC32C and xxh3.
//!
//! `crc32c` must produce output byte-for-byte identical to `io.metaloom.graph.core.util.Crc` and to
//! `java.util.zip.CRC32C`, which remain the reference. `NativeCodecTest` enforces that.
//!
//! **This used to be a bitwise loop and lost to the JDK by two orders of magnitude**, which is what the first N4
//! measurement recorded. The JDK's `CRC32C` compiles to the hardware CRC32 instruction; a byte-at-a-time loop in any
//! language cannot come close. It now uses the `crc32c` crate, which uses the same instruction — the honest
//! comparison, and the one spec/NATIVE_FFM.md section 2.1 asks for by saying hashes should come from crates.

/// Hardware-accelerated where the CPU has SSE4.2, with a software fallback inside the crate.
pub fn crc32c(data: &[u8], seed: u32) -> u32 {
    crc32c::crc32c_append(seed, data)
}

/// XXH3-64. Not persisted anywhere: it is a hash for in-memory structures, so the Java side treats it as opaque.
pub fn xxh3(data: &[u8], seed: u64) -> u64 {
    xxhash_rust::xxh3::xxh3_64_with_seed(data, seed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crc_of_empty_matches_the_reference() {
        assert_eq!(crc32c(&[], 0), 0);
    }

    #[test]
    fn xxh3_is_stable_and_spreads() {
        assert_eq!(xxh3(b"metaloom", 0), xxh3(b"metaloom", 0));
        assert_ne!(xxh3(b"metaloom", 0), xxh3(b"metaloom", 1));
        assert_ne!(xxh3(b"metaloom", 0), xxh3(b"metaloon", 0));
    }
}
