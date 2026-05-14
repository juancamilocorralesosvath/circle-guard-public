import { defineConfig } from '@playwright/test';

/**
 * Scope Playwright to API E2E specs only. Jest colocates unit tests under
 * components with a ".test.tsx" suffix; the default Playwright search would
 * pick those up and crash (RN code is not meant for the Playwright runner).
 */
export default defineConfig({
  testDir: './e2e/tests',
  fullyParallel: false,
  workers: 2,
  timeout: 60000,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: [['list'], ['junit', { outputFile: 'test-results/results.xml' }]],
  use: {
    trace: 'on-first-retry',
  },
});
