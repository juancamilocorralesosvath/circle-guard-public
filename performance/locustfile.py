"""
CircleGuard — Locust Performance & Stress Test Suite
=====================================================

PURPOSE
-------
Simulate realistic mixed traffic across the five critical CircleGuard
microservices under three load profiles:

  Tier 1 — Warm-up   :  10 users, ramp 2 u/s  →  2 minutes
  Tier 2 — Peak load : 100 users, ramp 10 u/s  →  5 minutes
  Tier 3 — Stress    : 200 users, ramp 20 u/s  →  5 minutes

RUNNING LOCALLY (headless, all tiers sequentially)
---------------------------------------------------
  pip install locust
  locust -f locustfile.py \
         --headless \
         -u 200 -r 20 \
         --run-time 12m \
         --host http://localhost:8180 \
         --html report.html

RUNNING IN KUBERNETES (staging namespace)
-----------------------------------------
  kubectl apply -f k8s/jobs/locust-perf-test-job.yaml -n circleguard-staging
  kubectl wait --for=condition=complete job/locust-perf-test \
               -n circleguard-staging --timeout=900s
  kubectl cp circleguard-staging/<pod-name>:/results/report.html ./report.html

ENVIRONMENT VARIABLES (override defaults)
-----------------------------------------
  AUTH_HOST     = http://circleguard-auth-service:8180
  FORM_HOST     = http://circleguard-form-service:8086
  GATEWAY_HOST  = http://circleguard-gateway-service:8087
  PROMOTION_HOST= http://circleguard-promotion-service:8088
  TEST_USER     = staff_guard
  TEST_PASSWORD = password
  HC_USER       = health_user
  HC_PASSWORD   = password

NFR THRESHOLDS (from AGENTS.md / README.md)
-------------------------------------------
  Gate validate       : p95 < 100 ms,  error_rate < 0.1 %
  Login               : p95 < 500 ms,  error_rate < 1 %
  Survey submission   : p95 < 500 ms,  error_rate < 1 %
  Health stats        : p95 < 300 ms,  error_rate < 0.5 %
  Status promotion    : p95 < 1 000 ms (NFR-1), error_rate < 1 %

ANALYSIS — EXPECTED BEHAVIOUR UNDER LOAD
-----------------------------------------
Tier 1 (10 users / 2 min):
  All endpoints stay well within their p95 budgets.  The Neo4j traversal in
  the promotion endpoint is the most expensive operation (~200–400 ms on a
  10-node graph) but still within the 1 s NFR.  Redis and JWT operations add
  <5 ms.  No error expected.

Tier 2 (100 users / 5 min — realistic peak-hour traffic):
  Gate validate remains fast (<50 ms p95) because it is a pure Redis read + JWT
  verify.  Login p95 may approach 300–400 ms due to BCrypt password hashing
  (CPU-bound).  Survey submission is I/O-bound (Postgres write + Kafka produce)
  and should stay under 300 ms.  Status promotion triggers a Neo4j 2-hop
  traversal; with caching (Caffeine) warmed up, p95 should be <600 ms.
  Expect < 0.5 % error rate across all endpoints.

Tier 3 (200 users / 5 min — stress / beyond-peak):
  Gate validate may exceed 100 ms p95 if the single Redis instance becomes a
  bottleneck; this signals a need for Redis cluster or read replicas.
  Login p95 will likely exceed 500 ms because BCrypt is intentionally slow;
  adding a JWT session cache or reducing BCrypt cost factor are mitigations.
  Status promotion p95 may exceed 1 000 ms (NFR-1 breach) for large contact
  graphs; the automated partitioning query already mitigates this, but a
  dedicated Neo4j read replica would further improve throughput.
  Error rate should remain < 2 % — higher rates indicate connection pool
  exhaustion (increase spring.datasource.hikari.maximum-pool-size).

KEY METRICS TO CAPTURE
-----------------------
  - Requests per second (RPS) at each tier
  - p50, p95, p99 response times per endpoint
  - Error rate per endpoint
  - Number of failed requests
  - Neo4j / Redis connection pool saturation (via /actuator/metrics)
"""

import os
import json
import random
import time
from locust import HttpUser, task, between, events
from locust.runners import MasterRunner

# ─── Configuration ────────────────────────────────────────────────────────────

AUTH_HOST      = os.getenv("AUTH_HOST",      "http://localhost:8180")
FORM_HOST      = os.getenv("FORM_HOST",      "http://localhost:8086")
GATEWAY_HOST   = os.getenv("GATEWAY_HOST",   "http://localhost:8087")
PROMOTION_HOST = os.getenv("PROMOTION_HOST", "http://localhost:8088")

