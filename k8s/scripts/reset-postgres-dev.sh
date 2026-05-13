#!/usr/bin/env bash
# Destructive dev reset: removes Postgres StatefulSet + data PVC, then reapplies
# the dev overlay so the official postgres image runs init again using
# circleguard-db-credentials and postgres-init-config (init-db.sql).
#
# Usage:
#   ./k8s/scripts/reset-postgres-dev.sh [namespace]
# Default namespace: circleguard-dev
#
# Prerequisites: kubectl configured; namespace exists; overlay path from repo root.

set -euo pipefail

NS="${1:-circleguard-dev}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OVERLAY="${REPO_ROOT}/k8s/overlays/dev"
PVC_NAME="pgdata-postgres-0"

echo "==> Namespace: ${NS}"
echo "==> WARNING: This deletes Postgres data PVC '${PVC_NAME}' and StatefulSet 'postgres'."
read -r -p "Type YES to continue: " confirm
if [[ "${confirm}" != "YES" ]]; then
  echo "Aborted."
  exit 1
fi

echo "==> Deleting StatefulSet postgres (pods terminate)..."
kubectl delete statefulset postgres -n "${NS}" --wait=true --ignore-not-found=true

echo "==> Deleting PVC ${PVC_NAME} (all DB data in this volume)..."
kubectl delete pvc "${PVC_NAME}" -n "${NS}" --wait=true --ignore-not-found=true

echo "==> Re-applying k8s/overlays/dev (Postgres + secrets + configmaps + services)..."
kubectl apply -k "${OVERLAY}"

echo "==> Waiting for postgres-0 pod Ready..."
kubectl rollout status statefulset/postgres -n "${NS}" --timeout=300s

echo "==> Done. Postgres should have re-initialized with DB_USERNAME/DB_PASSWORD from circleguard-db-credentials."
echo "    For bootRun on your laptop against this cluster, forward a free host port (15432 matches Spring defaults):"
echo "    kubectl port-forward -n ${NS} svc/postgres-service 15432:5432"
