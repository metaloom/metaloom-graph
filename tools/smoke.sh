#!/usr/bin/env bash
#
# Smoke-tests a built container image: the thing a user runs, not the reactor that built it.
#
#   tools/smoke.sh [image]        (default: metaloom/graph-server:<version from the root pom.xml>)
#
#   ENGINE        docker or podman (default: whichever is on PATH, docker first)
#   SMOKE_KEEP=1  leave the containers, volumes and the dump behind for a look afterwards
#
# `mvn test` proves the code. It cannot prove the image: that the jar inside it is the one just built,
# that the native layer made it into the jar and loads in the image's libc, that the UI bundle is there,
# that the shipped defaults (authentication on, writes off, the binary port on loopback) are what a user
# gets, or that the backup tool shipped beside the server can take a dump the server can be rebuilt from.
# Each of those has broken before without a single test going red. So this runs the image four ways:
#
#   1. Default configuration plus writes and backups: health and readiness, the version the server
#      reports, the native layer, the UI, login, a write, a read, an online backup through the CLI.
#   2. The shipped default with nothing enabled: a write must be refused.
#   3. A fresh volume, restored offline from that dump by the CLI in the same image, then served:
#      the restored graph must answer the same count.
#   4. The demo graph with authentication off and the binary port published: tools/WireSmoke.java,
#      a dependency-free client, runs a query over the binary protocol.
#
# Everything it creates is named smoke-<pid>-* and removed on exit, pass or fail, unless SMOKE_KEEP=1.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(sed -n '/<artifactId>metaloom-graph<\/artifactId>/,/<\/version>/s|.*<version>\(.*\)</version>.*|\1|p' \
	"$ROOT/pom.xml" | head -n 1)"
IMAGE="${1:-metaloom/graph-server:$VERSION}"
PASSWORD="smoke-$$-$RANDOM-pw"
PREFIX="smoke-$$"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/metaloom-graph-smoke.XXXXXX")"

ENGINE="${ENGINE:-}"
if [ -z "$ENGINE" ]; then
	if command -v docker >/dev/null 2>&1; then
		ENGINE=docker
	elif command -v podman >/dev/null 2>&1; then
		ENGINE=podman
	else
		echo "Neither docker nor podman is on PATH. Set ENGINE= to name one." >&2
		exit 1
	fi
fi
for tool in curl jq java; do
	command -v "$tool" >/dev/null 2>&1 || { echo "smoke.sh needs $tool on PATH" >&2; exit 1; }
done

STEP=0
step() {
	STEP=$((STEP + 1))
	echo "--> [$STEP] $*"
}
fail() {
	echo "SMOKE FAILED: $*" >&2
	for c in "$PREFIX-main" "$PREFIX-default" "$PREFIX-restored" "$PREFIX-wire"; do
		if "$ENGINE" container inspect "$c" >/dev/null 2>&1; then
			echo "----- last log lines of $c -----" >&2
			"$ENGINE" logs --tail 40 "$c" >&2 2>&1 || true
		fi
	done
	exit 1
}

cleanup() {
	if [ "${SMOKE_KEEP:-0}" = "1" ]; then
		echo "SMOKE_KEEP=1: leaving containers $PREFIX-*, volumes $PREFIX-* and $WORK"
		return
	fi
	for c in "$PREFIX-main" "$PREFIX-default" "$PREFIX-restored" "$PREFIX-wire"; do
		"$ENGINE" rm -f "$c" >/dev/null 2>&1 || true
	done
	for v in "$PREFIX-data" "$PREFIX-restore"; do
		"$ENGINE" volume rm -f "$v" >/dev/null 2>&1 || true
	done
	rm -rf "$WORK"
}
trap cleanup EXIT

# The host port the engine picked for a container port published as 127.0.0.1::<port>.
host_port() {
	"$ENGINE" port "$1" "$2/tcp" | head -n 1 | sed 's/.*://'
}

# Polls the metrics port's /ready until it answers 200, or fails with the container's log.
await_ready() {
	local container="$1" port="$2"
	for _ in $(seq 1 120); do
		if curl -fs "http://127.0.0.1:$port/ready" >/dev/null 2>&1; then
			return 0
		fi
		if [ "$("$ENGINE" container inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" != "true" ]; then
			fail "$container exited before it was ready"
		fi
		sleep 0.5
	done
	fail "$container was not ready after 60 s"
}