TEST_USER     = os.getenv("TEST_USER",     "staff_guard")
TEST_PASSWORD = os.getenv("TEST_PASSWORD", "password")
HC_USER       = os.getenv("HC_USER",       "health_user")
HC_PASSWORD   = os.getenv("HC_PASSWORD",   "password")

# ─── Shared state (populated once at startup by the first successful login) ───

_shared_jwt:      str | None = None
_shared_qr_token: str | None = None
_health_jwt:      str | None = None

# Pool of 100 pre-generated anonymousIds to spread Redis reads across keys
_anon_id_pool: list[str] = [
    f"perf-test-user-{i:04d}-anon-id-placeholder" for i in range(100)
]


# ─── Utility helpers ──────────────────────────────────────────────────────────

def _do_login(client, username: str, password: str) -> dict | None:
    """Login and return parsed JSON body, or None on failure."""
    with client.post(
        f"{AUTH_HOST}/api/v1/auth/login",
        json={"username": username, "password": password},
        catch_response=True,
        name="auth: POST /login",
    ) as resp:
        if resp.status_code == 200:
            return resp.json()
        resp.failure(f"Login failed: {resp.status_code} {resp.text[:120]}")
        return None


def _do_qr_generate(client, jwt: str) -> str | None:
    """Generate a QR token and return the raw token string, or None on failure."""
    with client.get(
        f"{AUTH_HOST}/api/v1/auth/qr/generate",
        headers={"Authorization": f"Bearer {jwt}"},
        catch_response=True,
        name="auth: GET /qr/generate",
    ) as resp:
        if resp.status_code == 200:
            return resp.json().get("qrToken")
        resp.failure(f"QR generate failed: {resp.status_code}")
        return None


# ─── User Classes ─────────────────────────────────────────────────────────────

class GateValidatorUser(HttpUser):
    """
    Simulates gate-scanner hardware validating QR codes at campus entrances.

    This is the HIGHEST FREQUENCY operation: every turnstile, every classroom
    door, every building entry.  NFR: p95 < 100 ms.

    Task weight: 10 — gate scanners far outnumber any other client type.
    """

    host        = GATEWAY_HOST
    wait_time   = between(0.1, 0.5)   # scanners fire rapidly

    def on_start(self):
        """Obtain a valid QR token once, then reuse until rotation needed."""
        self._refresh_token()

    def _refresh_token(self):
        """Obtain a fresh JWT and derive a QR token from it."""
        global _shared_jwt, _shared_qr_token

        body = _do_login(self.client, TEST_USER, TEST_PASSWORD)
        if body:
            _shared_jwt = body.get("token")
            _shared_qr_token = _do_qr_generate(self.client, _shared_jwt)

    @task(10)
    def validate_healthy_user(self):
        """
        Scenario: healthy student scans QR at building entrance.
        Expected: GREEN response in < 100 ms (Redis read + JWT verify).
        """
        token = _shared_qr_token
        if not token:
            self._refresh_token()
            return

        with self.client.post(
            "/api/v1/gate/validate",
            json={"token": token},
            catch_response=True,
            name="gateway: POST /gate/validate (healthy)",
        ) as resp:
            if resp.status_code != 200:
                resp.failure(f"Unexpected status: {resp.status_code}")
                return
            body = resp.json()
            # Refresh token if expired (RED + "Invalid or Expired")
            if body.get("status") == "RED" and "Expired" in body.get("message", ""):
                self._refresh_token()

    @task(2)
    def validate_with_expired_token(self):
        """
        Scenario: token rotation boundary — deliberately send a stale token.
        Expected: RED response (still 200, not 500).
        """
        with self.client.post(
            "/api/v1/gate/validate",
            json={"token": "this.is.not.a.valid.token"},
            catch_response=True,
            name="gateway: POST /gate/validate (invalid token)",
        ) as resp:
            if resp.status_code != 200:
                resp.failure(f"Gate must always return 200; got {resp.status_code}")
                return
            if resp.json().get("status") != "RED":
                resp.failure("Invalid token should return RED")


