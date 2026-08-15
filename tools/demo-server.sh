#!/usr/bin/env bash
#
# Starts the server over a small fixed demo graph — what the Playwright suite drives, and the shortest way to see the
# UI without having a store of your own.
#
#   tools/demo-server.sh [store-directory] [port]
#
# The store is seeded on first use and reused afterwards, so a second run starts against the same graph. Delete the
# directory to start over.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STORE="${1:-$ROOT/graph-server/target/demo-store}"
PORT="${2:-8080}"

# exec:java runs in the module directory, not in the caller's, so a relative store path would land somewhere the
# caller did not mean — graph-server/graph-server/target/… on the first attempt at this.
case "$STORE" in
    /*) ;;
    *) STORE="$PWD/$STORE" ;;
esac

# exec:java runs what is already compiled. Compiling here rather than failing with ClassNotFoundException means the
# script works from a clean checkout, which is what a first-time reader will have.
if [ ! -d "$ROOT/graph-server/target/test-classes" ]; then
    echo "[demo] compiling first"
    mvn -o -q -f "$ROOT/pom.xml" -pl graph-server -am -DskipTests test-compile
fi

exec mvn -o -q -f "$ROOT/pom.xml" -pl graph-server exec:java \
    -Dexec.classpathScope=test \
    -Dexec.mainClass=io.metaloom.graph.server.DemoServer \
    -Dexec.args="$STORE $PORT"
