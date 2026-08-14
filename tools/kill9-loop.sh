#!/usr/bin/env bash
#
# The kill -9 loop: start a writing workload, kill it without warning, reopen, and check that every commit the store
# acknowledged is still there. Repeat.
#
# This is the strongest durability evidence that can be produced without special hardware. It is level 3 of
# spec/TESTING.md section 6; level 4, cutting power to a machine with an honestly configured write cache, is a manual
# procedure documented in spec/OPERATIONS.md and cannot be automated here.
#
# Usage:
#   tools/kill9-loop.sh [iterations] [seconds-per-iteration]
#
# Exits non-zero on the first failure and leaves the store behind for diagnosis, because a store that failed a
# durability check is the most valuable artefact the project can produce.
set -u -o pipefail

ITERATIONS="${1:-20}"
SECONDS_PER="${2:-3}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/graphstore-kill9-$$"
CLASSPATH="$ROOT/target/classes:$ROOT/target/test-classes:$(cat "$ROOT/target/test-classpath.txt" 2>/dev/null || true)"

if [ ! -d "$ROOT/target/test-classes" ]; then
  echo "build first: mvn -o test-compile" >&2
  exit 2
fi

# The dependency classpath is written out by `mvn dependency:build-classpath`; regenerate it when it is missing.
if [ ! -f "$ROOT/target/test-classpath.txt" ]; then
  echo "generating the test classpath..."
  (cd "$ROOT" && mvn -o -q dependency:build-classpath \
      -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test) || exit 2
  CLASSPATH="$ROOT/target/classes:$ROOT/target/test-classes:$(cat "$ROOT/target/test-classpath.txt")"
fi

mkdir -p "$WORK"
echo "kill -9 loop: $ITERATIONS iterations of ${SECONDS_PER}s in $WORK"

for i in $(seq 1 "$ITERATIONS"); do
  java -cp "$CLASSPATH" io.metaloom.graph.core.crash.CrashWorkload write "$WORK" "$SECONDS_PER" >/dev/null 2>&1 &
  PID=$!

  # Let it get properly under way, then kill it at a point it cannot anticipate.
  sleep "$(awk -v s="$SECONDS_PER" 'BEGIN { srand(); print s * (0.3 + 0.6 * rand()) }')"
  kill -9 "$PID" 2>/dev/null
  wait "$PID" 2>/dev/null

  if ! java -cp "$CLASSPATH" io.metaloom.graph.core.crash.CrashWorkload check "$WORK"; then
    echo "FAILED on iteration $i. The store is at $WORK" >&2
    exit 1
  fi
  echo "  iteration $i/$ITERATIONS ok"
done

echo "kill -9 loop: $ITERATIONS iterations clean"
rm -rf "$WORK"
