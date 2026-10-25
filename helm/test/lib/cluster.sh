#!/usr/bin/env bash
# k3d and kubectl: downloaded into test/.bin, never touching the user's ~/.kube/config.

K3D_VERSION="${K3D_VERSION:-v5.8.3}"
KUBECTL_VERSION="${KUBECTL_VERSION:-v1.31.4}"

ensure_tools() {
	mkdir -p "$BIN_DIR"
	if [ ! -x "$BIN_DIR/k3d" ]; then
		echo "Downloading k3d $K3D_VERSION"
		curl -sSfL "https://github.com/k3d-io/k3d/releases/download/$K3D_VERSION/k3d-linux-amd64" \
			-o "$BIN_DIR/k3d" || fatal "could not download k3d"
		chmod +x "$BIN_DIR/k3d"
	fi
	if [ ! -x "$BIN_DIR/kubectl" ]; then
		echo "Downloading kubectl $KUBECTL_VERSION"
		curl -sSfL "https://dl.k8s.io/release/$KUBECTL_VERSION/bin/linux/amd64/kubectl" \
			-o "$BIN_DIR/kubectl" || fatal "could not download kubectl"
		chmod +x "$BIN_DIR/kubectl"
	fi
	require_cmd docker
	require_cmd helm
}

build_images() {
	section "Building the image"
	( cd "$ROOT" && mvn -o clean package -DskipTests ) || fatal "maven build failed"
	# LATEST=false: a throwaway test tag must not move "latest" off the release it points at.
	TAG="$IMAGE_TAG" LATEST=false "$ROOT/container/build-containers.sh" jvm || fatal "image build failed"
}

require_images() {
	docker image inspect "metaloom/graph-server:$IMAGE_TAG" >/dev/null 2>&1 \
		|| fatal "No metaloom/graph-server:$IMAGE_TAG image. Run with --build, or build it yourself."
}

cluster_up() {
	section "Cluster"
	if "$K3D" cluster list 2>/dev/null | grep -q "^$CLUSTER "; then
		echo "Reusing cluster $CLUSTER"
	else
		"$K3D" cluster create "$CLUSTER" --agents 0 --wait || fatal "could not create the cluster"
	fi
	"$K3D" kubeconfig get "$CLUSTER" > "$KUBECONFIG" || fatal "could not write a kubeconfig"
	chmod 600 "$KUBECONFIG"
	$KUBECTL cluster-info >/dev/null || fatal "cluster is not answering"
	pass "cluster $CLUSTER is up"
}

import_images() {
	"$K3D" image import "metaloom/graph-server:$IMAGE_TAG" -c "$CLUSTER" >/dev/null \
		|| fatal "could not import the image into the cluster"
	pass "image imported"
}

cluster_down() {
	"$K3D" cluster delete "$CLUSTER" >/dev/null 2>&1 || true
}

# Always BEFORE the cluster is deleted: afterwards there is nothing left to ask.
dump_diagnostics() {
	[ -n "${KUBECONFIG:-}" ] && [ -f "$KUBECONFIG" ] || return 0
	echo
	echo "---- diagnostics ----"
	$KUBECTL get pods -A 2>/dev/null | head -20
	$KUBECTL get pvc 2>/dev/null | head -10
	$KUBECTL describe pod -l app.kubernetes.io/name=metaloom-graph 2>/dev/null | tail -40
	$KUBECTL logs -l app.kubernetes.io/name=metaloom-graph --tail=60 2>/dev/null
	echo "---- end diagnostics ----"
}
