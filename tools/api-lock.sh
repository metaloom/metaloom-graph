#!/usr/bin/env bash
#
# The API lock's accept tool: the only thing that writes a <module>/api/<module>.api baseline.
#
# ApiLockTest in each locked module fails the build on any difference between the compiled public API and its
# baseline, and never writes the baseline itself. Recording an intended change is this script, run by a person, and
# the regenerated baseline is committed together with the code that changed the API. See spec/API_LOCK.md.
#
# AI agents: never run `accept` or `policy`. On an API lock failure, make the change additive, or stop and report the
# diff to the user. See AGENTS.md. (`check` is read-only and fine to run.)
#
# Usage:
#   tools/api-lock.sh check  [<module>...]                    compare, write nothing (all locked modules by default)
#   tools/api-lock.sh accept <module> [--allow-breaking]      record the current API as the baseline
#   tools/api-lock.sh policy <module> alpha|stable [--allow-breaking]
#
# <module> is one of graph-core, graph-cypher, graph-protocol, graph-client. Under `policy: stable`, accept refuses
# removals and changes unless --allow-breaking is given. Pass Maven flags through MAVEN_ARGS, e.g. MAVEN_ARGS=-o for
# an offline build.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCKED=(graph-core graph-cypher graph-protocol graph-client)

usage() {
  sed -n '12,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 64
}

is_locked() {
  local candidate="$1" module
  for module in "${LOCKED[@]}"; do
    [ "$module" = "$candidate" ] && return 0
  done
  return 1
}

[ $# -ge 1 ] || usage
COMMAND="$1"
shift
case "$COMMAND" in
  check)
    if [ $# -eq 0 ]; then
      MODULES=("${LOCKED[@]}")
    else
      MODULES=("$@")
    fi
    EXTRA=()
    ;;
  accept | policy)
    [ $# -ge 1 ] || usage
    MODULES=("$1")
    shift
    EXTRA=("$@")
    ;;
  *) usage ;;
esac

for module in "${MODULES[@]}"; do
  if ! is_locked "$module"; then
    echo "api-lock: $module is not a locked module (locked: ${LOCKED[*]})" >&2
    exit 64
  fi
done

# Compile the modules and everything they need, and write each one's test classpath next to its classes. -am resolves
# sibling modules — graph-api-lock above all, which is the dumper the tests use — to this checkout's target/
# directories rather than to whatever an earlier `mvn install` left in ~/.m2, so the tool and the test run the same
# code over the same classes. The native layer is skipped: it is not part of the Java API and the Rust build is slow.
PROJECTS="$(IFS=,; echo "${MODULES[*]}")"
(cd "$ROOT" && mvn -q -pl "$PROJECTS" -am -Dnative.skip=true -Dui.skip=true \
  test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/api-lock.classpath)

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
STATUS=0
for module in "${MODULES[@]}"; do
  DIR="$ROOT/$module"
  CLASSPATH="$DIR/target/test-classes:$DIR/target/classes:$(cat "$DIR/target/api-lock.classpath")"
  "$JAVA" -cp "$CLASSPATH" io.metaloom.graph.apilock.ApiLockTool "$COMMAND" "$module" "$DIR" "${EXTRA[@]}" || STATUS=$?
done
exit "$STATUS"
