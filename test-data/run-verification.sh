#!/usr/bin/env bash
#
# Boot a throwaway Neo4j, import every dataset under datasets/ and check the
# node/relationship counts declared in queries.csv against it.
#
#   ./run-verification.sh                     # verify all four datasets
#   ./run-verification.sh --dataset 03-social # verify one
#   ./run-verification.sh --keep              # leave the container running
#
# Environment overrides: NEO4J_IMAGE, NEO4J_PASSWORD, HTTP_PORT, BOLT_PORT.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

IMAGE="${NEO4J_IMAGE:-neo4j:5.26-community}"
CONTAINER="cypher-data-neo4j"
PASSWORD="${NEO4J_PASSWORD:-cypherdata123}"
HTTP_PORT="${HTTP_PORT:-7474}"
BOLT_PORT="${BOLT_PORT:-7687}"
KEEP=0
DATASET_ARGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --keep)     KEEP=1; shift ;;
    --image)    IMAGE="$2"; shift 2 ;;
    --dataset)  DATASET_ARGS+=(--dataset "$2"); shift 2 ;;
    -h|--help)  sed -n '2,12p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *)          echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

cleanup() {
  if [ "$KEEP" -eq 1 ]; then
    echo
    echo "container '$CONTAINER' left running: http://localhost:${HTTP_PORT}  (neo4j / ${PASSWORD})"
    echo "remove it with: docker rm -f $CONTAINER"
  else
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "pulling $IMAGE ..."
  docker pull "$IMAGE"
fi

docker rm -f "$CONTAINER" >/dev/null 2>&1 || true

echo "starting $IMAGE as '$CONTAINER' (http ${HTTP_PORT}, bolt ${BOLT_PORT}) ..."
docker run -d --name "$CONTAINER" \
  -e NEO4J_AUTH="neo4j/${PASSWORD}" \
  -e NEO4J_server_memory_pagecache_size=256M \
  -p "${HTTP_PORT}:7474" \
  -p "${BOLT_PORT}:7687" \
  "$IMAGE" >/dev/null

printf 'waiting for bolt'
for _ in $(seq 1 120); do
  if docker exec "$CONTAINER" cypher-shell -u neo4j -p "$PASSWORD" \
       --non-interactive "RETURN 1;" >/dev/null 2>&1; then
    echo " - ready"
    break
  fi
  printf '.'
  sleep 1
done

if ! docker exec "$CONTAINER" cypher-shell -u neo4j -p "$PASSWORD" \
       --non-interactive "RETURN 1;" >/dev/null 2>&1; then
  echo
  echo "neo4j did not become ready in time; last container logs:" >&2
  docker logs --tail 40 "$CONTAINER" >&2
  exit 1
fi

python3 "${HERE}/verify.py" \
  --container "$CONTAINER" \
  --password "$PASSWORD" \
  "${DATASET_ARGS[@]+"${DATASET_ARGS[@]}"}"
