import { defineConfig, devices } from '@playwright/test';

const PORT = Number(process.env.E2E_PORT ?? 8099);

/**
 * End-to-end, against the real server over a real store.
 *
 * <h3>No mocks</h3>
 *
 * The browser talks to a Vert.x server running the actual Cypher engine over a store on disk, filled by the shipped
 * `--demo` flag.
 * Everything these tests are about — that a syntax error carries a caret, that a lightweight edge has no properties,
 * that a 64-bit id survives the trip — is a property of the whole path. A mocked API would assert that the UI renders
 * the fixture someone wrote for it, which is the one thing that cannot be wrong.
 *
 * Starting the server is left to `tools/demo-server.sh`, so the same command a human uses to look at the UI is the
 * one the suite drives.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: {
    // The force layout is still moving when a test starts clicking, and Playwright waits for an element to stop
    // before acting on it. Ten seconds is a settling simulation, not a hang.
    timeout: 10_000
  },
  reporter: process.env.CI ? [['github'], ['list']] : [['list']],
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  /**
   * Signed in once, in globalSetup, and reused by every spec.
   *
   * Authentication is on by default now, so without this every one of the forty-odd existing specs would have to get
   * past a login screen. A saved storageState keeps them exactly as they were.
   */
  globalSetup: './e2e/global-setup.ts',
  projects: [
    {
      name: 'chromium',
      testIgnore: /login\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], storageState: './e2e/.auth/state.json' }
    },
    {
      // The login specs need a browser that has NOT signed in, so they get their own project with no storageState.
      name: 'login',
      testMatch: /login\.spec\.ts/,
      use: { ...devices['Desktop Chrome'] }
    }
  ],
  webServer: {
    command: `bash ../../../../tools/demo-server.sh ../../../target/e2e-store ${PORT}`,
    // /api/whoami rather than /api/stats: Playwright treats a status of 400 or above as not-ready, and /api/stats now
    // requires INTROSPECT. Probing it would make an auth-enabled server never report ready and burn the whole 240s
    // budget before failing. whoami is deliberately outside the auth filter for exactly this kind of reason.
    url: `http://localhost:${PORT}/api/whoami`,
    reuseExistingServer: !process.env.CI,
    // A cold start compiles the module first.
    timeout: 240_000,
    stdout: 'pipe'
  }
});
