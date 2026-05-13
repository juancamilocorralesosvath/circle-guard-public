# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: e2e-1-happy-path-healthy-student.spec.ts >> E2E-1: Happy Path — Healthy Student Campus Entry >> E2E-1.1: login → QR generate → gate validate returns GREEN within 3 s
- Location: e2e/tests/e2e-1-happy-path-healthy-student.spec.ts:45:7

# Error details

```
Error: Gate must grant access (valid=true)

expect(received).toBe(expected) // Object.is equality

Expected: true
Received: false
```

# Test source

```ts
  1   | // e2e/tests/e2e-1-happy-path-healthy-student.spec.ts
  2   | import { test, expect, request, APIRequestContext } from '@playwright/test';
  3   | 
  4   | /**
  5   |  * E2E Test 1: Happy Path — Healthy Student Enters Campus
  6   |  *
  7   |  * WHY CRITICAL:
  8   |  *   This is the baseline daily transaction for every student and staff member.
  9   |  *   It validates the complete token pipeline: login → QR generation → gate
  10  |  *   validation, touching auth-service, identity-service, and gateway-service.
  11  |  *   If this flow is broken, the entire campus entry system is non-functional.
  12  |  *
  13  |  * WHAT IS VALIDATED:
  14  |  *   1. POST /api/v1/auth/login returns a valid JWT and anonymousId.
  15  |  *   2. GET /api/v1/auth/qr/generate (with Bearer JWT) returns a qrToken.
  16  |  *   3. POST /api/v1/gate/validate with the qrToken returns status GREEN.
  17  |  *   4. The complete flow completes end-to-end in under 3 seconds.
  18  |  *   5. Gate response contains valid=true, status="GREEN", and a message.
  19  |  *
  20  |  * SERVICES UNDER TEST:
  21  |  *   auth-service (8180) → identity-service (8083) → gateway-service (8087)
  22  |  *
  23  |  * PREREQUISITES:
  24  |  *   - All services running (docker-compose or Kubernetes dev overlay).
  25  |  *   - Seed user "staff_guard" with password "password" exists in auth DB.
  26  |  *   - Redis is reachable by gateway-service and has no blocking entry for the test user.
  27  |  */
  28  | 
  29  | const AUTH_URL    = process.env.AUTH_URL    ?? 'http://localhost:8180';
  30  | const GATEWAY_URL = process.env.GATEWAY_URL ?? 'http://localhost:8087';
  31  | 
  32  | test.describe('E2E-1: Happy Path — Healthy Student Campus Entry', () => {
  33  | 
  34  |   let apiContext: APIRequestContext;
  35  | 
  36  |   test.beforeAll(async ({ playwright }) => {
  37  |     apiContext = await playwright.request.newContext({ ignoreHTTPSErrors: true });
  38  |   });
  39  | 
  40  |   test.afterAll(async () => {
  41  |     await apiContext.dispose();
  42  |   });
  43  | 
  44  |   // ── E2E-1.1: Full round-trip login → QR generate → gate validate ──────────
  45  |   test('E2E-1.1: login → QR generate → gate validate returns GREEN within 3 s', async () => {
  46  |     const startMs = Date.now();
  47  | 
  48  |     // Step 1: Login
  49  |     const loginResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
  50  |       data: { username: 'staff_guard', password: 'password' },
  51  |     });
  52  |     expect(loginResp.status(), 'Login must return 200').toBe(200);
  53  | 
  54  |     const loginBody = await loginResp.json();
  55  |     expect(loginBody.token,       'Login response must contain a JWT token').toBeTruthy();
  56  |     expect(loginBody.anonymousId, 'Login response must contain anonymousId').toBeTruthy();
  57  |     expect(loginBody.type,        'Token type must be Bearer').toBe('Bearer');
  58  | 
  59  |     const { token, anonymousId } = loginBody;
  60  | 
  61  |     // Step 2: Generate QR token
  62  |     const qrResp = await apiContext.get(`${AUTH_URL}/api/v1/auth/qr/generate`, {
  63  |       headers: { Authorization: `Bearer ${token}` },
  64  |     });
  65  |     expect(qrResp.status(), 'QR generate must return 200').toBe(200);
  66  | 
  67  |     const qrBody = await qrResp.json();
  68  |     expect(qrBody.qrToken,   'QR response must contain qrToken').toBeTruthy();
  69  |     expect(qrBody.expiresIn, 'QR response must contain expiresIn').toBeTruthy();
  70  | 
  71  |     const { qrToken } = qrBody;
  72  | 
  73  |     // Step 3: Validate at gate
  74  |     const gateResp = await apiContext.post(`${GATEWAY_URL}/api/v1/gate/validate`, {
  75  |       data: { token: qrToken },
  76  |     });
  77  |     expect(gateResp.status(), 'Gate validate must return 200').toBe(200);
  78  | 
  79  |     const gateBody = await gateResp.json();
> 80  |     expect(gateBody.valid,   'Gate must grant access (valid=true)').toBe(true);
      |                                                                     ^ Error: Gate must grant access (valid=true)
  81  |     expect(gateBody.status,  'Gate status must be GREEN').toBe('GREEN');
  82  |     expect(gateBody.message, 'Gate must return a non-empty message').toBeTruthy();
  83  | 
  84  |     // Step 4: Total duration SLA
  85  |     const elapsedMs = Date.now() - startMs;
  86  |     expect(elapsedMs, `Full round-trip must complete in < 3 000 ms (actual: ${elapsedMs} ms)`)
  87  |       .toBeLessThan(3_000);
  88  |   });
  89  | 
  90  |   // ── E2E-1.2: JWT has three-part structure ─────────────────────────────────
  91  |   test('E2E-1.2: JWT returned by login has a valid three-part header.payload.signature structure', async () => {
  92  |     const loginResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
  93  |       data: { username: 'staff_guard', password: 'password' },
  94  |     });
  95  |     const { token } = await loginResp.json();
  96  | 
  97  |     const parts = token.split('.');
  98  |     expect(parts.length, 'JWT must have exactly 3 dot-separated parts').toBe(3);
  99  | 
  100 |     // Payload must be valid base64url-encoded JSON
  101 |     const payloadJson = Buffer.from(parts[1], 'base64url').toString('utf-8');
  102 |     const payload = JSON.parse(payloadJson);
  103 |     expect(payload.sub,  'JWT payload must contain sub (anonymousId)').toBeTruthy();
  104 |     expect(payload.exp,  'JWT payload must contain exp (expiration)').toBeTruthy();
  105 |     expect(payload.iat,  'JWT payload must contain iat (issued at)').toBeTruthy();
  106 |   });
  107 | 
  108 |   // ── E2E-1.3: QR token expires after expiresIn seconds ────────────────────
  109 |   test('E2E-1.3: QR token expiresIn value is between 50 and 70 seconds (60 s ± 10 s)', async () => {
  110 |     const loginResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
  111 |       data: { username: 'staff_guard', password: 'password' },
  112 |     });
  113 |     const { token } = await loginResp.json();
  114 | 
  115 |     const qrResp = await apiContext.get(`${AUTH_URL}/api/v1/auth/qr/generate`, {
  116 |       headers: { Authorization: `Bearer ${token}` },
  117 |     });
  118 |     const { expiresIn } = await qrResp.json();
  119 | 
  120 |     const ttl = parseInt(expiresIn, 10);
  121 |     expect(ttl, `QR TTL should be ~60 s, got ${ttl}`).toBeGreaterThanOrEqual(50);
  122 |     expect(ttl, `QR TTL should be ~60 s, got ${ttl}`).toBeLessThanOrEqual(70);
  123 |   });
  124 | 
  125 |   // ── E2E-1.4: Wrong password returns 401 ──────────────────────────────────
  126 |   test('E2E-1.4: Incorrect password returns 401 Unauthorized with a message field', async () => {
  127 |     const resp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
  128 |       data: { username: 'staff_guard', password: 'WRONG_PASSWORD' },
  129 |     });
  130 |     expect(resp.status(), 'Wrong password must return 401').toBe(401);
  131 | 
  132 |     const body = await resp.json();
  133 |     expect(body.message, 'Error response must contain a message field').toBeTruthy();
  134 |   });
  135 | 
  136 |   // ── E2E-1.5: QR generate without Bearer token returns 401/403 ────────────
  137 |   test('E2E-1.5: QR generate without Authorization header returns 401 or 403', async () => {
  138 |     const resp = await apiContext.get(`${AUTH_URL}/api/v1/auth/qr/generate`);
  139 |     expect([401, 403], 'Unauthenticated QR generate must return 401 or 403')
  140 |       .toContain(resp.status());
  141 |   });
  142 | });
  143 | 
```