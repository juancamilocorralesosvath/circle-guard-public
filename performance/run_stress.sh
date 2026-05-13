#!/usr/bin/env bash
# run_stress.sh — Tier 3: Stress / beyond-peak (200 users, 5 min)
# -----------------------------------------------------------------
# PURPOSE:
#   Push the system well beyond its expected peak to discover:
#     1. Which component saturates first (Redis, Neo4j, Postgres pool, BCrypt).
#     2. At what user count the system starts breaching NFR thresholds.
#     3. Whether the system degrades gracefully (increasing latency)
#        or catastrophically (rising error rate / 5xx responses).
#
# EXPECTED OUTCOMES:
#   - gate /validate may exceed 100 ms p95 (Redis pool saturation signal).
#   - auth /login will exceed 500 ms p95 (BCrypt is intentionally slow).
#   - promotion /confirmed may exceed 1000 ms p95 (NFR-1 breach — signals
#     need for Neo4j horizontal scaling or query optimisation).
#   - Error rate should remain < 5 % (circuit breakers / connection refusals
#     are expected, but full 5xx cascades should NOT occur).
#
# USAGE:
#   chmod +x run_stress.sh
#   ./run_stress.sh
# -----------------------------------------------------------------

set -euo pipefail

RESULTS_DIR="./results/stress"
mkdir -p "$RESULTS_DIR"

echo "==============================================="
echo " CircleGuard — Tier 3: Stress test (200 users)"
echo "==============================================="
echo " ⚠  NFR breaches are EXPECTED at this load."
echo " ⚠  The goal is to identify saturation points."
echo ""

locust \
  --config locust_config.ini \
  --users        200 \
  --spawn-rate    20 \
  --run-time      5m \
  --html         "${RESULTS_DIR}/report.html" \
  --csv          "${RESULTS_DIR}/stats" \
  --host         "${AUTH_HOST:-http://localhost:8180}" \
  --exit-code-on-error 0    # Do not fail CI on NFR breach — this is exploratory

echo ""
echo "ℹ️   Stress test complete."
echo "    Analyse ${RESULTS_DIR}/report.html to identify saturation points."
echo "    Compare p95 values against NFR thresholds defined in locustfile.py."
echo "    CSV stats prefix: ${RESULTS_DIR}/stats"
