#![no_main]

//! The lz4 decompressor against arbitrary bytes.
//!
//! A compressed blob is data the engine wrote, but a corrupt page turns it into data nobody wrote. The decompressor
//! has to refuse it rather than write outside its output buffer — and the Java implementation in `Lz4` has to refuse
//! it the same way, which is what `BlobCompressionTest#testMalformedInputIsRefused` asserts from the other side.

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let mut out = vec![0u8; 4096];
    let written = unsafe {
        metaloom_graph_native::mlg_lz4_decompress(
            data.as_ptr(),
            data.len(),
            out.as_mut_ptr(),
            out.len(),
        )
    };
    assert!(
        written < 0 || (written as usize) <= out.len(),
        "decompress reported {written} bytes into a buffer of {}",
        out.len()
    );
});
