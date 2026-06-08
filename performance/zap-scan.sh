#!/usr/bin/env bash
set -euo pipefail

ZAP_TARGET="${ZAP_TARGET:-http://host.docker.internal:30087}"
ZAP_IMAGE="${ZAP_IMAGE:-owasp/zap2docker-stable}"
REPORT_DIR="${REPORT_DIR:-zap-reports}"
REPORT_FILE="${REPORT_FILE:-zap-report.html}"

mkdir -p "${REPORT_DIR}"

docker run --rm \
  -v "$(pwd)/${REPORT_DIR}:/zap/wrk:rw" \
  "${ZAP_IMAGE}" \
  zap-baseline.py \
    -t "${ZAP_TARGET}" \
    -r "${REPORT_FILE}" \
    -I
