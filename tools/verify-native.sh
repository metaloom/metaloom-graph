#!/usr/bin/env bash
#
# The two checks on the Rust crate that cannot run inside `mvn test`.
#
#   miri  interprets the crate and reports undefined behaviour in the unsafe entry points. It needs a nightly
#         toolchain, runs ~50x slower than native, and cannot execute syscalls — so the io_uring tests are ignored
#         under it and the raw-pointer entry points in `ffi_tests` are what it actually checks.
#   fuzz  drives the two decoders that are fed data the engine did not write. Both found real defects the day the
#         targets were written; see spec/GRAPH_BENCHMARK.md and the comments in codec/edges.rs.
#
# Neither belongs in the ordinary build: one is slow, the other is unbounded. This script is what CI and a developer
# run deliberately.
#
#   tools/verify-native.sh            # miri, then 60 seconds per fuzz target
#   tools/verify-native.sh 600        # 600 seconds per fuzz target
#
set -euo pipefail

cd "$(dirname "$0")/.."
FUZZ_SECONDS="${1:-60}"

if ! command -v cargo >/dev/null 2>&1; then
    echo "[native] cargo is not installed; nothing to verify"
    exit 0
fi

if cargo +nightly miri --version >/dev/null 2>&1; then
    echo "=== miri ==="
    cargo +nightly miri test --manifest-path native-rust-lib/Cargo.toml
else
    echo "[native] miri is unavailable. Install with:"
    echo "         rustup toolchain install nightly --profile minimal --component miri rust-src"
fi

if cargo fuzz --version >/dev/null 2>&1; then
    for target in $(cargo fuzz list --fuzz-dir native-rust-lib/fuzz 2>/dev/null); do
        echo "=== fuzz: ${target}, ${FUZZ_SECONDS}s ==="
        cargo +nightly fuzz run "${target}" --fuzz-dir native-rust-lib/fuzz -- \
            "-max_total_time=${FUZZ_SECONDS}"
    done
else
    echo "[native] cargo-fuzz is unavailable. Install with:"
    echo "         cargo install cargo-fuzz --locked"
fi

echo "[native] done"
