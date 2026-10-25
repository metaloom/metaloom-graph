#!/usr/bin/env bash
#
# Writes the golden fixture for the CURRENT store format version:
#   graph-core/src/test/resources/format/v<StoreFormat.CURRENT_VERSION>/
#     root.zip  expected.txt  expected-auth.txt  fixture.properties
#
# Run it once per format version, right after bumping StoreFormat.CURRENT_VERSION and registering the migration step
# (spec/MIGRATION.md section 6), and commit the directory it writes. It refuses to overwrite an existing fixture:
# a fixture of an older version is never regenerated, because it is the only evidence of what that version's bytes
# were. --replace overwrites the CURRENT version's fixture, and is only for growing what the fixture covers while
# the format itself has not changed.
#
# Usage:
#   tools/golden-fixture.sh [--replace]
set -u -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/graph-core"

# The pure-Java path is enough: the fixture's bytes do not depend on whether the native layer is loaded, and skipping
# the crate keeps this runnable on a machine without cargo.
(cd "$ROOT" && mvn -o -q test-compile -pl graph-core -am -Dnative.skip=true) || exit 2

if [ ! -f "$CORE/target/test-classpath.txt" ]; then
  (cd "$CORE" && mvn -o -q dependency:build-classpath \
      -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test) || exit 2
fi
CLASSPATH="$CORE/target/classes:$CORE/target/test-classes:$(cat "$CORE/target/test-classpath.txt")"

cd "$ROOT" && exec java --enable-native-access=ALL-UNNAMED -cp "$CLASSPATH" \
  io.metaloom.graph.core.format.GoldenFixtureGenerator --out="$CORE/src/test/resources/format" "$@"
