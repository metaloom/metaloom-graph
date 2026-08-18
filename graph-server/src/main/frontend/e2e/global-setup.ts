import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { request } from '@playwright/test';

/**
 * Signs in once, and saves the cookie for every spec that is not about signing in.
 *
 * Authentication is on by default, so without this each of the forty-odd existing specs would have to get past a login
 * screen — forty edits, and forty places for the login to be the thing that broke. A saved `storageState` leaves them
 * exactly as they were written.
 *
 * The password comes from the environment with the same default `tools/demo-server.sh` uses, because that script is
 * what Playwright starts and the two have to agree on it.
 */
const STATE = './e2e/.auth/state.json';

export default async function globalSetup() {
  const port = process.env.E2E_PORT ?? '8099';
  const base = `http://localhost:${port}`;
  const user = process.env.E2E_USER ?? 'admin';
  const password = process.env.GRAPH_INITIAL_PASSWORD ?? 'demo-password';

  const context = await request.newContext({ baseURL: base });
  try {
    const who = await context.get('api/whoami');
    if (!who.ok()) {
      throw new Error(`GET api/whoami answered ${who.status()}; is the demo server up?`);
    }
    const session = await who.json();

    // A server started with GRAPH_AUTH_ENABLED=false has nothing to sign in to. Save an empty state rather than
    // failing, so `GRAPH_AUTH_ENABLED=false npm run e2e` still works for somebody poking at the UI.
    if (session.authEnabled === false) {
      save({ cookies: [], origins: [] });
      return;
    }

    const login = await context.post('api/login', { data: { user, password } });
    if (!login.ok()) {
      throw new Error(
        `Signing in as '${user}' answered ${login.status()}. The suite needs the password the server was started `
        + `with: set GRAPH_INITIAL_PASSWORD for both tools/demo-server.sh and this setup, or delete `
        + `${'target/e2e-store/.auth'} to let it be bootstrapped again.`
      );
    }
    save(await context.storageState());
  } finally {
    await context.dispose();
  }
}

function save(state: unknown) {
  mkdirSync(dirname(STATE), { recursive: true });
  writeFileSync(STATE, JSON.stringify(state, null, 2));
}
