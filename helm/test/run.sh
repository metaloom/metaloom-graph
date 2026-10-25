#!/usr/bin/env bash
#
# Deploys the chart on a throwaway k3d cluster and checks that what comes up is a working graph.
#
#   helm/test/run.sh [options]
#
#     --build     build the jar and the image first
#     --core      the offline phases only (lint and render) — no cluster, no docker
#     --keep      leave the cluster running afterwards
#     --reuse     reuse an existing cluster instead of creating one
#     --down      delete the cluster and exit
#     --help      this
#
# k3d and kubectl are downloaded into helm/test/.bin on first use, and KUBECONFIG points inside
# helm/test/.work — this never touches ~/.kube/config.
#
# The phase that matters most is 7: delete the pod and assert the same data comes back. Everything
# else here could be true of a chart for a stateless service; that one is the thing this project is.
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR="$ROOT/helm/test"
BIN_DIR="$TEST_DIR/.bin"
WORK_DIR="$TEST_DIR/.work"
CHART="$ROOT/helm/metaloom-graph"

CLUSTER="${CLUSTER:-metaloom-graph-test}"
RELEASE="${RELEASE:-g}"
IMAGE_TAG="${IMAGE_TAG:-helmtest}"
HTTP_PORT="${HTTP_PORT:-18080}"
METRICS_PORT="${METRICS_PORT:-18082}"
# The administrator's first password, set through the chart's auth.initialPassword: authentication is on,
# as it is in every default install, and the phases below log in rather than turning it off.
# Fixed rather than random: --reuse keeps the volume, and with it the identity file the first run wrote.
GRAPH_PASSWORD="${GRAPH_PASSWORD:-helm-test-password}"

BUILD=0
CORE_ONLY=0
KEEP=0
REUSE=0

while [ $# -gt 0 ]; do
	case "$1" in
		--build) BUILD=1 ;;
		--core) CORE_ONLY=1 ;;
		--keep) KEEP=1 ;;
		--reuse) REUSE=1; KEEP=1 ;;
		--down) DOWN=1 ;;
		--help|-h) sed -n '2,20p' "$0"; exit 0 ;;
		*) echo "Unknown option '$1'. Try --help." >&2; exit 2 ;;
	esac
	shift
done

mkdir -p "$BIN_DIR" "$WORK_DIR"
export KUBECONFIG="$WORK_DIR/kubeconfig"
K3D="$BIN_DIR/k3d"
KUBECTL="$BIN_DIR/kubectl --request-timeout=30s"

# shellcheck source=lib/common.sh
. "$TEST_DIR/lib/common.sh"
# shellcheck source=lib/cluster.sh
. "$TEST_DIR/lib/cluster.sh"
# shellcheck source=lib/graph.sh
. "$TEST_DIR/lib/graph.sh"

if [ "${DOWN:-0}" = "1" ]; then
	ensure_tools
	cluster_down
	echo "Cluster $CLUSTER deleted."
	exit 0
fi

cleanup() {
	port_forward_stop
	if [ "$KEEP" = "0" ] && [ "$CORE_ONLY" = "0" ]; then
		cluster_down
	fi
}
trap cleanup EXIT

# ---------------------------------------------------------------- 1. the chart on its own

section "1. Chart validation"
require_cmd helm
helm lint "$CHART" >/dev/null 2>&1 && pass "helm lint" || fail "helm lint"

# Rendered under the combinations that actually change the output, because a template that only ever
# renders with default values is a template that has been tested once.
for combination in \
	"" \
	"--set mcp.enabled=true" \
	"--set metrics.enabled=false" \
	"--set wire.enabled=false" \
	"--set persistence.enabled=false" \
	"--set persistence.existingClaim=mine" \
	"--set ingress.enabled=true" \
	"--set metrics.serviceMonitor.enabled=true" \
	"--set graph.allowWrite=true --set graph.demo=true" \
	"--set wire.tls.enabled=true --set wire.tls.secretName=graph-tls" \
	"--set wire.tls.enabled=true --set wire.tls.secretName=graph-tls --set wire.tls.only=true"
do
	# shellcheck disable=SC2086
	if helm template "$RELEASE" "$CHART" $combination >/dev/null 2>&1; then
		pass "renders with '${combination:-defaults}'"
	else
		fail "renders with '${combination:-defaults}'"
	fi
