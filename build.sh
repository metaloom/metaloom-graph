	if grep -q "^## \[$VERSION\] - Unreleased" CHANGELOG.md; then
		fatal "CHANGELOG.md still says '## [$VERSION] - Unreleased'; give it the release date (spec/RELEASE.md, step 2)"
	fi
#!/usr/bin/env bash
#
# The whole build, locally, in one command: tests, jars, container image, chart, smoke test and, for a
# release, the distribution directory and the git tag. There is no CI/CD pipeline that releases; this
# script is the release process. spec/RELEASE.md describes it step by step.
#
#   ./build.sh [options]
#
#     (no options)     test the reactor with the native layer required, package, build the JVM image,
#                      lint and render the chart, smoke-test the image
#     --release        everything above, plus: refuse a SNAPSHOT version, a dirty tree, or versions
#                      that disagree (pom, chart, UI, CHANGELOG); run the crash profile too; collect
#                      dist/<version>/ with checksums; create the annotated tag v<version> (local only)
#     --skip-tests     package without running the test suite (not allowed with --release)
#     --native         also build the GraalVM binaries and the -native image
#     --helm           also deploy the chart on a throwaway k3d cluster (helm/test/run.sh)
#     --no-image       stop after the jars: no image, no smoke test
#     --help           this
#
#   MAVEN_ARGS   extra arguments for every mvn call (default: -o, offline)
#   ENGINE       docker or podman, passed through to the container scripts
#
# Nothing is pushed. A release ends by printing the commands that publish it, because publishing is the
# one step that cannot be taken back.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

RELEASE=0
SKIP_TESTS=0
NATIVE=0
HELM=0
IMAGE=1
while [ $# -gt 0 ]; do
	case "$1" in
		--release) RELEASE=1 ;;
		--skip-tests) SKIP_TESTS=1 ;;
		--native) NATIVE=1 ;;
		--helm) HELM=1 ;;
		--no-image) IMAGE=0 ;;
		--help|-h) sed -n '2,25p' "$0"; exit 0 ;;
		*) echo "Unknown option '$1'. Try --help." >&2; exit 2 ;;
	esac
	shift
done

# shellcheck disable=SC2206 # word splitting of MAVEN_ARGS is the point
MVN=(mvn -B ${MAVEN_ARGS:--o})

section() {
	echo
	echo "======================================================================"
	echo "== $*"
	echo "======================================================================"
}
fatal() {
	echo "BUILD FAILED: $*" >&2
	exit 1
}

VERSION="$(sed -n '/<artifactId>metaloom-graph<\/artifactId>/,/<\/version>/s|.*<version>\(.*\)</version>.*|\1|p' \
	pom.xml | head -n 1)"
[ -n "$VERSION" ] || fatal "could not read the project version from pom.xml"
REVISION="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
IMAGE_NAME="metaloom/graph-server:$VERSION"

# ---------------------------------------------------------------------------------------- preflight

section "MetaLoom // Graph $VERSION (revision $REVISION)"

# Every place a version is written down, compared with the pom. A release where they disagree ships a
# chart that deploys a different image than the one built, or a UI that reports a different version.
check_versions() {
	local problems=0
	local chart app ui
	chart="$(sed -n 's/^version: *//p' helm/metaloom-graph/Chart.yaml)"
	app="$(sed -n 's/^appVersion: *"\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' helm/metaloom-graph/Chart.yaml)"
	ui="$(jq -r '.version' graph-server/src/main/frontend/package.json 2>/dev/null || echo unknown)"
	for module in graph-*/pom.xml; do
		if ! grep -q "<version>$VERSION</version>" "$module"; then
			echo "  $module does not name parent version $VERSION" >&2
			problems=1
		fi
	done
	[ "$app" = "$VERSION" ] || { echo "  helm/metaloom-graph/Chart.yaml appVersion is '$app'" >&2; problems=1; }
	[ "$chart" = "$VERSION" ] || { echo "  helm/metaloom-graph/Chart.yaml version is '$chart'" >&2; problems=1; }
	[ "$ui" = "$VERSION" ] || { echo "  graph-server/src/main/frontend/package.json version is '$ui'" >&2; problems=1; }
	grep -q "^## \[$VERSION\]" CHANGELOG.md 2>/dev/null \
		|| { echo "  CHANGELOG.md has no '## [$VERSION]' section" >&2; problems=1; }
	return $problems
}

if [ "$RELEASE" = "1" ]; then
	[ "$SKIP_TESTS" = "0" ] || fatal "--release runs every test; drop --skip-tests"
	case "$VERSION" in *-SNAPSHOT) fatal "version $VERSION is a SNAPSHOT; set a release version first (spec/RELEASE.md)" ;; esac
	if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
		fatal "the working tree has uncommitted changes; a release is built from a commit"
	fi
	if git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null; then
		fatal "tag v$VERSION already exists; a version is released once"
	fi
	check_versions || fatal "the versions above disagree with the pom's $VERSION"
	if grep -q "^## \[$VERSION\] - Unreleased" CHANGELOG.md; then
		fatal "CHANGELOG.md still says '## [$VERSION] - Unreleased'; give it the release date (spec/RELEASE.md, step 2)"
	fi
else
	check_versions || echo "WARNING: versions disagree (see above); --release would refuse this" >&2
fi

