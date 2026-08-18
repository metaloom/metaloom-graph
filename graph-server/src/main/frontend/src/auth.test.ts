import { describe, expect, it } from 'vitest';
import {
  ALL_DATABASES, canManageRoles, canManageUsers, canWrite, may, needsLogin, parseGrants, sessionCanWrite,
  sessionMay, unrestricted, type Session
} from './auth';

/**
 * The grant logic, which decides which buttons this UI draws.
 *
 * It is the only part of authentication a browser is allowed to reason about — the server makes every actual decision —
 * and it is the only part that can be tested here at all: vitest runs in `environment: 'node'` with no DOM, so the
 * components are Playwright's job.
 */
describe('parseGrants', () => {
  it('reads the form the server prints', () => {
    expect(parseGrants(['MATCH ON *', 'MERGE ON sales'])).toEqual([
      { privilege: 'MATCH', database: '*' },
      { privilege: 'MERGE', database: 'sales' }
    ]);
  });

  it('is tolerant about spacing and case', () => {
    expect(parseGrants(['  merge   on   sales  '])).toEqual([{ privilege: 'MERGE', database: 'sales' }]);
  });

  /**
   * A shape this bundle predates is dropped, not thrown on.
   *
   * A cached UI that white-screened on one unrecognised grant would be a far worse failure than one that quietly
   * under-reports what a user may do — the server is still enforcing either way.
   */
  it('drops entries it cannot read rather than failing', () => {
    expect(parseGrants(['MATCH ON *', 'nonsense', '', 'FUTURE_THING WITH sales'])).toEqual([
      { privilege: 'MATCH', database: '*' }
    ]);
    expect(parseGrants(undefined)).toEqual([]);
    expect(parseGrants(null)).toEqual([]);
  });
});

describe('may', () => {
  const grants = parseGrants(['MATCH ON *', 'MERGE ON sales', 'BACKUP ON hr']);

  it('matches a named database exactly', () => {
    expect(may(grants, 'MERGE', 'sales')).toBe(true);
    expect(may(grants, 'MERGE', 'hr')).toBe(false);
    expect(may(grants, 'BACKUP', 'hr')).toBe(true);
  });

  it('lets a wildcard reach every database', () => {
    expect(may(grants, 'MATCH', 'sales')).toBe(true);
    expect(may(grants, 'MATCH', 'anything-made-later')).toBe(true);
  });

  it('refuses a privilege that was never granted', () => {
    expect(may(grants, 'DELETE', 'sales')).toBe(false);
    expect(may(grants, 'DROP_DATABASE', 'sales')).toBe(false);
  });

  /** An empty database is the everywhere question, and only a wildcard answers it. */
  it('treats an empty database as the everywhere question', () => {
    expect(may(grants, 'MATCH', '')).toBe(true);
    expect(may(grants, 'MERGE', '')).toBe(false);
    expect(may(grants, 'MERGE', ALL_DATABASES)).toBe(false);
  });

  it('is case-insensitive about the privilege', () => {
    expect(may(grants, 'match', 'sales')).toBe(true);
  });
});

describe('canWrite', () => {
  /** Any one write clause enables the editor; the server refuses the individual clause. */
  it('is true for any write privilege on the database', () => {
    expect(canWrite(parseGrants(['MERGE ON sales']), 'sales')).toBe(true);
    expect(canWrite(parseGrants(['DELETE ON sales']), 'sales')).toBe(true);
    expect(canWrite(parseGrants(['SET ON *']), 'anything')).toBe(true);
  });

  it('is false for a reader', () => {
    expect(canWrite(parseGrants(['MATCH ON *', 'INTROSPECT ON *']), 'sales')).toBe(false);
  });

  it('is false on a database the write grant does not name', () => {
    expect(canWrite(parseGrants(['MERGE ON sales']), 'hr')).toBe(false);
  });
});

describe('the administrative privileges', () => {
  /**
   * The split that matters most, and the UI has to honour it.
   *
   * `MANAGE_USERS` is the helpdesk's — reset a password, disable a leaver. `MANAGE_ROLES` is the ability to grant
   * anything to anyone including oneself. Drawing the grant controls for somebody holding only the first would offer
   * them a button the server is about to refuse.
   */
  it('does not let user administration imply role administration', () => {
    const helpdesk = parseGrants(['MANAGE_USERS ON *']);
    expect(canManageUsers(helpdesk)).toBe(true);
    expect(canManageRoles(helpdesk)).toBe(false);
  });

  it('recognises a full administrator', () => {
    const admin = parseGrants(['MANAGE_USERS ON *', 'MANAGE_ROLES ON *']);
    expect(canManageUsers(admin)).toBe(true);
    expect(canManageRoles(admin)).toBe(true);
  });
});

describe('needsLogin', () => {
  const signedIn: Session = { authEnabled: true, authenticated: true, user: 'alice', grants: [] };
  const signedOut: Session = { authEnabled: true, authenticated: false, user: null, grants: [] };
  const noAuth: Session = { authEnabled: false, authenticated: false, user: 'anonymous', grants: [] };

  it('is true only when the server asks and nobody has answered', () => {
    expect(needsLogin(signedOut)).toBe(true);
    expect(needsLogin(signedIn)).toBe(false);
  });

  /**
   * The condition that is easy to forget.
   *
   * On a server started with `--no-auth` there is nothing to log in to, and `POST /api/login` answers 400. A form
   * there would be a dead end.
   */
  it('is false on a server with authentication off', () => {
    expect(needsLogin(noAuth)).toBe(false);
  });

  it('is false before the server has been asked', () => {
    expect(needsLogin(null)).toBe(false);
  });
});

describe('the unrestricted case', () => {
  const noAuth: Session = { authEnabled: false, authenticated: false, user: 'anonymous', grants: [] };
  const reader: Session = { authEnabled: true, authenticated: true, user: 'r', grants: parseGrants(['MATCH ON *']) };

  /**
   * With authentication off the server reports no grants and permits everything.
   *
   * So asking `may()` would answer no to all of it and produce a UI that refuses what the server would happily do —
   * which is why the session-aware wrappers exist at all.
   */
  it('permits everything when authentication is off', () => {
    expect(unrestricted(noAuth)).toBe(true);
    expect(sessionMay(noAuth, 'DROP_DATABASE', 'sales')).toBe(true);
    expect(sessionCanWrite(noAuth, 'sales')).toBe(true);
    expect(may(noAuth.grants, 'DROP_DATABASE', 'sales')).toBe(false);
  });

  it('still checks grants when authentication is on', () => {
    expect(unrestricted(reader)).toBe(false);
    expect(sessionMay(reader, 'MATCH', 'sales')).toBe(true);
    expect(sessionMay(reader, 'DELETE', 'sales')).toBe(false);
    expect(sessionCanWrite(reader, 'sales')).toBe(false);
  });

  it('permits nothing before the server has been asked', () => {
    expect(sessionMay(null, 'MATCH', 'sales')).toBe(false);
    expect(sessionCanWrite(null, 'sales')).toBe(false);
  });
});
