#!/usr/bin/env bash
#
# Build the container images.
#
#   container/build-containers.sh [jvm|native|both]      (default: jvm)
#
#   TAG=1.2.3   the tag to apply (default: latest). The native variant gets a "-native" suffix.
#   ENGINE      docker or podman (default: whichever is on PATH, docker first)
#   GRAALVM_HOME  for the native build (default: /opt/jvm/graalvm-25)
#
# The build context is the REPOSITORY ROOT, not this directory, because the Containerfiles ADD build
# outputs from graph-server/target. That is also why .dockerignore and .containerignore matter here:
# without them the context would include /data, which is a real store and can be gigabytes.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WHAT="${1:-jvm}"
TAG="${TAG:-latest}"
IMAGE="metaloom/graph-server"

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

JAR="$ROOT/graph-server/target/graph-server-0.0.1-SNAPSHOT.jar"
BINARY="$ROOT/graph-server/target/native/graph-server"
# Both images also carry the backup tool, because a backup that needs a second artifact fetched and
# version-matched is a backup that gets taken once. Two artifacts per image, so two checks.
CLI_JAR="$ROOT/graph-cli/target/graph-cli-0.0.1-SNAPSHOT-cli.jar"
CLI_BINARY="$ROOT/graph-cli/target/native/metaloom-graph-cli"

build_jvm() {
	# Checked rather than built: this script does one thing, and a script that silently ran a Maven
	# build would hide a stale jar behind a fresh image.
	if [ ! -f "$JAR" ]; then
		echo "No jar at $JAR" >&2
		echo "Build it first:  mvn -o clean package -DskipTests" >&2
		exit 1
	fi
	# The -cli classifier, which is the shaded one. graph-cli-<version>.jar without it is the library
	# graph-server depends on for the dump format, and has no engine inside it — ADDing that one would
	# produce an image whose CLI fails on the first class it loads.
	if [ ! -f "$CLI_JAR" ]; then
		echo "No CLI jar at $CLI_JAR" >&2
		echo "Build it first:  mvn -o clean package -DskipTests" >&2
		exit 1
	fi
	warn_about_skipped_parts "$JAR"
	echo "==> $IMAGE:$TAG  (JVM, from $(basename "$JAR") and $(basename "$CLI_JAR"))"
	"$ENGINE" build -f "$ROOT/container/Containerfile" -t "$IMAGE:$TAG" "$ROOT"
}

#
# Both the UI and the native layer are optional at build time and their absence is invisible at run
# time: the server starts, answers every API route, and is simply worse. Packaging that into an image
# and shipping it is how it stops being noticed at all, so the jar is inspected and the omission is
# named here rather than discovered later.
#
warn_about_skipped_parts() {
	local jar="$1"
	# Listed ONCE, into a variable, and not piped into `grep -q`. Under `set -o pipefail` that pipeline
	# reports 141: grep exits at the first match, unzip is SIGPIPEd part-way through a twelve-megabyte
	# listing, and the pipeline's status is unzip's. So `if ! ...` fired on every jar that HAD a UI —
	# the check warned exactly when it should have been quiet, which is worse than not checking at all,
	# because a warning that is always there is a warning nobody reads.
	local listing
	listing="$(unzip -l "$jar" 2>/dev/null || true)"
	if ! grep -q "webroot/index.html" <<< "$listing"; then
		echo "WARNING: this jar has no browser UI (built with -Dui.skip=true, or without npm)." >&2
		echo "         The image will serve the API and a page explaining the UI is missing." >&2
	fi
	if ! grep -qE "\.so$" <<< "$listing"; then
		echo "WARNING: this jar has no native library (built with -Dnative.skip=true, or without cargo)." >&2
		echo "         The image falls back to pure Java; /api/stats will report nativeLayer 'not built'." >&2
	fi
}

build_native() {
	# `verify`, not `package`: native-image is bound to the verify phase in both poms, so `mvn package`
	# with -Pgraalvm finishes happily and produces no binary at all — which reads as a broken profile.
	for missing in "$BINARY" "$CLI_BINARY"; do
		if [ ! -x "$missing" ]; then
			echo "No native binary at $missing" >&2
			echo "Build it first:  GRAALVM_HOME=${GRAALVM_HOME:-/opt/jvm/graalvm-25} mvn -o -Pgraalvm clean verify -DskipTests" >&2
			exit 1
		fi
	done
	echo "==> $IMAGE:$TAG-native  (GraalVM, server and CLI)"
	"$ENGINE" build -f "$ROOT/container/Containerfile.native" -t "$IMAGE:$TAG-native" "$ROOT"
}

case "$WHAT" in
	jvm) build_jvm ;;
	native) build_native ;;
	both) build_jvm; build_native ;;
	*) echo "usage: $(basename "$0") [jvm|native|both]" >&2; exit 2 ;;
esac

echo
echo "Run it:"
echo "  $ENGINE run --rm -d --name g -e GRAPH_DEMO=true -e GRAPH_ALLOW_BACKUP=true -p 8080:8080 -p 8082:8082 $IMAGE:$TAG"
echo "  curl -s localhost:8080/api/stats     # nativeLayer says whether the Rust layer loaded"
echo "  curl -s localhost:8082/metrics | head"
echo
echo "Back it up (--server, because the running server holds the store's lock):"
echo "  $ENGINE exec g metaloom-graph-cli database backup default --server=http://localhost:8080 --out=- > g.dump"
