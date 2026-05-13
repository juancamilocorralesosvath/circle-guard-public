# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: e2e-5-questionnaire-driven-symptom-report.spec.ts >> E2E-5: Questionnaire-Driven Symptom Report — Full Dynamic Pipeline >> E2E-5.4: POST /surveys without anonymousId returns a 4xx client error
- Location: e2e/tests/e2e-5-questionnaire-driven-symptom-report.spec.ts:181:7

# Error details

```
Error: Survey without anonymousId must return 4xx, got 500

expect(received).toBeLessThan(expected)

Expected: < 500
Received:   500
```

# Test source

```ts
  99  |       expect(
  100 |         body.questions.length,
  101 |         'Active questionnaire must have at least one question',
  102 |       ).toBeGreaterThanOrEqual(1);
  103 |     },
  104 |   );
  105 | 
  106 |   // ──────────────────────────────────────────────────────────────────────────
  107 |   // E2E-5.2  Each question has id, text, and type fields
  108 |   // ──────────────────────────────────────────────────────────────────────────
  109 |   test(
  110 |     'E2E-5.2: Every question in the active questionnaire has id, text, and type fields',
  111 |     async () => {
  112 |       const resp = await api.get(`${FORM_URL}/api/v1/questionnaires/active`);
  113 |       test.skip(resp.status() === 404, 'No active questionnaire — skipping field validation');
  114 | 
  115 |       const { questions } = await resp.json();
  116 | 
  117 |       for (const q of questions) {
  118 |         expect(q.id,   `Question is missing "id" field: ${JSON.stringify(q)}`).toBeTruthy();
  119 |         expect(q.text, `Question is missing "text" field: ${JSON.stringify(q)}`).toBeTruthy();
  120 |         expect(q.type, `Question is missing "type" field: ${JSON.stringify(q)}`).toBeTruthy();
  121 | 
  122 |         const validTypes = ['YES_NO', 'SINGLE_CHOICE', 'MULTI_CHOICE', 'TEXT'];
  123 |         expect(
  124 |           validTypes,
  125 |           `Question type "${q.type}" must be one of ${validTypes.join(', ')}`,
  126 |         ).toContain(q.type);
  127 |       }
  128 |     },
  129 |   );
  130 | 
  131 |   // ──────────────────────────────────────────────────────────────────────────
  132 |   // E2E-5.3  Submitting a dynamic responses map (not legacy booleans) returns 200
  133 |   // ──────────────────────────────────────────────────────────────────────────
  134 |   test(
  135 |     'E2E-5.3: POST /surveys with a dynamic responses map (JSON key→answer) returns 200',
  136 |     async () => {
  137 |       // Fetch the active questionnaire to build a valid responses map
  138 |       const qResp = await api.get(`${FORM_URL}/api/v1/questionnaires/active`);
  139 |       test.skip(qResp.status() === 404, 'No active questionnaire — skipping survey submission');
  140 | 
  141 |       const questionnaire = await qResp.json();
  142 | 
  143 |       // Build a benign (asymptomatic) responses map so we do NOT fence the user here
  144 |       const responses: Record<string, string> = {};
  145 |       for (const q of questionnaire.questions) {
  146 |         switch (q.type) {
  147 |           case 'YES_NO':
  148 |             // Answer NO to every question — explicitly non-symptomatic
  149 |             responses[q.id] = 'NO';
  150 |             break;
  151 |           case 'SINGLE_CHOICE':
  152 |             responses[q.id] = Array.isArray(q.options) ? q.options[0] : 'option-1';
  153 |             break;
  154 |           case 'MULTI_CHOICE':
  155 |             responses[q.id] = '[]'; // empty selection — not symptomatic
  156 |             break;
  157 |           case 'TEXT':
  158 |             responses[q.id] = 'No issues to report.';
  159 |             break;
  160 |         }
  161 |       }
  162 | 
  163 |       const surveyResp = await api.post(`${FORM_URL}/api/v1/surveys`, {
  164 |         data: {
  165 |           anonymousId: userAnonymousId,
  166 |           responses,
  167 |           timestamp: new Date().toISOString(),
  168 |         },
  169 |       });
  170 | 
  171 |       expect(surveyResp.status(), 'Dynamic survey submission must return 200').toBe(200);
  172 | 
  173 |       const saved = await surveyResp.json();
  174 |       expect(saved.id, 'Saved survey must have an id').toBeTruthy();
  175 |     },
  176 |   );
  177 | 
  178 |   // ──────────────────────────────────────────────────────────────────────────
  179 |   // E2E-5.4  Survey without anonymousId returns a 4xx validation error
  180 |   // ──────────────────────────────────────────────────────────────────────────
  181 |   test(
  182 |     'E2E-5.4: POST /surveys without anonymousId returns a 4xx client error',
  183 |     async () => {
  184 |       const resp = await api.post(`${FORM_URL}/api/v1/surveys`, {
  185 |         data: {
  186 |           hasFever: true,
  187 |           timestamp: new Date().toISOString(),
  188 |           // anonymousId intentionally omitted
  189 |         },
  190 |       });
  191 | 
  192 |       expect(
  193 |         resp.status(),
  194 |         `Survey without anonymousId must return 4xx, got ${resp.status()}`,
  195 |       ).toBeGreaterThanOrEqual(400);
  196 |       expect(
  197 |         resp.status(),
  198 |         `Survey without anonymousId must return 4xx, got ${resp.status()}`,
> 199 |       ).toBeLessThan(500);
      |         ^ Error: Survey without anonymousId must return 4xx, got 500
  200 |     },
  201 |   );
  202 | 
  203 |   // ──────────────────────────────────────────────────────────────────────────
  204 |   // E2E-5.5  Symptomatic dynamic survey triggers gate RED within CASCADE_TIMEOUT_MS
  205 |   // ──────────────────────────────────────────────────────────────────────────
  206 |   test(
  207 |     'E2E-5.5: Symptomatic dynamic responses trigger gate RED within the cascade timeout',
  208 |     async () => {
  209 |       // Fetch active questionnaire
  210 |       const qResp = await api.get(`${FORM_URL}/api/v1/questionnaires/active`);
  211 |       test.skip(qResp.status() === 404, 'No active questionnaire — skipping cascade test');
  212 | 
  213 |       const questionnaire = await qResp.json();
  214 | 
  215 |       // Build a SYMPTOMATIC responses map by answering YES to the first fever/cough question
  216 |       const responses: Record<string, string> = {};
  217 |       let symptomTriggered = false;
  218 | 
  219 |       for (const q of questionnaire.questions) {
  220 |         const textLower: string = q.text.toLowerCase();
  221 |         const isSymptomQuestion =
  222 |           textLower.includes('fever') ||
  223 |           textLower.includes('cough') ||
  224 |           textLower.includes('breath');
  225 | 
  226 |         if (isSymptomQuestion && q.type === 'YES_NO' && !symptomTriggered) {
  227 |           responses[q.id] = 'YES'; // symptomatic answer
  228 |           symptomTriggered = true;
  229 |         } else {
  230 |           responses[q.id] = q.type === 'YES_NO' ? 'NO' : '[]';
  231 |         }
  232 |       }
  233 | 
  234 |       if (!symptomTriggered) {
  235 |         // None of the questions matched fever/cough/breath keywords in this environment
  236 |         // Fall back to legacy survey fields so the test is still meaningful
  237 |         await api.post(`${FORM_URL}/api/v1/surveys`, {
  238 |           data: { anonymousId: userAnonymousId, hasFever: true },
  239 |         });
  240 |       } else {
  241 |         // Submit the dynamic symptomatic survey
  242 |         const surveyResp = await api.post(`${FORM_URL}/api/v1/surveys`, {
  243 |           data: { anonymousId: userAnonymousId, responses, timestamp: new Date().toISOString() },
  244 |         });
  245 |         expect(surveyResp.status(), 'Symptomatic survey submission must return 200').toBe(200);
  246 |       }
  247 | 
  248 |       // Helper: get a fresh QR token each poll iteration
  249 |       async function freshQrToken(): Promise<string> {
  250 |         const r = await api.get(`${AUTH_URL}/api/v1/auth/qr/generate`, {
  251 |           headers: { Authorization: `Bearer ${userJwt}` },
  252 |         });
  253 |         return (await r.json()).qrToken as string;
  254 |       }
  255 | 
  256 |       // Poll gate until RED or timeout
  257 |       const deadline = Date.now() + CASCADE_TIMEOUT_MS;
  258 |       let gateBody: { valid: boolean; status: string; message: string } | null = null;
  259 | 
  260 |       while (Date.now() < deadline) {
  261 |         const qrToken = await freshQrToken();
  262 |         const gateResp = await api.post(`${GATEWAY_URL}/api/v1/gate/validate`, {
  263 |           data: { token: qrToken },
  264 |         });
  265 |         gateBody = await gateResp.json();
  266 |         if (gateBody?.status === 'RED') break;
  267 |         await sleep(POLL_INTERVAL_MS);
  268 |       }
  269 | 
  270 |       expect(
  271 |         gateBody?.status,
  272 |         `Gate must return RED after symptomatic dynamic survey within ${CASCADE_TIMEOUT_MS} ms`,
  273 |       ).toBe('RED');
  274 |       expect(gateBody?.valid, 'Gate valid must be false for fenced user').toBe(false);
  275 |     },
  276 |   );
  277 | 
  278 |   // ──────────────────────────────────────────────────────────────────────────
  279 |   // E2E-5.6  A new questionnaire can be created and activated via the API
  280 |   // ──────────────────────────────────────────────────────────────────────────
  281 |   test(
  282 |     'E2E-5.6: POST /questionnaires creates a new questionnaire; POST /activate makes it active',
  283 |     async () => {
  284 |       // Create a minimal questionnaire
  285 |       const createResp = await api.post(`${FORM_URL}/api/v1/questionnaires`, {
  286 |         data: {
  287 |           title:   `E2E Dynamic Form ${Date.now()}`,
  288 |           version: 99,
  289 |           questions: [
  290 |             {
  291 |               text:       'Do you have a fever? (E2E test)',
  292 |               type:       'YES_NO',
  293 |               orderIndex: 0,
  294 |             },
  295 |             {
  296 |               text:       'Do you have a cough? (E2E test)',
  297 |               type:       'YES_NO',
  298 |               orderIndex: 1,
  299 |             },
```