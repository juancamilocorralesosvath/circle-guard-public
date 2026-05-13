#!/usr/bin/env bash
# run_warmup.sh — Tier 1: Warm-up profile (10 users, 2 min)
# -----------------------------------------------------------
# PURPOSE:
#   Verify basic connectivity and confirm that every endpoint
#   responds correctly under minimal load before escalating.
#   No NFR threshold violations are expected at this tier.
#
# METRICS TARGET:
#   All p95 < 50 % of each NFR threshold
#   Error rate: 0 %
#
# USAGE:
#   chmod +x run_warmup.sh
#   ./run_warmup.sh
# -----------------------------------------------------------

set -euo pipefail

RESULTS_DIR="./results/warmup"
mkdir -p "$RESULTS_DIR"

echo "============================================="
echo " CircleGuard — Tier 1: Warm-up (10 users)"
echo "============================================="

locust \
  --config locust_config.ini \
  --users       10 \
  --spawn-rate   2 \
  --run-time    2m \
  --html        "${RESULTS_DIR}/report.html" \
  --csv         "${RESULTS_DIR}/stats" \
  --host        "${AUTH_HOST:-http://localhost:8180}"

echo ""
echo "✅  Warm-up complete. Report: ${RESULTS_DIR}/report.html"
echo "    CSV stats prefix: ${RESULTS_DIR}/stats"
