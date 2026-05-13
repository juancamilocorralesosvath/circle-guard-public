# CircleGuard — Performance & Stress Test Analysis
## Locust Load-Testing Report Template

---

## 1. Test Scope & Objectives

This document describes the **performance and stress testing strategy** for the
CircleGuard contact-tracing platform, the scenarios executed, and the interpretation
framework for Locust output metrics.

### Services under test

| Service | Port | Role |
|---|---|---|
| `circleguard-auth-service` | 8180 | Login + QR token generation |
| `circleguard-form-service` | 8086 | Health survey submission |
| `circleguard-gateway-service` | 8087 | QR-code gate validation |
| `circleguard-promotion-service` | 8088 | Status cascade + health stats |

---

## 2. Load Profiles

| Tier | Users | Spawn rate | Duration | Goal |
|---|---|---|---|---|
| **Tier 1 – Warm-up** | 10 | 2 u/s | 2 min | Verify connectivity, no errors |
| **Tier 2 – Peak load** | 100 | 10 u/s | 5 min | All NFRs must pass |
| **Tier 3 – Stress** | 200 | 20 u/s | 5 min | Find saturation point |

### User mix (task weights)

```
GateValidatorUser     weight 10  →  ~55 % of total requests
AuthUser              weight  3  →  ~17 % of total requests
SurveySubmitterUser   weight  2  →  ~11 % of total requests
HealthStatsDashboard  weight  1  →  ~  6 % of total requests
StatusPromotionUser   weight  1  →  ~  6 % of total requests
```

This mix reflects real-world campus traffic: many gate scans per login, occasional
health stats refreshes, and rare (but expensive) confirmed-positive registrations.

---

## 3. NFR Thresholds

| Endpoint | p95 target | Error-rate target | Rationale |
|---|---|---|---|
| `POST /gate/validate` | < 100 ms | < 0.1 % | Every turnstile fires this; latency is UX-critical |
| `POST /auth/login` | < 500 ms | < 1 % | App launch; BCrypt is intentionally slow |
| `GET /auth/qr/generate` | < 200 ms | < 0.5 % | Fired every 60 s per user |
| `POST /surveys` | < 500 ms | < 1 % | Postgres write + Kafka produce |
| `GET /health-status/stats` | < 300 ms | < 0.5 % | Caffeine-cached after first hit |
| `POST /health/confirmed` | < 1 000 ms | < 1 % | NFR-1 from AGENTS.md |

---

## 4. Scenario Descriptions

### Scenario A — Gate Validator (weight 10)
**Real-world analogue:** Building turnstile or classroom door scanner.

Every entry event triggers `POST /gateway/api/v1/gate/validate` with a signed
JWT QR token.  The service performs:
1. JWT signature verification (in-memory, ~1 ms).
2. Redis `GET user:status:{anonymousId}` (~1–3 ms local, ~5–15 ms cross-pod).
3. Returns `GREEN` or `RED` JSON.

**Bottleneck:** Redis connection pool.  At 200 concurrent users, if the pool
(`spring.data.redis.lettuce.pool.max-active`) is set to the default of 8, threads
queue up and p95 spikes.  Recommended: set `max-active=50` for staging.

---

### Scenario B — Auth User (weight 3)
**Real-world analogue:** Student opens CircleGuard app on their phone.

Login calls `POST /auth/api/v1/auth/login`, which chains:
1. LDAP bind attempt (fast if LDAP is healthy, ~20 ms; falls back on failure).
2. BCrypt password verify (~80–200 ms on a modern CPU at cost factor 12).
3. Synchronous HTTP call to identity-service `POST /identities/map` (~10–30 ms).
4. JWT signing (~1 ms).

**Bottleneck:** BCrypt is CPU-bound.  Under 100 concurrent logins, the auth-service
CPU limit (500 m) will be approached.  At 200 users, p95 will likely exceed 500 ms.
**Mitigation:** JWT-session caching (once logged in, skip re-auth for 1 h) or
scaling auth-service replicas to 2.

---

### Scenario C — Survey Submitter (weight 2)
**Real-world analogue:** Morning health check — students fill in the daily form.

`POST /form/api/v1/surveys` performs:
1. Postgres INSERT into `health_surveys` (~5–15 ms).
2. SymptomMapper evaluation (in-memory, < 1 ms).
3. `KafkaTemplate.send("survey.submitted", ...)` (async, < 5 ms producer call).