class AuthUser(HttpUser):
    """
    Simulates students and staff logging in at the start of the academic day.

    Login is BCrypt-heavy (CPU-bound); the main bottleneck at scale.
    NFR: p95 < 500 ms.

    Task weight: 3 — login is less frequent than gate scans.
    """

    host      = AUTH_HOST
    wait_time = between(1, 3)

    @task(3)
    def login(self):
        """
        Scenario: student opens the CircleGuard app in the morning.
        Expected: 200 with JWT + anonymousId within 500 ms.
        """
        _do_login(self.client, TEST_USER, TEST_PASSWORD)

    @task(2)
    def generate_qr(self):
        """
        Scenario: student refreshes QR code every 60 s (app timer fires).
        Expected: 200 with new qrToken within 200 ms.
        """
        global _shared_jwt
        jwt = _shared_jwt
        if not jwt:
            body = _do_login(self.client, TEST_USER, TEST_PASSWORD)
            if body:
                _shared_jwt = body.get("token")
                jwt = _shared_jwt

        if jwt:
            _do_qr_generate(self.client, jwt)

    @task(1)
    def login_wrong_password(self):
        """
        Scenario: mistyped password (fat-finger on mobile keyboard).
        Expected: 401 — must not cause a 500.
        """
        with self.client.post(
            "/api/v1/auth/login",
            json={"username": TEST_USER, "password": "WRONG_PASSWORD"},
            catch_response=True,
            name="auth: POST /login (wrong password)",
        ) as resp:
            if resp.status_code not in (401, 403):
                resp.failure(f"Wrong password must return 401/403; got {resp.status_code}")


class SurveySubmitterUser(HttpUser):
    """
    Simulates students submitting daily health self-reports.

    Typically a morning rush pattern; moderate frequency.
    NFR: p95 < 500 ms (Postgres write + Kafka produce).

    Task weight: 2.
    """

    host      = FORM_HOST
    wait_time = between(2, 5)

    def on_start(self):
        """Fetch the active questionnaire once per user lifecycle."""
        self._questionnaire = None
        self._fetch_questionnaire()

    def _fetch_questionnaire(self):
        with self.client.get(
            "/api/v1/questionnaires/active",
            catch_response=True,
            name="form: GET /questionnaires/active",
        ) as resp:
            if resp.status_code == 200:
                self._questionnaire = resp.json()
            elif resp.status_code == 404:
                # No active questionnaire — fall back to legacy boolean fields
                self._questionnaire = None

    @task(2)
    def submit_asymptomatic_survey(self):
        """
        Scenario: healthy student answers all questions NO.
        Expected: 200 OK, no Kafka cascade triggered.
        """
        anon_id = random.choice(_anon_id_pool)
        payload: dict = {
            "anonymousId": anon_id,
            "timestamp":   time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }

        if self._questionnaire and self._questionnaire.get("questions"):
            responses: dict[str, str] = {}
            for q in self._questionnaire["questions"]:
                qtype = q.get("type", "YES_NO")
                if qtype == "YES_NO":
                    responses[q["id"]] = "NO"
                elif qtype in ("SINGLE_CHOICE", "MULTI_CHOICE"):
                    responses[q["id"]] = "[]"
                else:
                    responses[q["id"]] = "All clear."
            payload["responses"] = responses
        else:
            payload["hasFever"] = False
            payload["hasCough"] = False

        with self.client.post(
            "/api/v1/surveys",
            json=payload,
            catch_response=True,
            name="form: POST /surveys (asymptomatic)",
        ) as resp:
            if resp.status_code not in (200, 201):
                resp.failure(f"Survey submission failed: {resp.status_code} {resp.text[:80]}")

    @task(1)
    def fetch_active_questionnaire(self):
        """
        Scenario: app fetches questionnaire on launch (cache miss on first open).
        Expected: 200 with JSON body within 200 ms.
        """
        self._fetch_questionnaire()


class HealthStatsDashboardUser(HttpUser):
    """
    Simulates the admin analytics dashboard auto-refreshing every 30 s.

    The stats endpoint is backed by a Neo4j aggregation query with Caffeine
    L1 cache (5 min TTL).  After the first request warms the cache, p95 should
    drop to < 20 ms (cache hit).  Cold-cache p95: < 300 ms.

    Task weight: 1.
    """

    host      = PROMOTION_HOST
    wait_time = between(25, 35)   # realistic dashboard refresh cadence

    @task(3)
    def get_campus_stats(self):
        """
        Scenario: dashboard polling overall campus health summary.
        Expected: 200 JSON within 300 ms (warm cache), 800 ms (cold cache).
        """
        with self.client.get(
            "/api/v1/health-status/stats",
            catch_response=True,
            name="promotion: GET /health-status/stats",
        ) as resp:
            if resp.status_code != 200:
                resp.failure(f"Stats failed: {resp.status_code}")
                return
            body = resp.json()
            if "totalUsers" not in body:
                resp.failure("Stats response missing totalUsers field")

    @task(1)
    def get_department_stats(self):
        """
        Scenario: admin drills into a specific faculty.
        Expected: 200 JSON within 300 ms; small faculties masked by K-Anonymity.
        """
        departments = [
            "Faculty of Health Sciences (Ciencias de la Salud)",
            "Faculty of Engineering, Design and Applied Sciences (Barberi de Ingeniería, Diseño y Ciencias Aplicadas)",
            "Faculty of Natural Sciences (Ciencias Naturales)",
        ]
        dept = random.choice(departments)
        with self.client.get(
            f"/api/v1/health-status/stats/department/{dept}",
            catch_response=True,
            name="promotion: GET /health-status/stats/department",
        ) as resp:
            if resp.status_code not in (200, 403):
                resp.failure(f"Dept stats unexpected status: {resp.status_code}")