command -v cargo >/dev/null 2>&1 || fatal "cargo is not on PATH: the native layer would be skipped silently"
if [ "$IMAGE" = "1" ]; then
	command -v "${ENGINE:-docker}" >/dev/null 2>&1 || command -v podman >/dev/null 2>&1 \
		|| fatal "neither docker nor podman is on PATH (or pass --no-image)"
fi

# ---------------------------------------------------------------------------------------- maven

# required=true turns a missing native library into a failure rather than a silent pure-Java build; a
# release jar without the Rust layer is the one mistake nothing else in the build would catch.
NATIVE_REQUIRED=(-Dmetaloom.graph.native.required=true)

if [ "$SKIP_TESTS" = "1" ]; then
	section "Package (tests skipped)"
	"${MVN[@]}" clean package -DskipTests "${NATIVE_REQUIRED[@]}" || fatal "maven package failed"
else
	section "Test and package: the reactor, native layer required"
	# verify rather than package, so the API lock and every other test runs, then the jars are built.
	"${MVN[@]}" clean verify "${NATIVE_REQUIRED[@]}" || fatal "the reactor build failed"
	if [ "$RELEASE" = "1" ]; then
		section "Crash profile (graph-core)"
		"${MVN[@]}" test -pl graph-core -Pcrash "${NATIVE_REQUIRED[@]}" || fatal "the crash profile failed"
	fi
fi

if [ "$NATIVE" = "1" ]; then
	section "GraalVM binaries"
	GRAALVM_HOME="${GRAALVM_HOME:-/opt/jvm/graalvm-25}" "${MVN[@]}" -Pgraalvm verify -DskipTests \
		-pl graph-cli,graph-server -am || fatal "the native-image build failed"
fi

# ---------------------------------------------------------------------------------------- image + chart

if [ "$IMAGE" = "1" ]; then
	section "Container image"
	# LATEST only for a release: an ordinary build must not move "latest" off the last release.
	LATEST="$([ "$RELEASE" = "1" ] && echo true || echo false)" container/build-containers.sh jvm \
		|| fatal "the image build failed"
	if [ "$NATIVE" = "1" ]; then
		LATEST="$([ "$RELEASE" = "1" ] && echo true || echo false)" container/build-containers.sh native \
			|| fatal "the native image build failed"
	fi
fi

section "Helm chart: lint and render"
if command -v helm >/dev/null 2>&1; then
	helm lint helm/metaloom-graph || fatal "helm lint failed"
	helm template smoke helm/metaloom-graph >/dev/null || fatal "the chart does not render"
else
	echo "WARNING: helm is not on PATH; the chart was not linted" >&2
	[ "$RELEASE" = "0" ] || fatal "a release lints the chart; install helm"
fi

# ---------------------------------------------------------------------------------------- smoke

if [ "$IMAGE" = "1" ]; then
	section "Smoke test: $IMAGE_NAME"
	tools/smoke.sh "$IMAGE_NAME" || fatal "the smoke test failed"
	if [ "$HELM" = "1" ]; then
		section "Helm on k3d"
		IMAGE_TAG="$VERSION" helm/test/run.sh || fatal "the chart test failed"
	fi
fi

# ---------------------------------------------------------------------------------------- release

if [ "$RELEASE" = "1" ]; then
	section "Distribution: dist/$VERSION"
	DIST="dist/$VERSION"
	rm -rf "$DIST"
	mkdir -p "$DIST"
	cp graph-server/target/graph-server.jar "$DIST/graph-server-$VERSION.jar"
	cp graph-cli/target/graph-cli-cli.jar "$DIST/graph-cli-$VERSION-cli.jar"
	for module in graph-core graph-cypher graph-protocol graph-client; do
		cp "$module/target/$module-$VERSION.jar" "$DIST/"
	done
	helm package helm/metaloom-graph --destination "$DIST" >/dev/null || fatal "helm package failed"
	# The release notes are this version's CHANGELOG section, so the tag, the notes and the file agree.
	awk -v v="## [$VERSION]" 'index($0, v) == 1 { on = 1; print; next } on && /^## \[/ { exit } on' \
		CHANGELOG.md > "$DIST/RELEASE_NOTES.md"
	( cd "$DIST" && sha256sum -- * > SHA256SUMS )
	ls -l "$DIST"

	section "Tag v$VERSION"
	git tag -a "v$VERSION" -m "MetaLoom // Graph $VERSION" || fatal "could not create tag v$VERSION"
	echo "Created the annotated tag v$VERSION on $REVISION (local only)."

	section "Release $VERSION is built. To publish it:"
	cat <<EOF
  git push origin v$VERSION
  ${ENGINE:-docker} push metaloom/graph-server:$VERSION
  ${ENGINE:-docker} push metaloom/graph-server:latest
  gh release create v$VERSION --prerelease --title "MetaLoom // Graph $VERSION" \\
      --notes-file $DIST/RELEASE_NOTES.md $DIST/*.jar $DIST/*.tgz $DIST/SHA256SUMS

Then move the pom to the next development version (spec/RELEASE.md, step 9).
EOF
else
	section "Build complete: $VERSION ($REVISION)"
	echo "  graph-server/target/graph-server.jar"
	echo "  graph-cli/target/graph-cli-cli.jar"
	[ "$IMAGE" = "0" ] || echo "  image $IMAGE_NAME (smoke-tested)"
fi