**Bottleneck:** HikariCP connection pool to Postgres.  Default `maximum-pool-size=10`
may queue under burst traffic.  Recommended: `maximum-pool-size=25` for staging.

---

### Scenario D — Health Stats Dashboard (weight 1)
**Real-world analogue:** Health Center admin dashboard auto-refresh every 30 s.

`GET /promotion/api/v1/health-status/stats` performs:
1. Caffeine cache lookup (< 1 ms on hit, 5-min TTL).
2. On cache miss: Neo4j `MATCH (u:User) RETURN u.status, count(u)` (~50–200 ms).

**Bottleneck:** Neo4j connection pool on cache-miss burst (e.g., after a deployment
that resets the JVM and clears Caffeine).  Under stress, if all 200 users hit the
endpoint within the same 5-min TTL window simultaneously (e.g., cold start), Neo4j
will receive 200 concurrent aggregation queries and latency will spike to 1–3 s.
**Mitigation:** Pre-warm cache on startup with a `@PostConstruct` or add a Redis L2
cache (TTL 60 s) in front of the Neo4j query.

---

### Scenario E — Status Promotion (weight 1)
**Real-world analogue:** Health Center staff registers a confirmed PCR result.

`POST /promotion/api/v1/health/confirmed` triggers the most expensive operation:
1. Neo4j 2-hop Cypher traversal (update source + L1 SUSPECT + L2 PROBABLE).
2. Redis batch `MSET` of all affected user status keys.
3. Kafka produce to `promotion.status.changed` and `alert.priority`.
4. Optional: Neo4j `circle.fenced` detection query.

On a 10,000-node graph, the existing `PromotionPerformanceTest` shows this
completes in < 1,000 ms (NFR-1).  Under concurrent load (10 simultaneous confirmations),
Neo4j write locks may cause queue build-up.

**Mitigation:**
- Neo4j write transactions should use `APOC` batching for large contact networks.
- Separate the status-cascade Cypher from the circle-detection query into two
  async steps (the circle detection can be eventual).

---

## 5. Expected Results by Tier

### Tier 1 — Warm-up (10 users, 2 min)

| Endpoint | Expected p50 | Expected p95 | Expected errors |
|---|---|---|---|
| POST /gate/validate | 5–15 ms | 20–40 ms | 0 % |
| POST /auth/login | 80–150 ms | 200–300 ms | 0 % |
| POST /surveys | 20–50 ms | 80–150 ms | 0 % |
| GET /health-status/stats | 10–50 ms | 50–150 ms | 0 % |
| POST /health/confirmed | 150–400 ms | 400–700 ms | 0 % |

All endpoints well within NFR thresholds. Zero errors expected.

---

### Tier 2 — Peak Load (100 users, 5 min)

| Endpoint | Expected p50 | Expected p95 | Expected errors |
|---|---|---|---|
| POST /gate/validate | 10–30 ms | 50–80 ms ✅ | < 0.1 % ✅ |
| POST /auth/login | 150–250 ms | 350–480 ms ✅ | < 0.5 % ✅ |
| POST /surveys | 30–80 ms | 150–350 ms ✅ | < 0.5 % ✅ |
| GET /health-status/stats | 5–20 ms (cached) | 20–80 ms ✅ | < 0.2 % ✅ |
| POST /health/confirmed | 300–600 ms | 700–950 ms ✅ | < 0.5 % ✅ |

All NFR thresholds expected to be **met**.  If `POST /health/confirmed` approaches
or exceeds 1,000 ms p95, the first investigation should be Neo4j query explain plan
and connection pool sizing.

---

### Tier 3 — Stress (200 users, 5 min)

| Endpoint | Expected p50 | Expected p95 | NFR breach? |
|---|---|---|---|
| POST /gate/validate | 20–60 ms | 90–180 ms | ⚠️ Possible (> 100 ms) |
| POST /auth/login | 250–500 ms | 600–900 ms | ❌ Breached (> 500 ms) |
| POST /surveys | 50–150 ms | 300–600 ms | ⚠️ Possible (> 500 ms) |
| GET /health-status/stats | 5–20 ms (cached) | 30–120 ms | ✅ Within threshold |
| POST /health/confirmed | 500–900 ms | 1 000–2 000 ms | ❌ Breached (> 1 000 ms) |

