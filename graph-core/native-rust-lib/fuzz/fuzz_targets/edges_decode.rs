#![no_main]

//! The adjacency decoder against arbitrary bytes.
//!
//! This is the one entry point in the crate that is fed data the engine did not write: a block read off disk may be
//! corrupt, truncated, or hostile, and the decoder's contract is that it returns an error rather than reading past
//! its input or panicking. That contract is exactly what a fuzzer is for — the Java side has `HostileInputTest` for
//! the same reason, and the two together cover both implementations of the same format.
//!
//! ```sh
//! cargo +nightly fuzz run edges_decode -- -max_total_time=60
//! ```

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    // A destination deliberately smaller than the input could ask for, so the "does not fit" path is reachable and
    // an unchecked write past the end would be caught rather than absorbed by a generous buffer.
    let mut out = vec![0u64; 256];

    let written = unsafe {
        metaloom_graph_native::mlg_edges_decode(
            data.as_ptr(),
            data.len() as u32,
            out.as_mut_ptr(),
            out.len() as u32,
        )
    };

    // Whatever it decided, it must have decided it: a negative errno or a count that fits the destination. Anything
    // else means the length checks let something through.
    assert!(
        written < 0 || (written as usize) <= out.len(),
        "decode reported {written} entries into a buffer of {}",
        out.len()
    );
});
