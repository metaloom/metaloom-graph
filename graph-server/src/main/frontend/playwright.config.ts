import { defineConfig, devices } from '@playwright/test';

const PORT = Number(process.env.E2E_PORT ?? 8099);

/**
 * End-to-end, against the real server over a real store.
 *
 * <h3>No mocks</h3>
 *
 * The browser talks to a Vert.x server running the actual Cypher engine over a store on disk, seeded by DemoServer.
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
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `bash ../../../../tools/demo-server.sh ../../../target/e2e-store ${PORT}`,
    url: `http://localhost:${PORT}/api/stats`,
    reuseExistingServer: !process.env.CI,
    // A cold start compiles the module first.
    timeout: 240_000,
    stdout: 'pipe'
  }
});
