import { expect, test } from '@playwright/test';

/**
 * The login screen, against a real server.
 *
 * This project's e2e suite deliberately has no mocks: the browser talks to a Vert.x server running the real Cypher
 * engine over a store on disk. That matters more here than anywhere else, because most of what a login is depends on
 * things a mock cannot have — an HttpOnly cookie the page cannot read, a session the server actually remembers, and a
 * grant set that decides what is drawn.
 *
 * These specs run in their own Playwright project with no saved `storageState`, because every other spec is signed in
 * before it starts.
 */

const PASSWORD = process.env.GRAPH_INITIAL_PASSWORD ?? 'demo-password';

test.describe('login', () => {
  test('the form is shown when nobody is signed in', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('login-screen')).toBeVisible();
    await expect(page.getByTestId('login-user')).toBeVisible();
    await expect(page.getByTestId('login-password')).toBeVisible();
    // And the application is not behind it.
    await expect(page.getByTestId('query-input')).toHaveCount(0);
  });

  test('the submit button is disabled until both fields are filled', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('login-submit')).toBeDisabled();
    await page.getByTestId('login-user').fill('admin');
    await expect(page.getByTestId('login-submit')).toBeDisabled();
    await page.getByTestId('login-password').fill(PASSWORD);
    await expect(page.getByTestId('login-submit')).toBeEnabled();
  });

  test('a wrong password shows an error and does not sign in', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('login-user').fill('admin');
    await page.getByTestId('login-password').fill('definitely-not-the-password');
    await page.getByTestId('login-submit').click();

    await expect(page.getByTestId('login-error')).toBeVisible();
    await expect(page.getByTestId('login-screen')).toBeVisible();

    // No session cookie was set, which is the assertion that actually matters: an error message with a cookie behind
    // it would be a login that succeeded and said otherwise.
    const cookies = await page.context().cookies();
    expect(cookies.filter(cookie => cookie.name === 'graph_session')).toHaveLength(0);
  });

  test('an unknown user is refused the same way', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('login-user').fill('nobody-at-all');
    await page.getByTestId('login-password').fill(PASSWORD);
    await page.getByTestId('login-submit').click();
    await expect(page.getByTestId('login-error')).toBeVisible();
  });

  test('the right password reaches the graph', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('login-user').fill('admin');
    await page.getByTestId('login-password').fill(PASSWORD);
    await page.getByTestId('login-submit').click();

    await expect(page.getByTestId('query-input')).toBeVisible();
    await expect(page.getByTestId('signed-in-as')).toHaveText('admin');
    await expect(page.getByTestId('login-screen')).toHaveCount(0);
  });

  /** The cookie is HttpOnly, so the page cannot read its own token. */
  test('the session token is not reachable from JavaScript', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('login-user').fill('admin');
    await page.getByTestId('login-password').fill(PASSWORD);
    await page.getByTestId('login-submit').click();
    await expect(page.getByTestId('query-input')).toBeVisible();

    const readable = await page.evaluate(() => document.cookie);
    expect(readable).not.toContain('graph_session');

    const cookie = (await page.context().cookies()).find(candidate => candidate.name === 'graph_session');
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.sameSite).toBe('Strict');
  });

  test('a reload keeps the session', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('login-user').fill('admin');
    await page.getByTestId('login-password').fill(PASSWORD);
    await page.getByTestId('login-submit').click();
    await expect(page.getByTestId('query-input')).toBeVisible();

    await page.reload();

    await expect(page.getByTestId('query-input')).toBeVisible();
    await expect(page.getByTestId('login-screen')).toHaveCount(0);
  });

  test('signing out returns to the form', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('login-user').fill('admin');
    await page.getByTestId('login-password').fill(PASSWORD);
    await page.getByTestId('login-submit').click();
    await expect(page.getByTestId('query-input')).toBeVisible();

    await page.getByTestId('sign-out').click();

    await expect(page.getByTestId('login-screen')).toBeVisible();
    await expect(page.getByTestId('query-input')).toHaveCount(0);
  });
});

test.describe('a user with fewer privileges', () => {
  /**
   * Created over the API as the administrator, never by test-only server behaviour.
   *
   * A fixture the server only has in tests would prove that the fixture works. Creating it the way an administrator
   * would means the route, the privilege check on it and the grant model are all exercised on the way in.
   */
  test('sees the editor with writes disabled', async ({ page, request }) => {
    const login = await request.post('api/login', { data: { user: 'admin', password: PASSWORD } });
    expect(login.ok()).toBeTruthy();

    // Ignore "already exists" on a rerun: the demo store is reused between runs by design.
    await request.post('api/roles', { data: { name: 'e2e-reader' } });
    await request.post('api/roles/e2e-reader/grants', { data: { grant: 'MATCH ON *' } });
    await request.post('api/roles/e2e-reader/grants', { data: { grant: 'INTROSPECT ON *' } });
    await request.post('api/users', {
      data: { name: 'e2e-reader', password: 'reader-password', roles: ['e2e-reader'] }
    });
    // Whether it was just created or was already there, make sure the password is the one this spec uses.
    await request.post('api/users/e2e-reader/password', { data: { password: 'reader-password' } });

    await page.goto('/');
    await page.getByTestId('login-user').fill('e2e-reader');
    await page.getByTestId('login-password').fill('reader-password');
    await page.getByTestId('login-submit').click();

    await expect(page.getByTestId('query-input')).toBeVisible();
    await expect(page.getByTestId('signed-in-as')).toHaveText('e2e-reader');

    // The subtitle says read-only, because the grant says so even though the server was started with --allow-write.
    await expect(page.locator('.subtitle')).toContainText('read-only');

    // And no administration, because a reader holds neither MANAGE_USERS nor MANAGE_ROLES.
    await expect(page.getByTestId('toggle-admin')).toHaveCount(0);
  });

  test('an administrator sees the users panel', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('login-user').fill('admin');
    await page.getByTestId('login-password').fill(PASSWORD);
    await page.getByTestId('login-submit').click();
    await expect(page.getByTestId('query-input')).toBeVisible();

    await page.getByTestId('toggle-admin').click();

    await expect(page.getByTestId('user-panel')).toBeVisible();
    await expect(page.getByTestId('user-list')).toContainText('admin');
    await expect(page.getByTestId('role-list')).toContainText('admin');
  });
});
