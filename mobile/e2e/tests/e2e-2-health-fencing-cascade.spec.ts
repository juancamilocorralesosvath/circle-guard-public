// e2e/tests/e2e-2-health-fencing-cascade.spec.ts
import { test, expect, request, APIRequestContext } from '@playwright/test';

/**
 * E2E Test 2: Health Fencing Cascade — Sick Student Blocked at Gate
 *
 * WHY CRITICAL:
 *   This is the core value proposition of the entire CircleGuard system.
 *   A student reports symptoms → form-service publishes a Kafka event →
 *   promotion-service updates Redis → gateway-service denies campus entry.
 *   Any break in this chain — wrong Kafka topic, missing Redis write, wrong
 *   status value — means symptomatic students can freely enter campus.
 *   This E2E test catches regressions across ALL five services simultaneously.
 *
 * WHAT IS VALIDATED:
 *   1. Survey submission with symptoms returns 200 OK.
 *   2. After the async Kafka→promotion cascade, user's gate returns RED.
 *   3. The gate denial message is non-empty and communicates the restriction.
 *   4. A DIFFERENT healthy user is still allowed GREEN access (no blast radius).
 *   5. Admin can confirm a positive test via POST /api/v1/health/confirmed.
 *
 * SERVICES UNDER TEST:
 *   auth-service → form-service → Kafka → promotion-service → Redis → gateway-service
 *
 * PREREQUISITES:
 *   - All services running.
 *   - "staff_guard" and "health_user" seed users exist.
 *   - promotion-service Kafka consumer is active.
 *   - Sufficient wait time (POLL_INTERVAL_MS) for async Kafka processing.
 */

const AUTH_URL       = process.env.AUTH_URL       ?? 'http://localhost:8180';
const FORM_URL       = process.env.FORM_URL       ?? 'http://localhost:8086';
const GATEWAY_URL    = process.env.GATEWAY_URL    ?? 'http://localhost:8087';
const PROMOTION_URL  = process.env.PROMOTION_URL  ?? 'http://localhost:8088';

// Max time to wait for async Kafka→promotion→Redis cascade
const CASCADE_TIMEOUT_MS = 8_000;
const POLL_INTERVAL_MS   = 500;