class StatusPromotionUser(HttpUser):
    """
    Simulates Health Center staff registering confirmed positive lab results.

    This is the RAREST but most expensive operation: it triggers a multi-hop
    Neo4j traversal and Redis batch update.  NFR-1: p95 < 1 000 ms.

    Task weight: 1 — only health center staff perform this action.
    """

    host      = PROMOTION_HOST
    wait_time = between(10, 30)   # health center staff work at human pace

    def on_start(self):
        """Authenticate as a Health Center user once."""
        global _health_jwt
        if not _health_jwt:
            with self.client.post(
                f"{AUTH_HOST}/api/v1/auth/login",
                json={"username": HC_USER, "password": HC_PASSWORD},
                catch_response=True,
                name="auth: POST /login (health_user)",
            ) as resp:
                if resp.status_code == 200:
                    _health_jwt = resp.json().get("token")
                else:
                    resp.failure(f"Health user login failed: {resp.status_code}")

    @task(1)
    def confirm_positive_case(self):
        """
        Scenario: health center staff enters a confirmed PCR result.
        Expected: 200 within 1 000 ms (NFR-1). Triggers Neo4j 2-hop cascade.
        """
        jwt = _health_jwt
        if not jwt:
            return

        anon_id = random.choice(_anon_id_pool)

        with self.client.post(
            "/api/v1/health/confirmed",
            headers={"Authorization": f"Bearer {jwt}"},
            json={"anonymousId": anon_id},
            catch_response=True,
            name="promotion: POST /health/confirmed",
        ) as resp:
            if resp.status_code == 403:
                # health_user doesn't have HEALTH_CENTER role in this env
                resp.failure("403 — health_user lacks HEALTH_CENTER role")
            elif resp.status_code != 200:
                resp.failure(f"Confirm positive failed: {resp.status_code} {resp.text[:80]}")

    @task(1)
    def get_mesh_stats(self):
        """
        Scenario: health center staff reviews a user's contact network size.
        Expected: 200 with confirmedCount + unconfirmedCount fields.
        """
        anon_id = random.choice(_anon_id_pool)
        with self.client.get(
            f"/api/v1/mesh/stats/{anon_id}",
            catch_response=True,
            name="promotion: GET /mesh/stats/{id}",
        ) as resp:
            if resp.status_code not in (200, 404):
                resp.failure(f"Mesh stats unexpected: {resp.status_code}")


# ─── Custom event hooks (optional CI threshold enforcement) ───────────────────

@events.quitting.add_listener
def assert_nfr_thresholds(environment, **_kwargs):
    """
    Fail the Locust run if any key NFR threshold is breached.
    This hook is invoked when the run ends, making it usable in CI pipelines:
      if locust exits with a non-zero code, the Jenkins stage fails.
    """
    stats = environment.stats

    violations: list[str] = []

    checks: list[tuple[str, str, float, str]] = [
        # (name_substring,         metric,  threshold, unit)
        ("gate/validate",          "p95",   100,  "ms — gate NFR"),
        ("POST /login",            "p95",   500,  "ms — login NFR"),
        ("POST /surveys",          "p95",   500,  "ms — survey NFR"),
        ("health-status/stats",    "p95",   300,  "ms — stats NFR"),
        ("health/confirmed",       "p95", 1_000,  "ms — NFR-1"),
    ]

    for entry in stats.entries.values():
        name = entry.name
        for (substring, metric, threshold, label) in checks:
            if substring in name:
                actual = getattr(entry, f"get_response_time_percentile")(0.95) \
                    if hasattr(entry, "get_response_time_percentile") \
                    else entry.response_times.get(95, 0)
                if actual and actual > threshold:
                    violations.append(
                        f"  NFR BREACH: [{name}] p95={actual:.0f} ms > {threshold} ms ({label})"
                    )

    if violations:
        print("\n⚠️  PERFORMANCE NFR VIOLATIONS DETECTED:")
        for v in violations:
            print(v)
        environment.process_exit_code = 1
    else:
        print("\n✅  All NFR thresholds met.")
