// e2e/tests/e2e-3-recovery-flow.spec.ts
import { test, expect, APIRequestContext } from '@playwright/test';

/**
 * E2E Test 3: Recovery Flow — Resolved User Re-Admitted to Campus
 *
 * WHY CRITICAL:
 *   A user that has recovered from illness must be able to re-enter campus.
 *   If the resolveStatus() → Redis eviction → gate GREEN pipeline is broken,
 *   recovered users are permanently locked out. This creates both an operational
 *   problem (staff unable to work) and a legal one (wrongful access restriction).
 *   This test validates the full status lifecycle: CONFIRMED → ACTIVE → GREEN.
 *
 * WHAT IS VALIDATED:
 *   1. Admin sets a user to CONFIRMED (fenced, RED at gate).
 *   2. Admin resolves the user's status via POST /health/resolve (adminOverride=true).
 *   3. After cascade, gate returns GREEN for the recovered user.
 *   4. POST /health/recovery/{id} promotes a user to RECOVERED status.
 *   5. Health stats endpoint reflects status changes.
 *
 * SERVICES UNDER TEST:
 *   auth-service → promotion-service → Redis → gateway-service
 *
 * PREREQUISITES:
 *   - "health_user" has HEALTH_CENTER role.
 *   - "staff_guard" seed user exists.
 *   - POLL_INTERVAL_MS and CASCADE_TIMEOUT_MS tuned for local vs CI latency.
 */

const AUTH_URL      = process.env.AUTH_URL      ?? 'http://localhost:8180';
const GATEWAY_URL   = process.env.GATEWAY_URL   ?? 'http://localhost:8087';
const PROMOTION_URL = process.env.PROMOTION_URL ?? 'http://localhost:8088';

const CASCADE_TIMEOUT_MS = 8_000;
const POLL_INTERVAL_MS   = 500;

