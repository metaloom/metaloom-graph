#!/usr/bin/env bash
# Talking to a deployed graph through a port-forward.

PF_PID=""

port_forward_start() {
	port_forward_stop
	$KUBECTL port-forward "svc/$RELEASE-metaloom-graph" "$HTTP_PORT:8080" "$METRICS_PORT:8082" \
		>"$WORK_DIR/port-forward.log" 2>&1 &
	PF_PID=$!
	wait_for 60 "curl -sf http://127.0.0.1:$METRICS_PORT/health" \
		|| fatal "port-forward never became usable; see $WORK_DIR/port-forward.log"
}

port_forward_stop() {
	if [ -n "$PF_PID" ]; then
		kill "$PF_PID" 2>/dev/null || true
		wait "$PF_PID" 2>/dev/null || true
		PF_PID=""
	fi
}

api() { curl -s "http://127.0.0.1:$HTTP_PORT$1"; }

metrics() { curl -s "http://127.0.0.1:$METRICS_PORT/metrics"; }

# Runs a Cypher query, echoing the raw response so a caller can grep it.
query() {
	curl -s -XPOST "http://127.0.0.1:$HTTP_PORT/api/query" \
		-H 'content-type: application/json' \
		-d "{\"query\": $(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/^/"/; s/$/"/')}"
}

query_status() {
	curl -s -o /dev/null -w '%{http_code}' -XPOST "http://127.0.0.1:$HTTP_PORT/api/query" \
		-H 'content-type: application/json' \
		-d "{\"query\": $(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/^/"/; s/$/"/')}"
}

node_count() { json_field "$(api /api/stats)" nodes; }

# The value of one Prometheus sample, by exact series name including its labels.
metric() { metrics | awk -v k="$1" '$1 == k { print $2 }' | head -1; }

graph_wait_ready() {
	wait_for 300 "$KUBECTL get pod -l app.kubernetes.io/name=metaloom-graph \
		-o jsonpath='{.items[0].status.containerStatuses[0].ready}' | grep -q true" \
		|| fatal "the pod never became ready"
}
