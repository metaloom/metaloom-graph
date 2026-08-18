#!/usr/bin/env bash
#
# Build a GraalVM native image, then prove the binary works.
#
#   tools/native-image.sh [server|cli]
#   tools/native-image.sh <jar> <output-dir> <name>
#
# Two binaries come out of this repository, and each gets a smoke test that fits it:
#
#   graph-server         the server, from graph-server/target/graph-server-*.jar
#   metaloom-graph-cli   the backup tool, from graph-cli/target/graph-cli-*-cli.jar
#
# Run by `mvn -Pgraalvm package` in both modules (see their poms), which passes the three explicit
# arguments. From the repo root, by hand:
#
#   mvn -Pgraalvm install -DskipTests
#   tools/native-image.sh server
#   tools/native-image.sh cli
#
# Unlike the cargo and npm steps, a missing toolchain here is a hard failure. Those two are optional
# capabilities the build routes around; this script only runs because someone asked for -Pgraalvm, and
# skipping what was explicitly requested would be worse than stopping.
#
# Everything native-image needs to know — the FFM downcall registrations, the shared-arena flag, the
# reflection and resource metadata — travels inside the jar under META-INF/native-image, which is why
# there are no build flags here beyond the output path. That is also why the CLI needs no metadata of
# its own: the registrations live in graph-core, so any image containing the engine inherits them.
#
# The SERVER jar has to be one built by `-Pgraalvm` though: that profile also compiles the netty
# substitution, and without it the build stops in netty's CleanerJava24Linker. The CLI has no netty and
# no such requirement. See spec/GRAALVM.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

case "${1:-server}" in
	server)
		JAR="$ROOT/graph-server/target/graph-server-0.0.1-SNAPSHOT.jar"
		OUT_DIR="$ROOT/graph-server/target/native"
		NAME="graph-server"
		;;
	cli)
		JAR="$ROOT/graph-cli/target/graph-cli-0.0.1-SNAPSHOT-cli.jar"
		OUT_DIR="$ROOT/graph-cli/target/native"
		NAME="metaloom-graph-cli"
		;;
	*)
		JAR="$1"
		OUT_DIR="${2:-$ROOT/graph-server/target/native}"
		NAME="${3:-graph-server}"
		;;
esac

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
	echo "[native] The JVM build is unaffected: the shaded jars still run on any JDK 25." >&2
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

LOG="$OUT_DIR/smoke.log"

fail() {
	echo "[native] smoke test failed: $1" >&2
	if [ -f "$LOG" ]; then
		sed -n '1,40p' "$LOG" >&2
	fi
	exit 1
}

# ---------------------------------------------------------------------------------------- the server

# The smoke test is not a formality. Three of the four defects this build hit were invisible until the
# binary ran: a missing FFM registration is a run-time error, an unregistered resource is a 404, and
# netty's buffer allocator failing on the event loop is an empty reply with nothing in the log. `--help`
# would have passed all three.
smoke_server() {
	PORT="${NATIVE_SMOKE_PORT:-8129}"
	# The binary port is a different netty path from the HTTP one, so it gets its own check rather than
	# being assumed to work because the HTTP server did. Its own port, so a machine already running a
	# server on the default 8081 does not make this fail for the wrong reason.
	WIRE_PORT="${NATIVE_SMOKE_WIRE_PORT:-8130}"
	# A third listener, for the TLS check below.
	WIRE_TLS_PORT="${NATIVE_SMOKE_WIRE_TLS_PORT:-8131}"
	STORE="$OUT_DIR/smoke-store"
	rm -rf "$STORE"

	# A throwaway certificate for the encrypted binary port. Generated rather than committed: a certificate expires,
	# and a committed one turns into a smoke test that passes for years and then fails on a date nobody chose. Skipped
	# without openssl, with a message, because the rest of this function is still worth running.
	TLS_DIR="$OUT_DIR/smoke-tls"
	TLS_ARGS=""
	if command -v openssl > /dev/null 2>&1; then
		rm -rf "$TLS_DIR"
		mkdir -p "$TLS_DIR"
		openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=localhost' \
			-keyout "$TLS_DIR/tls.key" -out "$TLS_DIR/tls.crt" > /dev/null 2>&1 \
			|| fail "openssl could not generate a smoke certificate"
		TLS_ARGS="--wire-tls-cert=$TLS_DIR/tls.crt --wire-tls-key=$TLS_DIR/tls.key --wire-tls-port=$WIRE_TLS_PORT"
	else
		echo "[native] no openssl on PATH, skipping the TLS check" >&2
	fi

	echo "[native] smoke test on ports $PORT, $WIRE_PORT and ${TLS_ARGS:+$WIRE_TLS_PORT}"
	# --no-auth deliberately. What this function tests is whether the image can serve — a route, a mapped segment,
	# a classpath resource, a netty listener — and every assertion below would otherwise be a 401 that proved only
	# that authentication works. The path authentication puts at risk in a native image is the JCA lookup, and
	# smoke_cli exercises that directly through `auth reset-admin`.
	# shellcheck disable=SC2086
	"$BINARY" --demo --no-auth $TLS_ARGS "$STORE" --wire-port="$WIRE_PORT" "$PORT" > "$LOG" 2>&1 &
	PID=$!
	trap 'kill "$PID" 2>/dev/null || true' EXIT

	for _ in $(seq 1 40); do
		if curl -sf "http://localhost:$PORT/api/stats" > /dev/null 2>&1; then
			break
		fi
		sleep 0.25
	done

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
	WIRE_SMOKE="$ROOT/tools/WireSmoke.java"
	if command -v java > /dev/null 2>&1; then
		java "$WIRE_SMOKE" 127.0.0.1 "$WIRE_PORT" || fail "the binary streaming port"
		# The encrypted listener, which is a JSSE question and therefore a metadata one: a provider missing from the
		# image fails at RUN time under --no-fallback and at no earlier point, exactly like the PBKDF2 lookup in
		# smoke_cli. The same handshake and the same query, so a pass here means TLS and nothing else changed.
		if [ -n "$TLS_ARGS" ]; then
			java "$WIRE_SMOKE" localhost "$WIRE_TLS_PORT" --tls || fail "the encrypted binary port"
		fi
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

	echo "[native] smoke test passed: $NODES nodes, 5 rows over each port${TLS_ARGS:+ including TLS}, index and"
	echo "[native] $ASSET served; Rust crate: ${CRATE:-unknown}"
}