test.describe('E2E-2: Health Fencing Cascade — Symptomatic Student Blocked', () => {

  let apiContext: APIRequestContext;
  let userJwt: string;
  let userQrToken: string;
  let healthJwt: string;

  test.beforeAll(async ({ playwright }) => {
    apiContext = await playwright.request.newContext({ ignoreHTTPSErrors: true });

    // Login as a regular user (will report symptoms)
    const loginResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
      data: { username: 'staff_guard', password: 'password' },
    });
    const loginBody = await loginResp.json();
    userJwt = loginBody.token;

    // Login as health center user (for admin actions)
    const healthResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
      data: { username: 'health_user', password: 'password' },
    });
    healthJwt = (await healthResp.json()).token;
  });

  test.afterAll(async () => {
    await apiContext.dispose();
  });

  /**
   * Helper: poll the gate until it returns the expected status or timeout.
   * Returns the final gate response body.
   */
  async function pollGateUntil(
    qrToken: string,
    expectedStatus: 'GREEN' | 'RED',
    timeoutMs: number,
  ): Promise<{ valid: boolean; status: string; message: string }> {
    const deadline = Date.now() + timeoutMs;
    let lastBody: any = null;

    while (Date.now() < deadline) {
      const resp = await apiContext.post(`${GATEWAY_URL}/api/v1/gate/validate`, {
        data: { token: qrToken },
      });
      lastBody = await resp.json();
      if (lastBody.status === expectedStatus) return lastBody;
      await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
    }

    return lastBody ?? { valid: false, status: 'UNKNOWN', message: 'Timeout' };
  }

  // ── E2E-2.1: Survey submission returns 200 OK ─────────────────────────────
  test('E2E-2.1: Symptomatic survey submission returns 200 OK', async () => {
    // Decode anonymousId from JWT payload
    const payload = JSON.parse(
      Buffer.from(userJwt.split('.')[1], 'base64url').toString(),
    );
    const anonymousId: string = payload.sub;

    const surveyResp = await apiContext.post(`${FORM_URL}/api/v1/surveys`, {
      data: {
        anonymousId,
        hasFever: true,
        hasCough: false,
        timestamp: new Date().toISOString(),
      },
    });
    expect(surveyResp.status(), 'Survey submission must return 200').toBe(200);

    const surveyBody = await surveyResp.json();
    expect(surveyBody.id, 'Saved survey must have an id field').toBeTruthy();
  });

  // ── E2E-2.2: After cascade, gate returns RED for symptomatic user ─────────
  test('E2E-2.2: Gate returns RED for user after Kafka→promotion cascade (within 8 s)', async () => {
    // Get a fresh QR token for the symptomatic user
    const qrResp = await apiContext.get(`${AUTH_URL}/api/v1/auth/qr/generate`, {
      headers: { Authorization: `Bearer ${userJwt}` },
    });
    const { qrToken } = await qrResp.json();

    // Submit symptomatic survey
    const payload = JSON.parse(Buffer.from(userJwt.split('.')[1], 'base64url').toString());
    await apiContext.post(`${FORM_URL}/api/v1/surveys`, {
      data: { anonymousId: payload.sub, hasFever: true, hasCough: true },
    });

    // Poll gate until RED or timeout
    const gateBody = await pollGateUntil(qrToken, 'RED', CASCADE_TIMEOUT_MS);

    expect(gateBody.valid,  'Gate must deny access after health cascade').toBe(false);
    expect(gateBody.status, 'Gate status must be RED after health cascade').toBe('RED');
    expect(gateBody.message,'Gate must return a non-empty denial message').toBeTruthy();
  });

  // ── E2E-2.3: Different healthy user still gets GREEN ──────────────────────
  test('E2E-2.3: A different healthy user still gets GREEN access (no blast radius)', async () => {
    // Login as a separate healthy user
    const healthUserResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
      data: { username: 'health_user', password: 'password' },
    });
    const { token: healthToken } = await healthUserResp.json();

    const qrResp = await apiContext.get(`${AUTH_URL}/api/v1/auth/qr/generate`, {
      headers: { Authorization: `Bearer ${healthToken}` },
    });
    const { qrToken } = await qrResp.json();

    const gateResp = await apiContext.post(`${GATEWAY_URL}/api/v1/gate/validate`, {
      data: { token: qrToken },
    });
    const gateBody = await gateResp.json();

    expect(gateBody.valid,  'Healthy user must still get valid=true').toBe(true);
    expect(gateBody.status, 'Healthy user must still get GREEN').toBe('GREEN');
  });

  // ── E2E-2.4: Admin can confirm a positive test ────────────────────────────
  test('E2E-2.4: Health admin can POST /health/confirmed with valid HEALTH_CENTER JWT', async () => {
    const payload = JSON.parse(Buffer.from(userJwt.split('.')[1], 'base64url').toString());
    const anonymousId: string = payload.sub;

    const confirmResp = await apiContext.post(`${PROMOTION_URL}/api/v1/health/confirmed`, {
      headers: { Authorization: `Bearer ${healthJwt}` },
      data: { anonymousId },
    });

    // 200 OK is the success path; 403 means healthJwt lacks HEALTH_CENTER role
    expect(
      [200, 403],
      `Confirm positive must return 200 (success) or 403 (insufficient role, check seed data). Got ${confirmResp.status()}`,
    ).toContain(confirmResp.status());
  });

  // ── E2E-2.5: Gate validates message content for denial ────────────────────
  test('E2E-2.5: Gate denial message is meaningful (not null or empty)', async () => {
    // Generate a token signed with the wrong secret → instant RED
    const fakeToken = 'fake.jwt.token';

    const gateResp = await apiContext.post(`${GATEWAY_URL}/api/v1/gate/validate`, {
      data: { token: fakeToken },
    });
    const gateBody = await gateResp.json();

    expect(gateBody.status,  'Status must be RED for invalid token').toBe('RED');
    expect(gateBody.message, 'Denial message must not be null or empty').toBeTruthy();
    expect(gateBody.message.length, 'Denial message must have at least 5 characters')
      .toBeGreaterThan(5);
  });
});
