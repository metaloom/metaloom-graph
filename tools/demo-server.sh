#!/usr/bin/env bash
#
# Starts the server over a generated demo graph — an air route network — and is what the Playwright suite drives.
#
#   tools/demo-server.sh [root-directory] [port] [size]
#
# The directory is a root holding one subdirectory per database; --demo seeds the default one.
#
# size is tiny | small | medium | large, or a number of airports; small is the default. The store is seeded on first
# use and reused afterwards, so a second run starts against the same graph. Delete the directory to start over.
#
# This runs the shipped server with its shipped --demo flag rather than anything test-only, so what the browser suite
# drives is the same thing a user gets.
#
# --allow-write is on here and off in the shipped default. This is a local demo of the feature, on a generated graph
# in a target directory, and the browser suite has to be able to exercise the editor. A real deployment gets the
# safe default; see spec/OPERATIONS.md section 9.1.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STORE="${1:-$ROOT/graph-server/target/demo-store}"
PORT="${2:-8080}"
SIZE="${3:-small}"

# exec:java runs in the module directory, not in the caller's, so a relative store path would land somewhere the
# caller did not mean — graph-server/graph-server/target/… on the first attempt at this.
case "$STORE" in
    /*) ;;
    *) STORE="$PWD/$STORE" ;;
esac

# exec:java runs what is already compiled. Compiling here rather than failing with ClassNotFoundException means the
# script works from a clean checkout, which is what a first-time reader will have.
if [ ! -d "$ROOT/graph-server/target/classes" ]; then
    echo "[demo] compiling first"
    mvn -o -q -f "$ROOT/pom.xml" -pl graph-server -am -DskipTests package
fi

# Vert.x copies classpath resources into .vertx/file-cache-* and serves the copy, so a server left running across a UI
# rebuild keeps serving the previous bundle. Off here because this script is the development and test loop, where that
# is a trap rather than a saving.
exec mvn -o -q -f "$ROOT/pom.xml" -pl graph-server exec:java \
    -Dvertx.disableFileCaching=true \
    -Dexec.args="--demo --allow-write --demo-size=$SIZE $STORE $PORT"