# POST /api/query with an optional bearer token; prints the body, and the status code on the last line.
query() {
	local port="$1" token="$2" cypher="$3"
	local auth=()
	if [ -n "$token" ]; then
		auth=(-H "Authorization: Bearer $token")
	fi
	curl -s -w '\n%{http_code}' -X POST "http://127.0.0.1:$port/api/query" \
		-H 'content-type: application/json' "${auth[@]}" \
		-d "$(jq -cn --arg q "$cypher" '{query: $q}')"
}

# The single value of the first row's first column of a successful query.
scalar() {
	local out status
	out="$(query "$@")"
	status="$(tail -n 1 <<< "$out")"
	[ "$status" = "200" ] || fail "query '$3' answered $status: $(head -n -1 <<< "$out")"
	head -n -1 <<< "$out" | jq -r '.rows[0] | if type == "array" then .[0] elif type == "object" then (to_entries[0].value) else . end'
}

login() {
	local port="$1"
	curl -fs -X POST "http://127.0.0.1:$port/api/login" -H 'content-type: application/json' \
		-d "$(jq -cn --arg p "$PASSWORD" '{user: "admin", password: $p}')" | jq -r '.token'
}

"$ENGINE" image inspect "$IMAGE" >/dev/null 2>&1 \
	|| fail "no image $IMAGE. Build it first: container/build-containers.sh jvm   (or ./build.sh)"
echo "Smoke-testing $IMAGE with $ENGINE"

# ------------------------------------------------------------------ 1. defaults plus writes and backups

step "start with authentication on (the default), writes and backups enabled"
"$ENGINE" volume create "$PREFIX-data" >/dev/null
"$ENGINE" run -d --name "$PREFIX-main" -v "$PREFIX-data:/data" \
	-e GRAPH_INITIAL_PASSWORD="$PASSWORD" -e GRAPH_ALLOW_WRITE=true -e GRAPH_ALLOW_BACKUP=true \
	-p 127.0.0.1::8080 -p 127.0.0.1::8082 "$IMAGE" >/dev/null
HTTP="$(host_port "$PREFIX-main" 8080)"
METRICS="$(host_port "$PREFIX-main" 8082)"
await_ready "$PREFIX-main" "$METRICS"
curl -fs "http://127.0.0.1:$METRICS/health" >/dev/null || fail "/health did not answer 200"

step "the image reports the version it was built as"
LABEL="$("$ENGINE" image inspect -f '{{index .Config.Labels "org.opencontainers.image.version"}}' "$IMAGE")"
[ "$LABEL" = "$VERSION" ] || fail "image label org.opencontainers.image.version is '$LABEL', the pom says $VERSION"
JAR_VERSION="$("$ENGINE" exec "$PREFIX-main" sh -c 'unzip -p /graph/graph-server.jar META-INF/MANIFEST.MF 2>/dev/null || true' \
	| tr -d '\r' | sed -n 's/^Implementation-Version: //p')"
if [ -n "$JAR_VERSION" ]; then
	[ "$JAR_VERSION" = "$VERSION" ] || fail "the server jar in the image is version $JAR_VERSION, not $VERSION"
else
	# No unzip in the runtime image is fine; the label above was written from the same pom.
	echo "    (no unzip in the image; relying on the label)"
fi

step "an anonymous query is refused"
STATUS="$(query "$HTTP" "" "RETURN 1" | tail -n 1)"
[ "$STATUS" = "401" ] || fail "an unauthenticated query answered $STATUS, expected 401"

step "log in as admin with GRAPH_INITIAL_PASSWORD"
TOKEN="$(login "$HTTP")"
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || fail "login returned no token"

step "the native layer loaded and the UI is in the jar"
NATIVE="$(curl -fs -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$HTTP/api/stats" | jq -r '.nativeLayer')"
case "$NATIVE" in
	"ABI "*) [[ "$NATIVE" != *"not loaded"* ]] || fail "native layer present but not loaded: $NATIVE" ;;
	*) fail "native layer is '$NATIVE'; a release image must carry the Rust layer" ;;
esac
echo "    nativeLayer: $NATIVE"
PAGE="$(curl -fs "http://127.0.0.1:$HTTP/" || true)"
[ -n "$PAGE" ] || fail "the UI page did not answer"
if grep -q "No UI bundle in this build" <<< "$PAGE"; then
	fail "the jar in the image was built without the UI bundle"
fi

