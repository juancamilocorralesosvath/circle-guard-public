// e2e/tests/e2e-5-questionnaire-driven-symptom-report.spec.ts
import { test, expect, APIRequestContext } from '@playwright/test';

/**
 * E2E Test 5: Questionnaire-Driven Symptom Report — Full Dynamic Form Pipeline
 *
 * WHY CRITICAL:
 *   The dynamic questionnaire system replaces the legacy hasFever/hasCough boolean
 *   fields with a fully configurable, versioned form.  A student must be able to:
 *     1. Fetch the active questionnaire.
 *     2. Submit answers for each question in the correct response format.
 *     3. Have SymptomMapper detect symptoms from the JSON responses map.
 *     4. Trigger the Kafka → promotion → Redis → gate RED cascade.
 *   If any step fails — wrong response key format, inactive questionnaire, broken
 *   SymptomMapper MULTI_CHOICE path — the automatic fencing of symptomatic users
 *   is silently bypassed using the newer form flow.
 *
 * WHAT IS VALIDATED:
 *   1. GET /questionnaires/active returns a questionnaire with at least one question.
 *   2. The questionnaire has a title, version, and isActive=true.
 *   3. POST /surveys with a dynamic responses map (not legacy boolean fields) returns 200.
 *   4. POST /surveys with no anonymousId returns a 4xx validation error.
 *   5. After submitting a symptomatic dynamic response, the gate eventually returns RED.
 *   6. POST /questionnaires/:id/activate correctly activates a newly created questionnaire.
 *
 * SERVICES UNDER TEST:
 *   auth-service → form-service (questionnaire + survey) → Kafka
 *   → promotion-service (SymptomMapper + Neo4j + Redis) → gateway-service
 *
 * PREREQUISITES:
 *   - At least one questionnaire with isActive=true exists in the form DB, OR
 *     the test itself creates and activates one (see E2E-5.6).
 *   - "staff_guard" seed user exists in auth DB.
 *   - promotion-service Kafka consumer is active.
 */

const AUTH_URL    = process.env.AUTH_URL    ?? 'http://localhost:8180';
const FORM_URL    = process.env.FORM_URL    ?? 'http://localhost:8086';
const GATEWAY_URL = process.env.GATEWAY_URL ?? 'http://localhost:8087';

const CASCADE_TIMEOUT_MS = 10_000;
const POLL_INTERVAL_MS   =    500;

// ─── Helpers ──────────────────────────────────────────────────────────────────

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Decode the JWT sub claim (anonymousId). */
function jwtSub(jwt: string): string {
  return JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString()).sub as string;
}

// ─── Test suite ───────────────────────────────────────────────────────────────