# ------------------------------------------------------------------------------------------- the CLI

# What has to be true of this binary is narrower than for the server and no less easy to get wrong: it
# opens a store through FFM, maps its segment files into an Arena.ofShared, walks it under a snapshot,
# and puts one back. `--help` exercises none of that, and -H:+SharedArenaSupport is a flag whose absence
# shows up only when a mapped file is CLOSED — so a check that opened a store and stopped would pass
# while the binary was still broken.
#
# The CLI cannot create data, so tools/GraphSeed.java writes it a store first, on the JVM, over the same
# shaded jar that has just been compiled into this binary. That needs a JDK, which the Maven build
# running this script definitionally has; a machine without one gets the reduced check and a message,
# the same trade WireSmoke.java makes above.
smoke_cli() {
	SEED_ROOT="$OUT_DIR/smoke-root"
	DUMP="$OUT_DIR/smoke.dump"
	rm -rf "$SEED_ROOT" "$DUMP"
	: > "$LOG"

	"$BINARY" --help > /dev/null 2>&1 || fail "--help"

	# The online half is 1.2 MB of java.net.http inside the image, and a server to point it at is more
	# than this script should need. Pointing it at a port nothing listens on still constructs the client,
	# resolves a name and opens a socket — which is where a missing registration would show — and must
	# come back as a refusal rather than as a crash.
	set +e
	OFFLINE_OUTPUT=$("$BINARY" database list --server="http://localhost:${NATIVE_SMOKE_DEAD_PORT:-8199}" 2>&1)
	OFFLINE_STATUS=$?
	set -e
	[ "$OFFLINE_STATUS" -eq 1 ] || fail "a server that is not there should exit 1, got $OFFLINE_STATUS: $OFFLINE_OUTPUT"
	case "$OFFLINE_OUTPUT" in
		*NoClassDefFound* | *UnsatisfiedLink* | *MissingReflectionRegistration* | *ExceptionInInitializer*)
			fail "the HTTP client is not fully compiled into the image: $OFFLINE_OUTPUT"
			;;
	esac

	if ! command -v java > /dev/null 2>&1; then
		echo "[native] no java on PATH, so no store to back up; --help and the HTTP path checked only" >&2
		return 0
	fi

	java --enable-native-access=ALL-UNNAMED -cp "$JAR" "$ROOT/tools/GraphSeed.java" "$SEED_ROOT" >> "$LOG" 2>&1 \
		|| fail "seeding a store"

	NAMES=$("$BINARY" database list --path="$SEED_ROOT" 2>>"$LOG") || fail "database list"
	echo "$NAMES" | grep -qx "default" || fail "database list did not name the seeded database: $NAMES"

	# The read half: a paced scan under one snapshot, over mapped segment files.
	BACKUP_OUTPUT=$("$BINARY" database backup default --path="$SEED_ROOT" --out="$DUMP" --quiet 2>&1) \
		|| fail "database backup: $BACKUP_OUTPUT"
	echo "$BACKUP_OUTPUT" | grep -q "3 nodes, 3 relationships, 1 lightweight edges" \
		|| fail "the dump does not hold what GraphSeed wrote: $BACKUP_OUTPUT"

	# Reads a header and opens nothing, which is the one path that has to work when a store will not.
	"$BINARY" database inspect --in="$DUMP" 2>>"$LOG" | grep -q "^database *default" || fail "database inspect"

	# The write half, ending in verify(FULL) — the engine's own oracle, run inside the binary.
	RESTORE_OUTPUT=$("$BINARY" database restore copy --path="$SEED_ROOT" --in="$DUMP" 2>&1) \
		|| fail "database restore: $RESTORE_OUTPUT"
	echo "$RESTORE_OUTPUT" | grep -q "3 nodes, 3 relationships, 1 lightweight edges, 1 indexes" \
		|| fail "the restore did not put back what the dump held: $RESTORE_OUTPUT"

	# And round again, off the restored copy: two dumps of one graph must agree on every count, which is
	# the cheapest statement of "the binary did not lose anything in either direction".
	ROUND_OUTPUT=$("$BINARY" database backup copy --path="$SEED_ROOT" --out="$DUMP.2" --quiet 2>&1) \
		|| fail "backing the restored copy up again: $ROUND_OUTPUT"
	FIRST=$(echo "$BACKUP_OUTPUT" | grep -o "[0-9]* nodes, [0-9]* relationships, [0-9]* lightweight edges")
	SECOND=$(echo "$ROUND_OUTPUT" | grep -o "[0-9]* nodes, [0-9]* relationships, [0-9]* lightweight edges")
	[ "$FIRST" = "$SECOND" ] || fail "the round trip changed the graph: '$FIRST' became '$SECOND'"

	# A damaged dump has to be diagnosed, not crash: this is the one path a user reaches on their worst
	# day. DumpHostileInputTest covers the format properly; this asserts the binary reports rather than
	# aborts.
	# 32 bytes, which is INSIDE the header — a 64-byte prefix still holds the whole of it, and `inspect`
	# would then succeed, correctly, on a file that is nine tenths missing.
	head -c 32 "$DUMP" > "$DUMP.truncated"
	set +e
	TRUNCATED=$("$BINARY" database inspect --in="$DUMP.truncated" 2>&1)
	TRUNCATED_STATUS=$?
	set -e
	[ "$TRUNCATED_STATUS" -eq 1 ] || fail "a truncated dump should exit 1, got $TRUNCATED_STATUS"
	echo "$TRUNCATED" | grep -q "dump could not be read" || fail "a truncated dump was not diagnosed: $TRUNCATED"

	# The JCA path, which is the one thing in this binary that a green `mvn verify` proves nothing about.
	# SecretKeyFactory.getInstance is a reflective service lookup, so a missing entry in graph-core's
	# reachability-metadata.json fails at RUN time under --no-fallback and at no earlier point. This command
	# generates a password with SecureRandom, derives it with PBKDF2-HMAC-SHA512 and writes the file, which
	# is every part of that path in one go. See spec/AUTH.md section 4.2.
	AUTH_ROOT="$OUT_DIR/smoke-auth-root"
	mkdir -p "$AUTH_ROOT"
	set +e
	AUTH_OUTPUT=$("$BINARY" auth reset-admin --path="$AUTH_ROOT" 2>&1)
	AUTH_STATUS=$?
	set -e
	[ "$AUTH_STATUS" -eq 0 ] || fail "auth reset-admin: $AUTH_OUTPUT"
	case "$AUTH_OUTPUT" in
		*NoSuchAlgorithmException*|*"is not available from this JVM"*)
			fail "the JCA provider is missing from graph-core's reachability-metadata.json: $AUTH_OUTPUT" ;;
	esac
	# The password is the only thing on stdout, so a 24-character line is the proof it was both generated and
	# printed where a script can capture it.
	PASSWORD=$("$BINARY" auth reset-admin --path="$AUTH_ROOT" 2>/dev/null | tr -d '\r\n')
	[ "${#PASSWORD}" -eq 24 ] || fail "auth reset-admin printed a ${#PASSWORD} character password, expected 24"
	"$BINARY" auth list --path="$AUTH_ROOT" 2>>"$LOG" | grep -q "admin" || fail "auth list"

	echo "[native] smoke test passed: $FIRST backed up, restored and backed up again; a truncated dump refused;"
	echo "[native] PBKDF2 and SecureRandom work in the image"
}

case "$NAME" in
	graph-server) smoke_server ;;
	metaloom-graph-cli) smoke_cli ;;
	# A name this script does not know is a build it cannot check. Saying so beats silently passing.
	*)
		echo "[native] no smoke test defined for '$NAME'; the binary was built but not exercised" >&2
		;;
esac