step "write 250 nodes and 249 relationships, read them back"
scalar "$HTTP" "$TOKEN" "UNWIND range(1, 250) AS i CREATE (:Smoke {i: i, name: 'n' + toString(i)})" >/dev/null
scalar "$HTTP" "$TOKEN" "MATCH (a:Smoke), (b:Smoke) WHERE b.i = a.i + 1 CREATE (a)-[:NEXT]->(b)" >/dev/null
COUNT="$(scalar "$HTTP" "$TOKEN" "MATCH (n:Smoke) RETURN count(n) AS c")"
[ "$COUNT" = "250" ] || fail "counted $COUNT :Smoke nodes, expected 250"
HOPS="$(scalar "$HTTP" "$TOKEN" "MATCH (:Smoke {i: 1})-[:NEXT*1..10]->(m) RETURN count(m) AS c")"
[ "$HOPS" = "10" ] || fail "a ten-hop walk reached $HOPS nodes, expected 10"

step "online backup through the CLI shipped in the image"
"$ENGINE" exec -e GRAPH_TOKEN="$TOKEN" "$PREFIX-main" \
	metaloom-graph-cli database backup default --server=http://localhost:8080 --out=- > "$WORK/smoke.dump" \
	|| fail "the online backup failed"
[ -s "$WORK/smoke.dump" ] || fail "the backup is empty"
echo "    dump: $(wc -c < "$WORK/smoke.dump") bytes"

# ------------------------------------------------------------------ 2. the shipped default refuses writes

step "with the shipped defaults, a write query is refused"
"$ENGINE" run -d --name "$PREFIX-default" -e GRAPH_INITIAL_PASSWORD="$PASSWORD" \
	-p 127.0.0.1::8080 -p 127.0.0.1::8082 "$IMAGE" >/dev/null
DEFAULT_HTTP="$(host_port "$PREFIX-default" 8080)"
await_ready "$PREFIX-default" "$(host_port "$PREFIX-default" 8082)"
DEFAULT_TOKEN="$(login "$DEFAULT_HTTP")"
STATUS="$(query "$DEFAULT_HTTP" "$DEFAULT_TOKEN" "CREATE (:ShouldNotExist)" | tail -n 1)"
[ "$STATUS" != "200" ] || fail "a write succeeded on a server started without GRAPH_ALLOW_WRITE"
echo "    write answered $STATUS"
"$ENGINE" rm -f "$PREFIX-default" >/dev/null

# ------------------------------------------------------------------ 3. restore into a fresh volume

step "offline restore of the dump into a fresh volume"
"$ENGINE" volume create "$PREFIX-restore" >/dev/null
"$ENGINE" run --rm -i -v "$PREFIX-restore:/data" "$IMAGE" \
	metaloom-graph-cli database restore default --in=- < "$WORK/smoke.dump" >/dev/null \
	|| fail "the restore failed"

step "the restored store serves the same graph"
"$ENGINE" run -d --name "$PREFIX-restored" -v "$PREFIX-restore:/data" -e GRAPH_INITIAL_PASSWORD="$PASSWORD" \
	-p 127.0.0.1::8080 -p 127.0.0.1::8082 "$IMAGE" >/dev/null
RESTORED_HTTP="$(host_port "$PREFIX-restored" 8080)"
await_ready "$PREFIX-restored" "$(host_port "$PREFIX-restored" 8082)"
RESTORED_TOKEN="$(login "$RESTORED_HTTP")"
COUNT="$(scalar "$RESTORED_HTTP" "$RESTORED_TOKEN" "MATCH (n:Smoke) RETURN count(n) AS c")"
[ "$COUNT" = "250" ] || fail "the restored store has $COUNT :Smoke nodes, expected 250"
EDGES="$(scalar "$RESTORED_HTTP" "$RESTORED_TOKEN" "MATCH (:Smoke)-[r:NEXT]->(:Smoke) RETURN count(r) AS c")"
[ "$EDGES" = "249" ] || fail "the restored store has $EDGES :NEXT relationships, expected 249"
"$ENGINE" rm -f "$PREFIX-restored" >/dev/null

# ------------------------------------------------------------------ 4. the binary protocol

step "the binary port answers a query (demo graph, authentication off)"
"$ENGINE" run -d --name "$PREFIX-wire" -e GRAPH_DEMO=true -e GRAPH_DEMO_SIZE=tiny -e GRAPH_AUTH_ENABLED=false \
	-e GRAPH_WIRE_HOST=0.0.0.0 -p 127.0.0.1::8081 -p 127.0.0.1::8082 "$IMAGE" >/dev/null
await_ready "$PREFIX-wire" "$(host_port "$PREFIX-wire" 8082)"
java "$ROOT/tools/WireSmoke.java" 127.0.0.1 "$(host_port "$PREFIX-wire" 8081)" || fail "WireSmoke failed"

echo
echo "SMOKE PASSED: $IMAGE ($STEP checks)"
