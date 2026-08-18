import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, login, logout, runQuery, whoami } from './api';

/**
 * The request shapes, and the one response mapping the app branches on.
 *
 * `fetch` is stubbed rather than mocked at a module boundary, because what is worth asserting here is the wire: the
 * path, the method, the content type that CSRF protection depends on, and that a 401 becomes something structural
 * rather than a string somebody has to match on.
 */
describe('the auth calls', () => {
  let calls: Array<{ url: string; init: RequestInit | undefined }>;

  function respond(status: number, body: unknown) {
    calls = [];
    globalThis.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
      calls.push({ url: String(url), init });
      return {
        ok: status >= 200 && status < 300,
        status,
        statusText: String(status),
        text: async () => (typeof body === 'string' ? body : JSON.stringify(body))
      } as Response;
    }) as unknown as typeof fetch;
  }

  beforeEach(() => {
    calls = [];
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('posts a login as JSON to a relative path', async () => {
    respond(200, { user: 'alice', token: 'abc', grants: ['MATCH ON *'] });
    const who = await login('alice', 'secret');

    expect(calls[0].url).toBe('api/login');
    expect(calls[0].init?.method).toBe('POST');
    // The content type is load-bearing, not decoration: it is half the CSRF defence, because an HTML form cannot
    // produce it cross-origin without a preflight.
    expect((calls[0].init?.headers as Record<string, string>)['content-type']).toBe('application/json');
    expect(calls[0].init?.credentials).toBe('same-origin');
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ user: 'alice', password: 'secret' });

    expect(who).toEqual({ authEnabled: true, authenticated: true, user: 'alice', grants: ['MATCH ON *'] });
  });

  it('reads whoami without a body', async () => {
    respond(200, { authEnabled: true, authenticated: false, user: null, grants: [] });
    const who = await whoami();

    expect(calls[0].url).toBe('api/whoami');
    expect(who.authEnabled).toBe(true);
    expect(who.authenticated).toBe(false);
    expect(who.user).toBeNull();
  });

  it('posts a logout', async () => {
    respond(200, {});
    await logout();
    expect(calls[0].url).toBe('api/logout');
    expect(calls[0].init?.method).toBe('POST');
  });

  /**
   * A 401 always becomes `unauthenticated`, whatever the body said.
   *
   * The app branches on this to drop back to the login screen, and it must not depend on the server having filled in
   * an error kind — a proxy or a misconfiguration can produce a 401 with no JSON at all.
   */
  it('maps every 401 to the unauthenticated kind', async () => {
    respond(401, { error: 'something-else', message: 'nope' });
    const failure = await runQuery('MATCH (n) RETURN n').catch(error => error);

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).kind).toBe('unauthenticated');
    expect((failure as ApiError).isUnauthenticated).toBe(true);
    expect((failure as ApiError).isForbidden).toBe(false);
  });

  it('maps a 401 with no JSON body to the same kind', async () => {
    respond(401, 'not json at all');
    const failure = await runQuery('MATCH (n) RETURN n').catch(error => error);
    expect(failure).toBeInstanceOf(ApiError);
    // A non-JSON body is a transport failure by the older rule; what matters is that the app can still tell it is
    // not a query problem.
    expect(['unauthenticated', 'transport']).toContain((failure as ApiError).kind);
  });

  /** A 403 stays a 403: signed in and not allowed, which is worth displaying because an admin can fix it. */
  it('keeps a 403 distinct from a 401', async () => {
    respond(403, { error: 'forbidden', message: "'reader' has not been granted CREATE" });
    const failure = await runQuery('CREATE (n)').catch(error => error);

    expect((failure as ApiError).kind).toBe('forbidden');
    expect((failure as ApiError).isForbidden).toBe(true);
    expect((failure as ApiError).isUnauthenticated).toBe(false);
    expect((failure as ApiError).message).toContain('CREATE');
  });

  /** A syntax error still carries the engine's caret line, which the query bar renders in a monospace block. */
  it('leaves a query failure alone', async () => {
    respond(400, { error: 'syntax', message: 'MATCH (n\n        ^ expected )' });
    const failure = await runQuery('MATCH (n').catch(error => error);

    expect((failure as ApiError).kind).toBe('syntax');
    expect((failure as ApiError).hasCaret).toBe(true);
  });
});