**Interpretation of breaches at Tier 3:**

- `POST /auth/login` exceeding 500 ms is **expected** and by design; BCrypt cost
  factor 12 requires ~80–200 ms per hash regardless of concurrency.  The solution
  is horizontal scaling (2–3 replicas) rather than reducing the cost factor.

- `POST /gate/validate` approaching 100 ms indicates Redis connection pool
  saturation.  Increase `spring.data.redis.lettuce.pool.max-active` to 50.

- `POST /health/confirmed` exceeding 1,000 ms at this scale is a Neo4j write
  throughput issue.  The system is designed for occasional confirmations, not
  concurrent bulk confirmations.  Mitigation: async Neo4j write queue.

---

## 6. Key Metrics Checklist for CI

After each Locust run, validate the following from the CSV output:

```bash
# Extract p95 for gate/validate
awk -F',' '$2 ~ /gate.validate/ { print "gate/validate p95:", $14, "ms" }' \
  results/peak/stats_stats.csv

# Check overall failure count
awk -F',' 'NR>1 { failures += $8 } END { print "Total failures:", failures }' \
  results/peak/stats_stats.csv

# Check requests per second at peak
awk -F',' '$2 == "Aggregated" { print "Peak RPS:", $9 }' \
  results/peak/stats_stats.csv
```

**CI gate (Jenkins `post` step):** The `assert_nfr_thresholds` Locust event hook
sets `exit_code=1` when any NFR threshold is breached during the **peak** run.
The Jenkins pipeline stage should be configured as:

```groovy
stage('Performance Tests') {
    steps {
        sh './performance/run_peak.sh'
    }
    post {
        always {
            publishHTML([
                reportDir:   'performance/results/peak',
                reportFiles: 'report.html',
                reportName:  'Locust Peak Performance Report'
            ])
            archiveArtifacts artifacts: 'performance/results/peak/stats*.csv'
        }
        failure {
            echo 'ALERT: Peak-load NFR threshold breached — investigate before merging.'
        }
    }
}
```

---

## 7. Infrastructure Tuning Recommendations

Based on the expected stress-tier results, the following tuning changes are
recommended before the system handles real production traffic:

| Component | Parameter | Current default | Recommended |
|---|---|---|---|
| auth-service | BCrypt cost factor | 12 | 12 (keep; add horizontal scaling) |
| auth-service | Replicas (staging/prod) | 1 / 1 | 2 / 3 |
| gateway-service | Redis pool max-active | 8 | 50 |
| gateway-service | Replicas (staging/prod) | 2 / 3 | 3 / 5 |
| promotion-service | HikariCP max-pool-size | 10 | 25 |
| promotion-service | Neo4j connection pool | 50 | 100 |
| form-service | HikariCP max-pool-size | 10 | 25 |
| Kafka | Partition count (survey.submitted) | 1 | 3 |
| Redis | max-active connections | 8 | 50 |

---

## 8. Running the Full Suite in Jenkins

```groovy
// Add to Jenkinsfile after the 'Deploy to Staging' stage:
stage('Performance & Stress Tests') {
    parallel {
        stage('Warm-up') {
            steps {
                dir('performance') {
                    sh 'pip install locust --quiet'
                    sh 'chmod +x run_warmup.sh && ./run_warmup.sh'
                }
            }
        }
    }
    post { always { archiveArtifacts 'performance/results/warmup/**' } }
}

stage('Peak Load Test') {
    steps {
        dir('performance') {
            sh 'chmod +x run_peak.sh && ./run_peak.sh'
        }
    }
    post {
        always {
            publishHTML([
                reportDir: 'performance/results/peak',
                reportFiles: 'report.html',
                reportName: 'Peak Load Report'
            ])
        }
    }
}

stage('Stress Test') {
    when { branch 'staging' }
    steps {
        dir('performance') {
            sh 'chmod +x run_stress.sh && ./run_stress.sh'
        }
    }
    post {
        always {
            publishHTML([
                reportDir: 'performance/results/stress',
                reportFiles: 'report.html',
                reportName: 'Stress Test Report'
            ])
        }
    }
}
```
