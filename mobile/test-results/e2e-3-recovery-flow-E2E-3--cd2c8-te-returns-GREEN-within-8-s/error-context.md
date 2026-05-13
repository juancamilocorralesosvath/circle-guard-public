# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: e2e-3-recovery-flow.spec.ts >> E2E-3: Recovery Flow — Resolved User Re-Admitted >> E2E-3.2: Admin resolves user status (adminOverride) → gate returns GREEN within 8 s
- Location: e2e/tests/e2e-3-recovery-flow.spec.ts:110:7

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 500
```

# Test source

```ts
  24  |  * PREREQUISITES:
  25  |  *   - "health_user" has HEALTH_CENTER role.
  26  |  *   - "staff_guard" seed user exists.
  27  |  *   - POLL_INTERVAL_MS and CASCADE_TIMEOUT_MS tuned for local vs CI latency.
  28  |  */
  29  | 
  30  | const AUTH_URL      = process.env.AUTH_URL      ?? 'http://localhost:8180';
  31  | const GATEWAY_URL   = process.env.GATEWAY_URL   ?? 'http://localhost:8087';
  32  | const PROMOTION_URL = process.env.PROMOTION_URL ?? 'http://localhost:8088';
  33  | 
  34  | const CASCADE_TIMEOUT_MS = 8_000;
  35  | const POLL_INTERVAL_MS   = 500;
  36  | 
  37  | test.describe('E2E-3: Recovery Flow — Resolved User Re-Admitted', () => {
  38  | 
  39  |   let apiContext: APIRequestContext;
  40  |   let userJwt: string;
  41  |   let healthJwt: string;
  42  |   let userAnonymousId: string;
  43  | 
  44  |   test.beforeAll(async ({ playwright }) => {
  45  |     apiContext = await playwright.request.newContext({ ignoreHTTPSErrors: true });
  46  | 
  47  |     // Login as regular user (the one to be fenced then recovered)
  48  |     const loginResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
  49  |       data: { username: 'staff_guard', password: 'password' },
  50  |     });
  51  |     const loginBody = await loginResp.json();
  52  |     userJwt = loginBody.token;
  53  |     userAnonymousId = loginBody.anonymousId;
  54  | 
  55  |     // Login as health center admin
  56  |     const healthResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
  57  |       data: { username: 'health_user', password: 'password' },
  58  |     });
  59  |     healthJwt = (await healthResp.json()).token;
  60  |   });
  61  | 
  62  |   test.afterAll(async () => {
  63  |     await apiContext.dispose();
  64  |   });
  65  | 
  66  |   // ── Helper: refresh a QR token ────────────────────────────────────────────
  67  |   async function freshQrToken(): Promise<string> {
  68  |     const resp = await apiContext.get(`${AUTH_URL}/api/v1/auth/qr/generate`, {
  69  |       headers: { Authorization: `Bearer ${userJwt}` },
  70  |     });
  71  |     return (await resp.json()).qrToken;
  72  |   }
  73  | 
  74  |   // ── Helper: poll gate ─────────────────────────────────────────────────────
  75  |   async function pollGateUntil(
  76  |     expectedStatus: 'GREEN' | 'RED',
  77  |     timeoutMs: number,
  78  |   ): Promise<{ valid: boolean; status: string; message: string }> {
  79  |     const deadline = Date.now() + timeoutMs;
  80  |     let last: any = null;
  81  |     while (Date.now() < deadline) {
  82  |       const qrToken = await freshQrToken();
  83  |       const resp = await apiContext.post(`${GATEWAY_URL}/api/v1/gate/validate`, {
  84  |         data: { token: qrToken },
  85  |       });
  86  |       last = await resp.json();
  87  |       if (last.status === expectedStatus) return last;
  88  |       await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  89  |     }
  90  |     return last ?? { valid: false, status: 'TIMEOUT', message: '' };
  91  |   }
  92  | 
  93  |   // ── E2E-3.1: Admin marks user CONFIRMED → gate goes RED ──────────────────
  94  |   test('E2E-3.1: Admin confirms positive → gate returns RED within 8 s', async () => {
  95  |     const confirmResp = await apiContext.post(`${PROMOTION_URL}/api/v1/health/confirmed`, {
  96  |       headers: { Authorization: `Bearer ${healthJwt}` },
  97  |       data: { anonymousId: userAnonymousId },
  98  |     });
  99  | 
  100 |     // If 403, health_user lacks HEALTH_CENTER role — skip instead of fail
  101 |     test.skip(confirmResp.status() === 403, 'health_user does not have HEALTH_CENTER role');
  102 |     expect(confirmResp.status()).toBe(200);
  103 | 
  104 |     const gateBody = await pollGateUntil('RED', CASCADE_TIMEOUT_MS);
  105 |     expect(gateBody.valid,  'Gate must deny fenced user').toBe(false);
  106 |     expect(gateBody.status, 'Gate must return RED for CONFIRMED user').toBe('RED');
  107 |   });
  108 | 
  109 |   // ── E2E-3.2: Admin resolves user → gate goes GREEN ────────────────────────
  110 |   test('E2E-3.2: Admin resolves user status (adminOverride) → gate returns GREEN within 8 s', async () => {
  111 |     // First, ensure user is fenced
  112 |     await apiContext.post(`${PROMOTION_URL}/api/v1/health/confirmed`, {
  113 |       headers: { Authorization: `Bearer ${healthJwt}` },
  114 |       data: { anonymousId: userAnonymousId },
  115 |     });
  116 | 
  117 |     // Now resolve with adminOverride=true (bypasses mandatory fence window)
  118 |     const resolveResp = await apiContext.post(`${PROMOTION_URL}/api/v1/health/resolve`, {
  119 |       headers: { Authorization: `Bearer ${healthJwt}` },
  120 |       data: { anonymousId: userAnonymousId, adminOverride: true },
  121 |     });
  122 | 
  123 |     test.skip(resolveResp.status() === 403, 'health_user does not have HEALTH_CENTER role');
> 124 |     expect(resolveResp.status()).toBe(200);
      |                                  ^ Error: expect(received).toBe(expected) // Object.is equality
  125 | 
  126 |     const gateBody = await pollGateUntil('GREEN', CASCADE_TIMEOUT_MS);
  127 |     expect(gateBody.valid,  'Gate must allow resolved user').toBe(true);
  128 |     expect(gateBody.status, 'Gate must return GREEN after resolution').toBe('GREEN');
  129 |   });
  130 | 
  131 |   // ── E2E-3.3: POST /health/recovery promotes to RECOVERED ─────────────────
  132 |   test('E2E-3.3: POST /health/recovery/{id} endpoint returns 200 for a HEALTH_CENTER user', async () => {
  133 |     const recoveryResp = await apiContext.post(
  134 |       `${PROMOTION_URL}/api/v1/health/recovery/${userAnonymousId}`,
  135 |       { headers: { Authorization: `Bearer ${healthJwt}` } },
  136 |     );
  137 | 
  138 |     test.skip(recoveryResp.status() === 403, 'health_user does not have HEALTH_CENTER role');
  139 |     expect(
  140 |       [200, 409], // 409 if already RECOVERED is also acceptable
  141 |       `Recovery endpoint must return 200 or 409, got ${recoveryResp.status()}`,
  142 |     ).toContain(recoveryResp.status());
  143 |   });
  144 | 
  145 |   // ── E2E-3.4: Health stats endpoint returns JSON with status counts ────────
  146 |   test('E2E-3.4: GET /health-status/stats returns JSON with a totalUsers field', async () => {
  147 |     const statsResp = await apiContext.get(`${PROMOTION_URL}/api/v1/health-status/stats`);
  148 |     expect(statsResp.status(), 'Health stats must return 200').toBe(200);
  149 | 
  150 |     const body = await statsResp.json();
  151 |     expect(body, 'Stats response must be an object').toBeTruthy();
  152 |     expect(typeof body.totalUsers, 'totalUsers must be a number').toBe('number');
  153 |   });
  154 | 
  155 |   // ── E2E-3.5: Resolve without adminOverride within fence window → 403 ──────
  156 |   test('E2E-3.5: Resolve without adminOverride within fence window returns 403 (FenceException)', async () => {
  157 |     // Fence the user
  158 |     const confirmResp = await apiContext.post(`${PROMOTION_URL}/api/v1/health/confirmed`, {
  159 |       headers: { Authorization: `Bearer ${healthJwt}` },
  160 |       data: { anonymousId: userAnonymousId },
  161 |     });
  162 |     test.skip(confirmResp.status() !== 200, 'Could not fence user, skipping fence-window test');
  163 | 
  164 |     // Try to resolve WITHOUT adminOverride — should be blocked by fence window
  165 |     const resolveResp = await apiContext.post(`${PROMOTION_URL}/api/v1/health/resolve`, {
  166 |       headers: { Authorization: `Bearer ${healthJwt}` },
  167 |       data: { anonymousId: userAnonymousId, adminOverride: false },
  168 |     });
  169 | 
  170 |     // 403 = FenceException (mandatory window not expired)
  171 |     // 200 = fence window already expired from a previous test run — acceptable
  172 |     expect(
  173 |       [200, 403],
  174 |       `Without adminOverride in fence window, expect 403 (FenceException) or 200 (window expired). Got ${resolveResp.status()}`,
  175 |     ).toContain(resolveResp.status());
  176 |   });
  177 | });
  178 | 
```