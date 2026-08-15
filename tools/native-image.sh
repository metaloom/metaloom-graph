#!/usr/bin/env bash
#
# Build the server as a GraalVM native image, then prove the binary works.
#
#   tools/native-image.sh [jar] [output-dir] [name]
#
# Run by `mvn -Pgraalvm package` (see graph-server/pom.xml). Standalone, from the repo root:
#
#   mvn -Pgraalvm install -DskipTests
#   tools/native-image.sh
#
# Unlike the cargo and npm steps, a missing toolchain here is a hard failure. Those two are optional
# capabilities the build routes around; this script only runs because someone asked for -Pgraalvm, and
# skipping what was explicitly requested would be worse than stopping.
#
# Everything native-image needs to know — the FFM downcall registrations, the shared-arena flag, the
# reflection and resource metadata — travels inside the jar under META-INF/native-image, which is why
# there are no build flags here beyond the output path. The jar has to be one built by `-Pgraalvm`
# though: that profile also compiles the netty substitution, and without it the build stops in netty's
# CleanerJava24Linker. See spec/GRAALVM.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${1:-$ROOT/graph-server/target/graph-server-0.0.1-SNAPSHOT.jar}"
OUT_DIR="${2:-$ROOT/graph-server/target/native}"
NAME="${3:-graph-server}"

# GRAALVM_HOME first, then the JDK running the build (which is a GraalVM often enough to be worth
# trying), then the PATH.
NATIVE_IMAGE=""
for candidate in "${GRAALVM_HOME:-}/bin/native-image" "${JAVA_HOME:-}/bin/native-image"; do
	if [ -x "$candidate" ]; then
		NATIVE_IMAGE="$candidate"
		break
	fi
done
if [ -z "$NATIVE_IMAGE" ]; then
	NATIVE_IMAGE="$(command -v native-image || true)"
fi
if [ -z "$NATIVE_IMAGE" ]; then
	echo "[native] native-image not found." >&2
	echo "[native] Set GRAALVM_HOME to a GraalVM 25 installation, or put native-image on the PATH." >&2
	echo "[native] The JVM build is unaffected: graph-server/target/*.jar still runs on any JDK 25." >&2
	exit 1
fi

if [ ! -f "$JAR" ]; then
	echo "[native] $JAR does not exist. Run 'mvn package' first." >&2
	exit 1
fi

echo "[native] $("$NATIVE_IMAGE" --version | head -1)"
mkdir -p "$OUT_DIR"
"$NATIVE_IMAGE" -jar "$JAR" --no-fallback -o "$OUT_DIR/$NAME"

BINARY="$OUT_DIR/$NAME"
echo "[native] $BINARY, $(du -h "$BINARY" | cut -f1)"

if [ "${NATIVE_SMOKE:-true}" != "true" ]; then
	exit 0
fi

# The smoke test is not a formality. Three of the four defects this build hit were invisible until the
# binary ran: a missing FFM registration is a run-time error, an unregistered resource is a 404, and
# netty's buffer allocator failing on the event loop is an empty reply with nothing in the log. `--help`
# would have passed all three.
PORT="${NATIVE_SMOKE_PORT:-8129}"
# The binary port is a different netty path from the HTTP one, so it gets its own check rather than
# being assumed to work because the HTTP server did. Its own port, so a machine already running a
# server on the default 8081 does not make this fail for the wrong reason.
WIRE_PORT="${NATIVE_SMOKE_WIRE_PORT:-8130}"
STORE="$OUT_DIR/smoke-store"
rm -rf "$STORE"

echo "[native] smoke test on ports $PORT and $WIRE_PORT"
"$BINARY" --demo "$STORE" --wire-port="$WIRE_PORT" "$PORT" > "$OUT_DIR/smoke.log" 2>&1 &
PID=$!
trap 'kill "$PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 40); do
	if curl -sf "http://localhost:$PORT/api/stats" > /dev/null 2>&1; then
		break
	fi
	sleep 0.25
done

fail() {
	echo "[native] smoke test failed: $1" >&2
	sed -n '1,40p' "$OUT_DIR/smoke.log" >&2
	exit 1
}

# The store, over FFM: mapped segment files, a scan, and the demo seed that wrote them.
STATS=$(curl -sf "http://localhost:$PORT/api/stats") || fail "/api/stats"
NODES=$(echo "$STATS" | grep -o '"nodes":[0-9]*' | cut -d: -f2)
[ "${NODES:-0}" -gt 0 ] || fail "the demo store came back empty"
# Which of the two paths this binary took. Not asserted: a build without cargo is a legitimate outcome
# and says "not built" here. Printed, because it is the only way to see it from outside the process.
CRATE=$(echo "$STATS" | grep -o '"nativeLayer":"[^"]*"' | cut -d'"' -f4)

# The query engine, and a result that has to serialise a 64 bit id.
ROUTES=$(curl -sf -X POST "http://localhost:$PORT/api/query" -H 'Content-Type: application/json' \
	-d '{"query":"MATCH (a:Airport)-[r:ROUTE_TO]->(b) RETURN a, r, b LIMIT 5"}' \
	| grep -o '"rowCount":[0-9]*' | cut -d: -f2) || fail "/api/query"
[ "${ROUTES:-0}" -eq 5 ] || fail "expected 5 rows from the demo graph, got ${ROUTES:-none}"

# The binary streaming port: handshake, a query, and rows back. WireSmoke.java is a single-file
# source-launcher program with no classpath, so this needs a JDK on PATH and nothing else. A machine
# without one skips it with a message rather than failing — the binary is still built and the HTTP
# assertions above still ran.
WIRE_SMOKE="$(dirname "${BASH_SOURCE[0]}")/WireSmoke.java"
if command -v java > /dev/null 2>&1; then
	java "$WIRE_SMOKE" 127.0.0.1 "$WIRE_PORT" || fail "the binary streaming port"
else
	echo "[native] no java on PATH, skipping the binary port check" >&2
fi

# The bundled UI, which is a classpath resource and therefore a metadata question.
INDEX=$(curl -sf -o /dev/null -w '%{http_code}' "http://localhost:$PORT/") || fail "the index page"
[ "$INDEX" = "200" ] || fail "the index page returned $INDEX"
ASSET=$(curl -sf "http://localhost:$PORT/" | grep -o '/assets/[^"]*\.js' | head -1)
if [ -n "$ASSET" ]; then
	CODE=$(curl -sf -o /dev/null -w '%{http_code}' "http://localhost:$PORT$ASSET") || fail "$ASSET"
	[ "$CODE" = "200" ] || fail "$ASSET returned $CODE"
fi

echo "[native] smoke test passed: $NODES nodes, 5 rows over each port, index and $ASSET served; Rust crate: ${CRATE:-unknown}"