done

# The single-writer rule, encoded. A chart that let this through would produce a crash-looping second
# pod and no explanation.
# Captured rather than piped: `helm template` exits non-zero here (which is the point), and under
# `set -o pipefail` a pipeline inherits that failure even when the grep matched.
refusal="$(helm template "$RELEASE" "$CHART" --set replicaCount=2 2>&1 || true)"
check_contains "$refusal" "LOCK file" "replicaCount=2 is refused, naming the LOCK file"

# The pairing that silently disables the native layer if it is ever broken.
rendered="$(helm template "$RELEASE" "$CHART")"
check_contains "$rendered" "readOnlyRootFilesystem: true" "root filesystem is read-only"
check_contains "$rendered" "mountPath: /tmp" "and /tmp is a writable mount, or the native layer would not load"
check_contains "$rendered" "GRAPH_WIRE_HOST" "the wire host is set explicitly rather than left to the image"

# Helm reads a YAML integer of a million or more as a float and prints it as 1.8e+06, which the server
# refuses to parse — so the default sessionTimeoutMillis of 1800000 crash-looped every install until the
# templates piped numbers through int64. Checked with the default and with a large value set by hand.
check_contains "$rendered" '"1800000"' "a large integer renders as an integer (the default session timeout)"
large="$(helm template "$RELEASE" "$CHART" --set graph.queryTimeoutMillis=3600000 --set graph.slowQueryMillis=86400000)"
check_contains "$large" '"3600000"' "and so does one set with --set"
if echo "$large$rendered" | grep -qE 'value: "?[0-9.]+e\+[0-9]+'; then
	fail "a value rendered in scientific notation"
else
	pass "no value renders in scientific notation"
fi

# TLS on the binary port. The refusal first, because a chart that mounted an empty secret name would
# produce a pod stuck in ContainerCreating with the reason three kubectl commands away.
tls_refusal="$(helm template "$RELEASE" "$CHART" --set wire.tls.enabled=true 2>&1 || true)"
check_contains "$tls_refusal" "wire.tls.secretName is required" "TLS without a secret name is refused by name"

tls_rendered="$(helm template "$RELEASE" "$CHART" --set wire.tls.enabled=true --set wire.tls.secretName=graph-tls)"
check_contains "$tls_rendered" "GRAPH_WIRE_TLS_CERT" "the certificate path reaches the container"
check_contains "$tls_rendered" "secretName: graph-tls" "from the secret the operator named"
check_contains "$tls_rendered" "defaultMode: 0440" "and mounted 0440: not world-readable, and still readable by uid 1000 in the fsGroup"
# The migration property, asserted as a property of the chart rather than left to the operator: adding
# a certificate must leave the port every existing client is using open.
if echo "$tls_rendered" | grep -qE '^\s+- name: wire$'; then
	pass "the cleartext port stays open until wire.tls.only"
else
	fail "adding a certificate closed the port every existing client is using"
fi

# ...and that only closes it.
only_rendered="$(helm template "$RELEASE" "$CHART" --set wire.tls.enabled=true --set wire.tls.secretName=graph-tls --set wire.tls.only=true)"
check_contains "$only_rendered" "GRAPH_WIRE_TLS_ONLY" "wire.tls.only reaches the container"
if echo "$only_rendered" | grep -qE '^\s+- name: wire$'; then
	fail "wire.tls.only left the cleartext port in the Service"
else
	pass "wire.tls.only removes the cleartext port from the Service and the container"
fi

if [ "$CORE_ONLY" = "1" ]; then
	summary
	exit $?
fi

# ---------------------------------------------------------------- 2. cluster

ensure_tools
if [ "$BUILD" = "1" ]; then
	build_images
fi
require_images
cluster_up
import_images

section "2. Server-side dry run"
$KUBECTL apply --dry-run=server -f <(helm template "$RELEASE" "$CHART" -f "$TEST_DIR/values/default.yaml") \
	>/dev/null 2>&1 && pass "the API server accepts the manifests" || fail "server-side dry run"

# ---------------------------------------------------------------- 3. install

