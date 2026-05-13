# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: e2e-2-health-fencing-cascade.spec.ts >> E2E-2: Health Fencing Cascade — Symptomatic Student Blocked >> E2E-2.3: A different healthy user still gets GREEN access (no blast radius)
- Location: e2e/tests/e2e-2-health-fencing-cascade.spec.ts:138:7

# Error details

```
Error: Healthy user must still get valid=true

expect(received).toBe(expected) // Object.is equality

Expected: true
Received: false
```

# Test source

```ts
  55  |     const loginBody = await loginResp.json();
  56  |     userJwt = loginBody.token;
  57  | 
  58  |     // Login as health center user (for admin actions)
  59  |     const healthResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
  60  |       data: { username: 'health_user', password: 'password' },
  61  |     });
  62  |     healthJwt = (await healthResp.json()).token;
  63  |   });
  64  | 
  65  |   test.afterAll(async () => {
  66  |     await apiContext.dispose();
  67  |   });
  68  | 
  69  |   /**
  70  |    * Helper: poll the gate until it returns the expected status or timeout.
  71  |    * Returns the final gate response body.
  72  |    */
  73  |   async function pollGateUntil(
  74  |     qrToken: string,
  75  |     expectedStatus: 'GREEN' | 'RED',
  76  |     timeoutMs: number,
  77  |   ): Promise<{ valid: boolean; status: string; message: string }> {
  78  |     const deadline = Date.now() + timeoutMs;
  79  |     let lastBody: any = null;
  80  | 
  81  |     while (Date.now() < deadline) {
  82  |       const resp = await apiContext.post(`${GATEWAY_URL}/api/v1/gate/validate`, {
  83  |         data: { token: qrToken },
  84  |       });
  85  |       lastBody = await resp.json();
  86  |       if (lastBody.status === expectedStatus) return lastBody;
  87  |       await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  88  |     }
  89  | 
  90  |     return lastBody ?? { valid: false, status: 'UNKNOWN', message: 'Timeout' };
  91  |   }
  92  | 
  93  |   // ── E2E-2.1: Survey submission returns 200 OK ─────────────────────────────
  94  |   test('E2E-2.1: Symptomatic survey submission returns 200 OK', async () => {
  95  |     // Decode anonymousId from JWT payload
  96  |     const payload = JSON.parse(
  97  |       Buffer.from(userJwt.split('.')[1], 'base64url').toString(),
  98  |     );
  99  |     const anonymousId: string = payload.sub;
  100 | 
  101 |     const surveyResp = await apiContext.post(`${FORM_URL}/api/v1/surveys`, {
  102 |       data: {
  103 |         anonymousId,
  104 |         hasFever: true,
  105 |         hasCough: false,
  106 |         timestamp: new Date().toISOString(),
  107 |       },
  108 |     });
  109 |     expect(surveyResp.status(), 'Survey submission must return 200').toBe(200);
  110 | 
  111 |     const surveyBody = await surveyResp.json();
  112 |     expect(surveyBody.id, 'Saved survey must have an id field').toBeTruthy();
  113 |   });
  114 | 
  115 |   // ── E2E-2.2: After cascade, gate returns RED for symptomatic user ─────────
  116 |   test('E2E-2.2: Gate returns RED for user after Kafka→promotion cascade (within 8 s)', async () => {
  117 |     // Get a fresh QR token for the symptomatic user
  118 |     const qrResp = await apiContext.get(`${AUTH_URL}/api/v1/auth/qr/generate`, {
  119 |       headers: { Authorization: `Bearer ${userJwt}` },
  120 |     });
  121 |     const { qrToken } = await qrResp.json();
  122 | 
  123 |     // Submit symptomatic survey
  124 |     const payload = JSON.parse(Buffer.from(userJwt.split('.')[1], 'base64url').toString());
  125 |     await apiContext.post(`${FORM_URL}/api/v1/surveys`, {
  126 |       data: { anonymousId: payload.sub, hasFever: true, hasCough: true },
  127 |     });
  128 | 
  129 |     // Poll gate until RED or timeout
  130 |     const gateBody = await pollGateUntil(qrToken, 'RED', CASCADE_TIMEOUT_MS);
  131 | 
  132 |     expect(gateBody.valid,  'Gate must deny access after health cascade').toBe(false);
  133 |     expect(gateBody.status, 'Gate status must be RED after health cascade').toBe('RED');
  134 |     expect(gateBody.message,'Gate must return a non-empty denial message').toBeTruthy();
  135 |   });
  136 | 
  137 |   // ── E2E-2.3: Different healthy user still gets GREEN ──────────────────────
  138 |   test('E2E-2.3: A different healthy user still gets GREEN access (no blast radius)', async () => {
  139 |     // Login as a separate healthy user
  140 |     const healthUserResp = await apiContext.post(`${AUTH_URL}/api/v1/auth/login`, {
  141 |       data: { username: 'health_user', password: 'password' },
  142 |     });
  143 |     const { token: healthToken } = await healthUserResp.json();
  144 | 
  145 |     const qrResp = await apiContext.get(`${AUTH_URL}/api/v1/auth/qr/generate`, {
  146 |       headers: { Authorization: `Bearer ${healthToken}` },
  147 |     });
  148 |     const { qrToken } = await qrResp.json();
  149 | 
  150 |     const gateResp = await apiContext.post(`${GATEWAY_URL}/api/v1/gate/validate`, {
  151 |       data: { token: qrToken },
  152 |     });
  153 |     const gateBody = await gateResp.json();
  154 | 
> 155 |     expect(gateBody.valid,  'Healthy user must still get valid=true').toBe(true);
      |                                                                       ^ Error: Healthy user must still get valid=true
  156 |     expect(gateBody.status, 'Healthy user must still get GREEN').toBe('GREEN');
  157 |   });
  158 | 
  159 |   // ── E2E-2.4: Admin can confirm a positive test ────────────────────────────
  160 |   test('E2E-2.4: Health admin can POST /health/confirmed with valid HEALTH_CENTER JWT', async () => {
  161 |     const payload = JSON.parse(Buffer.from(userJwt.split('.')[1], 'base64url').toString());
  162 |     const anonymousId: string = payload.sub;
  163 | 
  164 |     const confirmResp = await apiContext.post(`${PROMOTION_URL}/api/v1/health/confirmed`, {
  165 |       headers: { Authorization: `Bearer ${healthJwt}` },
  166 |       data: { anonymousId },
  167 |     });
  168 | 
  169 |     // 200 OK is the success path; 403 means healthJwt lacks HEALTH_CENTER role
  170 |     expect(
  171 |       [200, 403],
  172 |       `Confirm positive must return 200 (success) or 403 (insufficient role, check seed data). Got ${confirmResp.status()}`,
  173 |     ).toContain(confirmResp.status());
  174 |   });
  175 | 
  176 |   // ── E2E-2.5: Gate validates message content for denial ────────────────────
  177 |   test('E2E-2.5: Gate denial message is meaningful (not null or empty)', async () => {
  178 |     // Generate a token signed with the wrong secret → instant RED
  179 |     const fakeToken = 'fake.jwt.token';
  180 | 
  181 |     const gateResp = await apiContext.post(`${GATEWAY_URL}/api/v1/gate/validate`, {
  182 |       data: { token: fakeToken },
  183 |     });
  184 |     const gateBody = await gateResp.json();
  185 | 
  186 |     expect(gateBody.status,  'Status must be RED for invalid token').toBe('RED');
  187 |     expect(gateBody.message, 'Denial message must not be null or empty').toBeTruthy();
  188 |     expect(gateBody.message.length, 'Denial message must have at least 5 characters')
  189 |       .toBeGreaterThan(5);
  190 |   });
  191 | });
  192 | 
```