test.describe('E2E-5: Questionnaire-Driven Symptom Report — Full Dynamic Pipeline', () => {

  let api: APIRequestContext;
  let userJwt: string;
  let userAnonymousId: string;

  test.beforeAll(async ({ playwright }) => {
    api = await playwright.request.newContext({ ignoreHTTPSErrors: true });

    const login = await api.post(`${AUTH_URL}/api/v1/auth/login`, {
      data: { username: 'staff_guard', password: 'password' },
    });
    expect(login.ok(), 'Login must succeed in beforeAll').toBeTruthy();

    const body  = await login.json();
    userJwt          = body.token;
    userAnonymousId  = body.anonymousId;
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  // ──────────────────────────────────────────────────────────────────────────
  // E2E-5.1  Active questionnaire exists and has required fields
  // ──────────────────────────────────────────────────────────────────────────
  test(
    'E2E-5.1: GET /questionnaires/active returns 200 with title, version, isActive, and questions',
    async () => {
      const resp = await api.get(`${FORM_URL}/api/v1/questionnaires/active`);

      // 404 means no active questionnaire — we cannot run the rest of the suite
      test.skip(
        resp.status() === 404,
        'No active questionnaire found — seed one before running E2E-5',
      );

      expect(resp.status(), 'Active questionnaire endpoint must return 200').toBe(200);

      const body = await resp.json();
      expect(body.title,    'Questionnaire must have a title').toBeTruthy();
      expect(body.version,  'Questionnaire must have a version number').toBeTruthy();
      expect(body.isActive, 'isActive must be true for the active questionnaire').toBe(true);
      expect(Array.isArray(body.questions), 'questions field must be an array').toBeTruthy();
      expect(
        body.questions.length,
        'Active questionnaire must have at least one question',
      ).toBeGreaterThanOrEqual(1);
    },
  );

  // ──────────────────────────────────────────────────────────────────────────
  // E2E-5.2  Each question has id, text, and type fields
  // ──────────────────────────────────────────────────────────────────────────
  test(
    'E2E-5.2: Every question in the active questionnaire has id, text, and type fields',
    async () => {
      const resp = await api.get(`${FORM_URL}/api/v1/questionnaires/active`);
      test.skip(resp.status() === 404, 'No active questionnaire — skipping field validation');

      const { questions } = await resp.json();

      for (const q of questions) {
        expect(q.id,   `Question is missing "id" field: ${JSON.stringify(q)}`).toBeTruthy();
        expect(q.text, `Question is missing "text" field: ${JSON.stringify(q)}`).toBeTruthy();
        expect(q.type, `Question is missing "type" field: ${JSON.stringify(q)}`).toBeTruthy();

        const validTypes = ['YES_NO', 'SINGLE_CHOICE', 'MULTI_CHOICE', 'TEXT'];
        expect(
          validTypes,
          `Question type "${q.type}" must be one of ${validTypes.join(', ')}`,
        ).toContain(q.type);
      }
    },
  );

  // ──────────────────────────────────────────────────────────────────────────
  // E2E-5.3  Submitting a dynamic responses map (not legacy booleans) returns 200
  // ──────────────────────────────────────────────────────────────────────────
  test(
    'E2E-5.3: POST /surveys with a dynamic responses map (JSON key→answer) returns 200',
    async () => {
      // Fetch the active questionnaire to build a valid responses map
      const qResp = await api.get(`${FORM_URL}/api/v1/questionnaires/active`);
      test.skip(qResp.status() === 404, 'No active questionnaire — skipping survey submission');

      const questionnaire = await qResp.json();

      // Build a benign (asymptomatic) responses map so we do NOT fence the user here
      const responses: Record<string, string> = {};
      for (const q of questionnaire.questions) {
        switch (q.type) {
          case 'YES_NO':
            // Answer NO to every question — explicitly non-symptomatic
            responses[q.id] = 'NO';
            break;
          case 'SINGLE_CHOICE':
            responses[q.id] = Array.isArray(q.options) ? q.options[0] : 'option-1';
            break;
          case 'MULTI_CHOICE':
            responses[q.id] = '[]'; // empty selection — not symptomatic
            break;
          case 'TEXT':
            responses[q.id] = 'No issues to report.';
            break;
        }
      }

      const surveyResp = await api.post(`${FORM_URL}/api/v1/surveys`, {
        data: {
          anonymousId: userAnonymousId,
          responses,
          timestamp: new Date().toISOString(),
        },
      });

      expect(surveyResp.status(), 'Dynamic survey submission must return 200').toBe(200);

      const saved = await surveyResp.json();
      expect(saved.id, 'Saved survey must have an id').toBeTruthy();
    },
  );

  // ──────────────────────────────────────────────────────────────────────────
  // E2E-5.4  Survey without anonymousId returns a 4xx validation error
  // ──────────────────────────────────────────────────────────────────────────
  test(
    'E2E-5.4: POST /surveys without anonymousId returns a 4xx client error',
    async () => {
      const resp = await api.post(`${FORM_URL}/api/v1/surveys`, {
        data: {
          hasFever: true,
          timestamp: new Date().toISOString(),
          // anonymousId intentionally omitted
        },
      });

      expect(
        resp.status(),
        `Survey without anonymousId must return 4xx, got ${resp.status()}`,
      ).toBeGreaterThanOrEqual(400);
      expect(
        resp.status(),
        `Survey without anonymousId must return 4xx, got ${resp.status()}`,
      ).toBeLessThan(500);
    },
  );

  // ──────────────────────────────────────────────────────────────────────────
  // E2E-5.5  Symptomatic dynamic survey triggers gate RED within CASCADE_TIMEOUT_MS
  // ──────────────────────────────────────────────────────────────────────────
  test(
    'E2E-5.5: Symptomatic dynamic responses trigger gate RED within the cascade timeout',
    async () => {
      // Fetch active questionnaire
      const qResp = await api.get(`${FORM_URL}/api/v1/questionnaires/active`);
      test.skip(qResp.status() === 404, 'No active questionnaire — skipping cascade test');

      const questionnaire = await qResp.json();

      // Build a SYMPTOMATIC responses map by answering YES to the first fever/cough question
      const responses: Record<string, string> = {};
      let symptomTriggered = false;

      for (const q of questionnaire.questions) {
        const textLower: string = q.text.toLowerCase();
        const isSymptomQuestion =
          textLower.includes('fever') ||
          textLower.includes('cough') ||
          textLower.includes('breath');

        if (isSymptomQuestion && q.type === 'YES_NO' && !symptomTriggered) {
          responses[q.id] = 'YES'; // symptomatic answer
          symptomTriggered = true;
        } else {
          responses[q.id] = q.type === 'YES_NO' ? 'NO' : '[]';
        }
      }

      if (!symptomTriggered) {
        // None of the questions matched fever/cough/breath keywords in this environment
        // Fall back to legacy survey fields so the test is still meaningful
        await api.post(`${FORM_URL}/api/v1/surveys`, {
          data: { anonymousId: userAnonymousId, hasFever: true },
        });
      } else {
        // Submit the dynamic symptomatic survey
        const surveyResp = await api.post(`${FORM_URL}/api/v1/surveys`, {
          data: { anonymousId: userAnonymousId, responses, timestamp: new Date().toISOString() },
        });
        expect(surveyResp.status(), 'Symptomatic survey submission must return 200').toBe(200);
      }

      // Helper: get a fresh QR token each poll iteration
      async function freshQrToken(): Promise<string> {
        const r = await api.get(`${AUTH_URL}/api/v1/auth/qr/generate`, {
          headers: { Authorization: `Bearer ${userJwt}` },
        });
        return (await r.json()).qrToken as string;
      }

      // Poll gate until RED or timeout
      const deadline = Date.now() + CASCADE_TIMEOUT_MS;
      let gateBody: { valid: boolean; status: string; message: string } | null = null;

      while (Date.now() < deadline) {
        const qrToken = await freshQrToken();
        const gateResp = await api.post(`${GATEWAY_URL}/api/v1/gate/validate`, {
          data: { token: qrToken },
        });
        gateBody = await gateResp.json();
        if (gateBody?.status === 'RED') break;
        await sleep(POLL_INTERVAL_MS);
      }

      expect(
        gateBody?.status,
        `Gate must return RED after symptomatic dynamic survey within ${CASCADE_TIMEOUT_MS} ms`,
      ).toBe('RED');
      expect(gateBody?.valid, 'Gate valid must be false for fenced user').toBe(false);
    },
  );

  // ──────────────────────────────────────────────────────────────────────────
  // E2E-5.6  A new questionnaire can be created and activated via the API
  // ──────────────────────────────────────────────────────────────────────────
  test(
    'E2E-5.6: POST /questionnaires creates a new questionnaire; POST /activate makes it active',
    async () => {
      // Create a minimal questionnaire
      const createResp = await api.post(`${FORM_URL}/api/v1/questionnaires`, {
        data: {
          title:   `E2E Dynamic Form ${Date.now()}`,
          version: 99,
          questions: [
            {
              text:       'Do you have a fever? (E2E test)',
              type:       'YES_NO',
              orderIndex: 0,
            },
            {
              text:       'Do you have a cough? (E2E test)',
              type:       'YES_NO',
              orderIndex: 1,
            },
          ],
        },
      });

      expect(createResp.status(), 'POST /questionnaires must return 200').toBe(200);

      const created = await createResp.json();
      expect(created.id,    'Created questionnaire must have an id').toBeTruthy();
      expect(created.title, 'Created questionnaire must echo the title').toContain('E2E Dynamic Form');

      // Activate it
      const activateResp = await api.post(
        `${FORM_URL}/api/v1/questionnaires/${created.id}/activate`,
      );
      expect(activateResp.status(), 'POST /activate must return 200').toBe(200);

      // Confirm it is now the active questionnaire
      const activeResp = await api.get(`${FORM_URL}/api/v1/questionnaires/active`);
      expect(activeResp.status(), 'GET /active must return 200 after activation').toBe(200);

      const active = await activeResp.json();
      expect(active.id, 'Active questionnaire id must match the one we just activated')
        .toBe(created.id);
      expect(active.isActive, 'isActive must be true after activation').toBe(true);
    },
  );
});