section "3. Install"
helm upgrade --install "$RELEASE" "$CHART" \
	-f "$TEST_DIR/values/default.yaml" \
	--set image.tag="$IMAGE_TAG" --set auth.initialPassword="$GRAPH_PASSWORD" \
	--wait --timeout 5m >/dev/null 2>&1 || fatal "helm install failed"
pass "installed"
graph_wait_ready
pass "pod is ready"

port_forward_start
pass "port-forward is up"

# ---------------------------------------------------------------- 4. it is a graph

section "4. The API"
stats="$(api /api/stats)"
check_nonempty "$stats" "/api/stats answers"
seeded="$(node_count)"
check "[ '${seeded:-0}' -gt 0 ]" "the demo graph was seeded ($seeded nodes)"
check_contains "$(api /api/schema)" "labels" "/api/schema answers"

result="$(query 'MATCH (a:Airport) RETURN a.code AS code LIMIT 5')"
check_contains "$result" "columns" "a Cypher query returns a result"

# ---------------------------------------------------------------- 5. writes are off by default

section "5. Read-only by default"
status="$(query_status 'CREATE (n:Thing) RETURN n')"
check_eq "$status" "403" "a write query is refused, even for the administrator"
check_eq "$(anonymous_query_status)" "401" "and a query with no credential is refused outright"

# ---------------------------------------------------------------- 6. metrics

section "6. Metrics"
scrape="$(metrics)"
check_contains "$scrape" "graph_store_nodes" "the scrape carries the store gauges"
check_contains "$scrape" "graph_queries_total" "and the query counters"
exported="$(metric "graph_store_nodes{database=\"default\"}")"
# Proves the cached tier is actually wired to the same store the API is reading, which is the one
# thing a scrape full of plausible zeroes would not tell you.
check_eq "${exported%.*}" "$seeded" "graph_store_nodes agrees with /api/stats"
check_contains "$(curl -s "http://127.0.0.1:$METRICS_PORT/ready")" "ready" "/ready says ready"

# ---------------------------------------------------------------- 7. it survives

section "7. Restart — the phase this whole harness exists for"
$KUBECTL delete pod -l app.kubernetes.io/name=metaloom-graph --wait=true >/dev/null 2>&1
port_forward_stop
graph_wait_ready
port_forward_start
after="$(node_count)"
check_eq "$after" "$seeded" "the same node count came back after the pod was deleted"
recovery="$(metric "graph_store_recovery_millis{database=\"default\"}")"
check_nonempty "${recovery:-}" "recovery time is reported ($recovery ms)"

# ---------------------------------------------------------------- 8. upgrade turns writes on

section "8. Upgrade"
helm upgrade "$RELEASE" "$CHART" \
	-f "$TEST_DIR/values/write-enabled.yaml" \
	--set image.tag="$IMAGE_TAG" --set auth.initialPassword="$GRAPH_PASSWORD" \
	--wait --timeout 5m >/dev/null 2>&1 || fail "helm upgrade failed"
port_forward_stop
graph_wait_ready
port_forward_start
status="$(query_status 'CREATE (n:Thing {name: "from the harness"}) RETURN n')"
check_eq "$status" "200" "a write query is accepted after allowWrite=true"
check_eq "$(node_count)" "$((seeded + 1))" "and the node is really there"

# ---------------------------------------------------------------- 9. the data outlives the release

section "9. Uninstall and reinstall against the retained volume"
helm uninstall "$RELEASE" >/dev/null 2>&1
port_forward_stop
# A StatefulSet's volumeClaimTemplate PVC is deliberately NOT deleted with the release. That is the
# behaviour somebody is relying on the first time they reinstall a chart by mistake.
check "$KUBECTL get pvc | grep -q data-" "the PVC survived the uninstall"
helm upgrade --install "$RELEASE" "$CHART" \
	-f "$TEST_DIR/values/write-enabled.yaml" \
	--set image.tag="$IMAGE_TAG" --set auth.initialPassword="$GRAPH_PASSWORD" \
	--wait --timeout 5m >/dev/null 2>&1 || fail "reinstall failed"
graph_wait_ready
port_forward_start
check_eq "$(node_count)" "$((seeded + 1))" "the data survived a full uninstall and reinstall"

summary
