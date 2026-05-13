#!/usr/bin/env bash
# run_peak.sh — Tier 2: Peak load profile (100 users, 5 min)
# ------------------------------------------------------------
# PURPOSE:
#   Simulate a realistic academic morning rush — 100 concurrent
#   users logging in, scanning QR codes, submitting surveys, and
#   the admin dashboard auto-refreshing.  All NFR thresholds must
#   be met at this tier for the system to be considered production-ready.
#
# METRICS TARGET:
#   gateway /validate   : p95 < 100 ms,  error_rate < 0.1 %
#   auth /login         : p95 < 500 ms,  error_rate < 1 %
#   form /surveys       : p95 < 500 ms,  error_rate < 1 %
#   promotion /stats    : p95 < 300 ms,  error_rate < 0.5 %
#   promotion /confirmed: p95 < 1000 ms, error_rate < 1 %
#
# USAGE:
#   chmod +x run_peak.sh
#   ./run_peak.sh
# ------------------------------------------------------------

set -euo pipefail

RESULTS_DIR="./results/peak"
mkdir -p "$RESULTS_DIR"

echo "=============================================="
echo " CircleGuard — Tier 2: Peak load (100 users)"
echo "=============================================="

locust \
  --config locust_config.ini \
  --users        100 \
  --spawn-rate    10 \
  --run-time      5m \
  --html         "${RESULTS_DIR}/report.html" \
  --csv          "${RESULTS_DIR}/stats" \
  --host         "${AUTH_HOST:-http://localhost:8180}"

EXIT_CODE=$?

echo ""
if [ "$EXIT_CODE" -eq 0 ]; then
  echo "✅  Peak load test PASSED — all NFR thresholds met."
else
  echo "❌  Peak load test FAILED — one or more NFR thresholds breached."
  echo "    See ${RESULTS_DIR}/report.html for details."
fi

echo "    Report: ${RESULTS_DIR}/report.html"
echo "    CSV stats prefix: ${RESULTS_DIR}/stats"

exit "$EXIT_CODE"
