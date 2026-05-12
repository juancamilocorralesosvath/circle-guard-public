// e2e/tests/e2e-1-happy-path-healthy-student.spec.ts
import { test, expect, request, APIRequestContext } from '@playwright/test';

/**
 * E2E Test 1: Happy Path — Healthy Student Enters Campus
 *
 * WHY CRITICAL:
 *   This is the baseline daily transaction for every student and staff member.
 *   It validates the complete token pipeline: login → QR generation → gate
 *   validation, touching auth-service, identity-service, and gateway-service.
 *   If this flow is broken, the entire campus entry system is non-functional.
 *
 * WHAT IS VALIDATED:
 *   1. POST /api/v1/auth/login returns a valid JWT and anonymousId.
 *   2. GET /api/v1/auth/qr/generate (with Bearer JWT) returns a qrToken.
 *   3. POST /api/v1/gate/validate with the qrToken returns status GREEN.
 *   4. The complete flow completes end-to-end in under 3 seconds.
 *   5. Gate response contains valid=true, status="GREEN", and a message.
 *
 * SERVICES UNDER TEST:
 *   auth-service (8180) → identity-service (8083) → gateway-service (8087)
 *
 * PREREQUISITES:
 *   - All services running (docker-compose or Kubernetes dev overlay).
 *   - Seed user "staff_guard" with password "password" exists in auth DB.
 *   - Redis is reachable by gateway-service and has no blocking entry for the test user.
 */

const AUTH_URL    = process.env.AUTH_URL    ?? 'http://localhost:8180';
const GATEWAY_URL = process.env.GATEWAY_URL ?? 'http://localhost:8087';

test.describe('E2E-1: Happy Path — Healthy Student Campus Entry', () => {

  let apiContext: APIRequestContext;

  test.beforeAll(async ({ playwright }) => {
    apiContext = await playwright.request.newContext({ ignoreHTTPSErrors: true });
  });

  test.afterAll(async () => {
    await apiContext.dispose();
  });

  // ── E2E-1.1: Full round-trip login → QR generate → gate validate ──────────
  test('E2E-1.1: login → QR generate → gate validate returns GREEN within 3 s', async () => {
    const startMs = Date.now();

    // Step 1: Login
    const loginResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
      data: { username: 'staff_guard', password: 'password' },
    });
    expect(loginResp.status(), 'Login must return 200').toBe(200);

    const loginBody = await loginResp.json();
    expect(loginBody.token,       'Login response must contain a JWT token').toBeTruthy();
    expect(loginBody.anonymousId, 'Login response must contain anonymousId').toBeTruthy();
    expect(loginBody.type,        'Token type must be Bearer').toBe('Bearer');

    const { token, anonymousId } = loginBody;

    // Step 2: Generate QR token
    const qrResp = await apiContext.get(`${AUTH_URL}/api/v1/auth/qr/generate`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(qrResp.status(), 'QR generate must return 200').toBe(200);

    const qrBody = await qrResp.json();
    expect(qrBody.qrToken,   'QR response must contain qrToken').toBeTruthy();
    expect(qrBody.expiresIn, 'QR response must contain expiresIn').toBeTruthy();

    const { qrToken } = qrBody;

    // Step 3: Validate at gate
    const gateResp = await apiContext.post(`${GATEWAY_URL}/api/v1/gate/validate`, {
      data: { token: qrToken },
    });
    expect(gateResp.status(), 'Gate validate must return 200').toBe(200);

    const gateBody = await gateResp.json();
    expect(gateBody.valid,   'Gate must grant access (valid=true)').toBe(true);
    expect(gateBody.status,  'Gate status must be GREEN').toBe('GREEN');
    expect(gateBody.message, 'Gate must return a non-empty message').toBeTruthy();

    // Step 4: Total duration SLA
    const elapsedMs = Date.now() - startMs;
    expect(elapsedMs, `Full round-trip must complete in < 3 000 ms (actual: ${elapsedMs} ms)`)
      .toBeLessThan(3_000);
  });

  // ── E2E-1.2: JWT has three-part structure ─────────────────────────────────
  test('E2E-1.2: JWT returned by login has a valid three-part header.payload.signature structure', async () => {
    const loginResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
      data: { username: 'staff_guard', password: 'password' },
    });
    const { token } = await loginResp.json();

    const parts = token.split('.');
    expect(parts.length, 'JWT must have exactly 3 dot-separated parts').toBe(3);

    // Payload must be valid base64url-encoded JSON
    const payloadJson = Buffer.from(parts[1], 'base64url').toString('utf-8');
    const payload = JSON.parse(payloadJson);
    expect(payload.sub,  'JWT payload must contain sub (anonymousId)').toBeTruthy();
    expect(payload.exp,  'JWT payload must contain exp (expiration)').toBeTruthy();
    expect(payload.iat,  'JWT payload must contain iat (issued at)').toBeTruthy();
  });

  // ── E2E-1.3: QR token expires after expiresIn seconds ────────────────────
  test('E2E-1.3: QR token expiresIn value is between 50 and 70 seconds (60 s ± 10 s)', async () => {
    const loginResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
      data: { username: 'staff_guard', password: 'password' },
    });
    const { token } = await loginResp.json();

    const qrResp = await apiContext.get(`${AUTH_URL}/api/v1/auth/qr/generate`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const { expiresIn } = await qrResp.json();

    const ttl = parseInt(expiresIn, 10);
    expect(ttl, `QR TTL should be ~60 s, got ${ttl}`).toBeGreaterThanOrEqual(50);
    expect(ttl, `QR TTL should be ~60 s, got ${ttl}`).toBeLessThanOrEqual(70);
  });

  // ── E2E-1.4: Wrong password returns 401 ──────────────────────────────────
  test('E2E-1.4: Incorrect password returns 401 Unauthorized with a message field', async () => {
    const resp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
      data: { username: 'staff_guard', password: 'WRONG_PASSWORD' },
    });
    expect(resp.status(), 'Wrong password must return 401').toBe(401);

    const body = await resp.json();
    expect(body.message, 'Error response must contain a message field').toBeTruthy();
  });

  // ── E2E-1.5: QR generate without Bearer token returns 401/403 ────────────
  test('E2E-1.5: QR generate without Authorization header returns 401 or 403', async () => {
    const resp = await apiContext.get(`${AUTH_URL}/api/v1/auth/qr/generate`);
    expect([401, 403], 'Unauthenticated QR generate must return 401 or 403')
      .toContain(resp.status());
  });
});
