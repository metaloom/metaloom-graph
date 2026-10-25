#!/usr/bin/env bash
#
# Build the container images.
#
#   container/build-containers.sh [jvm|native|both]      (default: jvm)
#
#   TAG=1.2.3     the version tag to apply (default: the project version, read from the root pom.xml)
#   LATEST=false  do not also tag the image "latest" (default: true). The native variant gets a
#                 "-native" suffix on both tags: <version>-native and latest-native.
#   ENGINE        docker or podman (default: whichever is on PATH, docker first)
#   GRAALVM_HOME  for the native build (default: /opt/jvm/graalvm-25)
#
# Nothing in here names a version. The jars under target/ have version-free names (finalName in the
# server and CLI poms), and the tag comes from the pom, so bumping the version is a pom change only.
#
# The build context is the REPOSITORY ROOT, not this directory, because the Containerfiles ADD build
# outputs from graph-server/target. That is also why .dockerignore and .containerignore matter here:
# without them the context would include /data, which is a real store and can be gigabytes.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WHAT="${1:-jvm}"

# The project version, read from the root pom rather than asked of Maven (which would need a plugin and,
# offline, may not have it): the first <version> after the project's own artifactId, so the parent's
# version, which comes later in the file, is never picked up by mistake.
VERSION="$(sed -n '/<artifactId>metaloom-graph<\/artifactId>/,/<\/version>/s|.*<version>\(.*\)</version>.*|\1|p' \
	"$ROOT/pom.xml" | head -n 1)"
if [ -z "$VERSION" ]; then
	echo "Could not read the project version from $ROOT/pom.xml" >&2
	exit 1
fi
TAG="${TAG:-$VERSION}"
LATEST="${LATEST:-true}"
IMAGE="metaloom/graph-server"
REVISION="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"

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

JAR="$ROOT/graph-server/target/graph-server.jar"
BINARY="$ROOT/graph-server/target/native/graph-server"
# Both images also carry the backup tool, because a backup that needs a second artifact fetched and
# version-matched is a backup that gets taken once. Two artifacts per image, so two checks.
CLI_JAR="$ROOT/graph-cli/target/graph-cli-cli.jar"
CLI_BINARY="$ROOT/graph-cli/target/native/metaloom-graph-cli"

# The tags for one variant: the version tag, and "latest" unless LATEST=false.
tag_args() {
	local suffix="$1"
	local args=(-t "$IMAGE:$TAG$suffix")
	if [ "$LATEST" = "true" ]; then
		args+=(-t "$IMAGE:latest$suffix")
	fi
	echo "${args[@]}"
}

# OCI labels that say which build an image is, so `docker inspect` answers it without running it.
label_args() {
	echo "--label org.opencontainers.image.version=$VERSION --label org.opencontainers.image.revision=$REVISION"
}

build_jvm() {
	# Checked rather than built: this script does one thing, and a script that silently ran a Maven
	# build would hide a stale jar behind a fresh image. build.sh at the root is the one that builds.
	if [ ! -f "$JAR" ]; then
		echo "No jar at $JAR" >&2
		echo "Build it first:  mvn -o clean package -DskipTests   (or ./build.sh)" >&2
		exit 1
	fi
	# The -cli classifier, which is the shaded one. graph-cli.jar without it is the library graph-server
	# depends on for the dump format, and has no engine inside it — ADDing that one would produce an image
	# whose CLI fails on the first class it loads.
	if [ ! -f "$CLI_JAR" ]; then
		echo "No CLI jar at $CLI_JAR" >&2
		echo "Build it first:  mvn -o clean package -DskipTests   (or ./build.sh)" >&2
		exit 1
	fi
	require_jar_version "$JAR"
	require_jar_version "$CLI_JAR"
	warn_about_skipped_parts "$JAR"
	echo "==> $IMAGE:$TAG  (JVM, version $VERSION, revision $REVISION)"
	# shellcheck disable=SC2046 # the helpers print separate words on purpose
	"$ENGINE" build -f "$ROOT/container/Containerfile" $(tag_args "") $(label_args) "$ROOT"
}

#
# A jar left over from before a version bump has the same version-free file name as the new one, so the
# name proves nothing. Its manifest does: Implementation-Version is written from ${project.version}.
#
require_jar_version() {
	local jar="$1"
	local found
	found="$(unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r' \
		| sed -n 's/^Implementation-Version: //p' | head -n 1)"
	if [ "$found" != "$VERSION" ]; then
		echo "$(basename "$jar") was built as version '${found:-unknown}', but the pom says $VERSION." >&2
		echo "It is stale. Rebuild it:  mvn -o clean package -DskipTests   (or ./build.sh)" >&2
		exit 1
	fi
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
	echo "==> $IMAGE:$TAG-native  (GraalVM, server and CLI, version $VERSION, revision $REVISION)"
	# shellcheck disable=SC2046
	"$ENGINE" build -f "$ROOT/container/Containerfile.native" $(tag_args "-native") $(label_args) "$ROOT"
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
