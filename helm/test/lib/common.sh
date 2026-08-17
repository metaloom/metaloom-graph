#!/usr/bin/env bash
# Assertions and output. Sourced by run.sh.

PASS=0
FAIL=0
XFAIL=0

section() { echo; echo "=============================================================="; echo "  $*"; echo "=============================================================="; }
pass()    { PASS=$((PASS + 1)); echo "  [ok]   $*"; }
fail()    { FAIL=$((FAIL + 1)); echo "  [FAIL] $*" >&2; }
xfail()   { XFAIL=$((XFAIL + 1)); echo "  [xfail] $*  (known gap, not a regression)"; }
fatal()   { echo; echo "FATAL: $*" >&2; dump_diagnostics; exit 1; }

check() { if eval "$1" >/dev/null 2>&1; then pass "${2:-$1}"; else fail "${2:-$1}"; fi; }

check_eq() {
	if [ "$1" = "$2" ]; then pass "${3:-$1 == $2}"; else fail "${3:-expected '$2', got '$1'}"; fi
}

check_contains() {
	case "$1" in *"$2"*) pass "${3:-contains '$2'}" ;; *) fail "${3:-'$2' not found in: $1}" ;; esac
}

check_nonempty() {
	if [ -n "$1" ] && [ "$1" != "null" ]; then pass "${2:-non-empty}"; else fail "${2:-was empty}"; fi
}

# Polls until the command succeeds or the deadline passes. Everything asynchronous goes through this
# rather than through a sleep, so a slow machine is slow rather than red.
wait_for() {
	local seconds="$1"; shift
	local deadline=$((SECONDS + seconds))
	while [ $SECONDS -lt $deadline ]; do
		if eval "$*" >/dev/null 2>&1; then return 0; fi
		sleep 2
	done
	return 1
}

require_cmd() { command -v "$1" >/dev/null 2>&1 || fatal "$1 is not on PATH"; }

json_field() { echo "$1" | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p" | head -1; }

summary() {
	echo
	echo "=============================================================="
	echo "  $PASS passed, $FAIL failed, $XFAIL known gaps"
	echo "=============================================================="
	[ "$FAIL" -eq 0 ]
}
