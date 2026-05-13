# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: e2e-4-admin-confirms-positive-contacts-notified.spec.ts >> E2E-4: Admin Confirms Positive — RBAC, Cascade & Notification >> E2E-4.4: A circle can be created and the confirmed user can be added as a member
- Location: e2e/tests/e2e-4-admin-confirms-positive-contacts-notified.spec.ts:173:7

# Error details

```
Error: Adding member must return 200 (added) or 409 (already member). Got 500

expect(received).toContain(expected) // indexOf

Expected value: 500
Received array: [200, 409]
```

# Test source

```ts
  100 |       // 403 means health_user seed data is missing the HEALTH_CENTER role
  101 |       test.skip(
  102 |         resp.status() === 403,
  103 |         'health_user does not have HEALTH_CENTER role in this environment — check seed data',
  104 |       );
  105 | 
  106 |       expect(resp.status(), 'HEALTH_CENTER user must get 200 on /confirmed').toBe(200);
  107 |     },
  108 |   );
  109 | 
  110 |   // ──────────────────────────────────────────────────────────────────────────
  111 |   // E2E-4.2  STUDENT role is forbidden from calling /health/confirmed → 403
  112 |   // ──────────────────────────────────────────────────────────────────────────
  113 |   test(
  114 |     'E2E-4.2: POST /health/confirmed with STUDENT/GATE_STAFF JWT returns 403 (RBAC enforced)',
  115 |     async () => {
  116 |       const resp = await api.post(`${PROMOTION_URL}/api/v1/health/confirmed`, {
  117 |         headers: { Authorization: `Bearer ${studentJwt}` },
  118 |         data:    { anonymousId: targetAnonymousId },
  119 |       });
  120 | 
  121 |       expect(
  122 |         resp.status(),
  123 |         'A non-HEALTH_CENTER user must be denied /confirmed with 403',
  124 |       ).toBe(403);
  125 |     },
  126 |   );
  127 | 
  128 |   // ──────────────────────────────────────────────────────────────────────────
  129 |   // E2E-4.3  Health stats confirmedCount increases after /health/confirmed
  130 |   // ──────────────────────────────────────────────────────────────────────────
  131 |   test(
  132 |     'E2E-4.3: confirmedCount in /health-status/stats increases after marking a user CONFIRMED',
  133 |     async () => {
  134 |       // Baseline snapshot
  135 |       const beforeResp = await api.get(`${PROMOTION_URL}/api/v1/health-status/stats`);
  136 |       expect(beforeResp.ok(), 'Stats endpoint must be reachable').toBeTruthy();
  137 |       const before = await beforeResp.json();
  138 |       const confirmedBefore: number = before.confirmedCount ?? 0;
  139 | 
  140 |       // Mark the user CONFIRMED
  141 |       const confirmResp = await api.post(`${PROMOTION_URL}/api/v1/health/confirmed`, {
  142 |         headers: { Authorization: `Bearer ${healthJwt}` },
  143 |         data:    { anonymousId: targetAnonymousId },
  144 |       });
  145 |       test.skip(
  146 |         confirmResp.status() === 403,
  147 |         'health_user lacks HEALTH_CENTER role — skipping stats assertion',
  148 |       );
  149 |       expect(confirmResp.status()).toBe(200);
  150 | 
  151 |       // Poll stats until confirmedCount increases or timeout
  152 |       const deadline = Date.now() + CASCADE_TIMEOUT_MS;
  153 |       let confirmedAfter = confirmedBefore;
  154 | 
  155 |       while (Date.now() < deadline) {
  156 |         await sleep(POLL_INTERVAL_MS);
  157 |         const afterResp = await api.get(`${PROMOTION_URL}/api/v1/health-status/stats`);
  158 |         const after = await afterResp.json();
  159 |         confirmedAfter = after.confirmedCount ?? 0;
  160 |         if (confirmedAfter > confirmedBefore) break;
  161 |       }
  162 | 
  163 |       expect(
  164 |         confirmedAfter,
  165 |         `confirmedCount must increase after marking user CONFIRMED. Before: ${confirmedBefore}, After: ${confirmedAfter}`,
  166 |       ).toBeGreaterThan(confirmedBefore);
  167 |     },
  168 |   );
  169 | 
  170 |   // ──────────────────────────────────────────────────────────────────────────
  171 |   // E2E-4.4  A safety circle can be created and the confirmed user added to it
  172 |   // ──────────────────────────────────────────────────────────────────────────
  173 |   test(
  174 |     'E2E-4.4: A circle can be created and the confirmed user can be added as a member',
  175 |     async () => {
  176 |       // Create a new circle
  177 |       const createResp = await api.post(`${PROMOTION_URL}/api/v1/circles`, {
  178 |         headers: { Authorization: `Bearer ${healthJwt}` },
  179 |         data:    { name: `E2E-Test-Circle-${Date.now()}`, locationId: 'e2e-building-1' },
  180 |       });
  181 | 
  182 |       // If the endpoint requires a different permission, skip gracefully
  183 |       if (createResp.status() === 403) {
  184 |         test.skip(true, 'Circle creation requires elevated permission not held by health_user');
  185 |       }
  186 |       expect(createResp.status(), 'Circle creation must return 200').toBe(200);
  187 | 
  188 |       const circle = await createResp.json();
  189 |       expect(circle.id,         'Created circle must have an id').toBeTruthy();
  190 |       expect(circle.inviteCode, 'Created circle must have an inviteCode').toBeTruthy();
  191 | 
  192 |       // Add the confirmed user to the circle
  193 |       const addResp = await api.post(
  194 |         `${PROMOTION_URL}/api/v1/circles/${circle.id}/members/${targetAnonymousId}`,
  195 |         { headers: { Authorization: `Bearer ${healthJwt}` } },
  196 |       );
  197 |       expect(
  198 |         [200, 409],
  199 |         `Adding member must return 200 (added) or 409 (already member). Got ${addResp.status()}`,
> 200 |       ).toContain(addResp.status());
      |         ^ Error: Adding member must return 200 (added) or 409 (already member). Got 500
  201 |     },
  202 |   );
  203 | 
  204 |   // ──────────────────────────────────────────────────────────────────────────
  205 |   // E2E-4.5  GET /circles/user/{id} returns the circles a user belongs to
  206 |   // ──────────────────────────────────────────────────────────────────────────
  207 |   test(
  208 |     'E2E-4.5: GET /circles/user/{anonymousId} returns an array (even if empty)',
  209 |     async () => {
  210 |       const resp = await api.get(
  211 |         `${PROMOTION_URL}/api/v1/circles/user/${targetAnonymousId}`,
  212 |         { headers: { Authorization: `Bearer ${healthJwt}` } },
  213 |       );
  214 | 
  215 |       expect(resp.status(), 'Get user circles must return 200').toBe(200);
  216 | 
  217 |       const body = await resp.json();
  218 |       expect(Array.isArray(body), 'User circles response must be an array').toBeTruthy();
  219 | 
  220 |       // Each circle (if any) must have at minimum an id and an inviteCode
  221 |       for (const circle of body) {
  222 |         expect(circle.id,         'Each circle must have an id').toBeTruthy();
  223 |         expect(circle.inviteCode, 'Each circle must have an inviteCode').toBeTruthy();
  224 |       }
  225 |     },
  226 |   );
  227 | });
  228 | 
```