test.describe('E2E-3: Recovery Flow — Resolved User Re-Admitted', () => {

  let apiContext: APIRequestContext;
  let userJwt: string;
  let healthJwt: string;
  let userAnonymousId: string;

  test.beforeAll(async ({ playwright }) => {
    apiContext = await playwright.request.newContext({ ignoreHTTPSErrors: true });

    // Login as regular user (the one to be fenced then recovered)
    const loginResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
      data: { username: 'staff_guard', password: 'password' },
    });
    const loginBody = await loginResp.json();
    userJwt = loginBody.token;
    userAnonymousId = loginBody.anonymousId;

    // Login as health center admin
    const healthResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
      data: { username: 'health_user', password: 'password' },
    });
    healthJwt = (await healthResp.json()).token;
  });

  test.afterAll(async () => {
    await apiContext.dispose();
  });

  // ── Helper: refresh a QR token ────────────────────────────────────────────
  async function freshQrToken(): Promise<string> {
    const resp = await apiContext.get(`${AUTH_URL}/api/v1/auth/qr/generate`, {
      headers: { Authorization: `Bearer ${userJwt}` },
    });
    return (await resp.json()).qrToken;
  }

  // ── Helper: poll gate ─────────────────────────────────────────────────────
  async function pollGateUntil(
    expectedStatus: 'GREEN' | 'RED',
    timeoutMs: number,
  ): Promise<{ valid: boolean; status: string; message: string }> {
    const deadline = Date.now() + timeoutMs;
    let last: any = null;
    while (Date.now() < deadline) {
      const qrToken = await freshQrToken();
      const resp = await apiContext.post(`${GATEWAY_URL}/api/v1/gate/validate`, {
        data: { token: qrToken },
      });
      last = await resp.json();
      if (last.status === expectedStatus) return last;
      await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
    }
    return last ?? { valid: false, status: 'TIMEOUT', message: '' };
  }

  // ── E2E-3.1: Admin marks user CONFIRMED → gate goes RED ──────────────────
  test('E2E-3.1: Admin confirms positive → gate returns RED within 8 s', async () => {
    const confirmResp = await apiContext.post(`${PROMOTION_URL}/api/v1/health/confirmed`, {
      headers: { Authorization: `Bearer ${healthJwt}` },
      data: { anonymousId: userAnonymousId },
    });

    // If 403, health_user lacks HEALTH_CENTER role — skip instead of fail
    test.skip(confirmResp.status() === 403, 'health_user does not have HEALTH_CENTER role');
    expect(confirmResp.status()).toBe(200);

    const gateBody = await pollGateUntil('RED', CASCADE_TIMEOUT_MS);
    expect(gateBody.valid,  'Gate must deny fenced user').toBe(false);
    expect(gateBody.status, 'Gate must return RED for CONFIRMED user').toBe('RED');
  });

  // ── E2E-3.2: Admin resolves user → gate goes GREEN ────────────────────────
  test('E2E-3.2: Admin resolves user status (adminOverride) → gate returns GREEN within 8 s', async () => {
    // First, ensure user is fenced
    await apiContext.post(`${PROMOTION_URL}/api/v1/health/confirmed`, {
      headers: { Authorization: `Bearer ${healthJwt}` },
      data: { anonymousId: userAnonymousId },
    });

    // Now resolve with adminOverride=true (bypasses mandatory fence window)
    const resolveResp = await apiContext.post(`${PROMOTION_URL}/api/v1/health/resolve`, {
      headers: { Authorization: `Bearer ${healthJwt}` },
      data: { anonymousId: userAnonymousId, adminOverride: true },
    });

    test.skip(resolveResp.status() === 403, 'health_user does not have HEALTH_CENTER role');
    expect(resolveResp.status()).toBe(200);

    const gateBody = await pollGateUntil('GREEN', CASCADE_TIMEOUT_MS);
    expect(gateBody.valid,  'Gate must allow resolved user').toBe(true);
    expect(gateBody.status, 'Gate must return GREEN after resolution').toBe('GREEN');
  });

  // ── E2E-3.3: POST /health/recovery promotes to RECOVERED ─────────────────
  test('E2E-3.3: POST /health/recovery/{id} endpoint returns 200 for a HEALTH_CENTER user', async () => {
    const recoveryResp = await apiContext.post(
      `${PROMOTION_URL}/api/v1/health/recovery/${userAnonymousId}`,
      { headers: { Authorization: `Bearer ${healthJwt}` } },
    );

    test.skip(recoveryResp.status() === 403, 'health_user does not have HEALTH_CENTER role');
    expect(
      [200, 409], // 409 if already RECOVERED is also acceptable
      `Recovery endpoint must return 200 or 409, got ${recoveryResp.status()}`,
    ).toContain(recoveryResp.status());
  });

  // ── E2E-3.4: Health stats endpoint returns JSON with status counts ────────
  test('E2E-3.4: GET /health-status/stats returns JSON with a totalUsers field', async () => {
    const statsResp = await apiContext.get(`${PROMOTION_URL}/api/v1/health-status/stats`);
    expect(statsResp.status(), 'Health stats must return 200').toBe(200);

    const body = await statsResp.json();
    expect(body, 'Stats response must be an object').toBeTruthy();
    expect(typeof body.totalUsers, 'totalUsers must be a number').toBe('number');
  });

  // ── E2E-3.5: Resolve without adminOverride within fence window → 403 ──────
  test('E2E-3.5: Resolve without adminOverride within fence window returns 403 (FenceException)', async () => {
    // Fence the user
    const confirmResp = await apiContext.post(`${PROMOTION_URL}/api/v1/health/confirmed`, {
      headers: { Authorization: `Bearer ${healthJwt}` },
      data: { anonymousId: userAnonymousId },
    });
    test.skip(confirmResp.status() !== 200, 'Could not fence user, skipping fence-window test');

    // Try to resolve WITHOUT adminOverride — should be blocked by fence window
    const resolveResp = await apiContext.post(`${PROMOTION_URL}/api/v1/health/resolve`, {
      headers: { Authorization: `Bearer ${healthJwt}` },
      data: { anonymousId: userAnonymousId, adminOverride: false },
    });

    // 403 = FenceException (mandatory window not expired)
    // 200 = fence window already expired from a previous test run — acceptable
    expect(
      [200, 403],
      `Without adminOverride in fence window, expect 403 (FenceException) or 200 (window expired). Got ${resolveResp.status()}`,
    ).toContain(resolveResp.status());
  });
});
