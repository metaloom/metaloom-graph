/**
 * What the signed-in user may do, as pure functions over the grant strings the server sent.
 *
 * The grants arrive as text — `"MERGE ON sales"`, `"MATCH ON *"` — because the server is the only thing that makes an
 * authorization decision and a browser has no business reimplementing one. What this module does is narrower and is
 * genuinely the UI's job: decide which buttons to draw. A UI that offers an editor whose every write comes back 403 is
 * worse than one that greys it out and says why.
 *
 * It is a separate module with no React in it because the vitest setup here runs in `environment: 'node'` with no DOM
 * ([vite.config.ts]). Pure logic in its own file is the part that can actually be tested; everything else is covered by
 * Playwright against a real server.
 */

/** Every database, present and future. Matches `Grant.ALL_DATABASES` on the server. */
export const ALL_DATABASES = '*';

export interface Grant {
  privilege: string;
  database: string;
}

/**
 * Parses `"MERGE ON sales"`.
 *
 * Unparseable entries are dropped rather than thrown on. A newer server may send a shape this bundle predates, and a
 * cached UI that white-screened on one unknown grant would be a worse failure than one that quietly under-reports what
 * a user may do — the server is still enforcing either way.
 */
export function parseGrants(grants: readonly string[] | undefined | null): Grant[] {
  if (!grants) {
    return [];
  }
  const parsed: Grant[] = [];
  for (const text of grants) {
    const match = /^(\S+)\s+ON\s+(\S+)$/i.exec(String(text).trim());
    if (match) {
      parsed.push({ privilege: match[1].toUpperCase(), database: match[2] });
    }
  }
  return parsed;
}

/**
 * Whether these grants permit `privilege` on `database`.
 *
 * An empty database means the question is not about one — the same convention the server's
 * `may(privilege, ALL_DATABASES)` uses — and is answered only by a wildcard grant.
 */
export function may(grants: readonly Grant[], privilege: string, database: string): boolean {
  const wanted = privilege.toUpperCase();
  const target = database === '' ? ALL_DATABASES : database;
  return grants.some(
    (grant) => grant.privilege === wanted && (grant.database === ALL_DATABASES || grant.database === target)
  );
}

/**
 * Whether the editor should offer to run a write.
 *
 * Any one of the write clauses is enough: the editor is one box and a user who may `MERGE` but not `DELETE` still needs
 * it enabled. The server refuses the individual clause, which is where that distinction belongs.
 */
export function canWrite(grants: readonly Grant[], database: string): boolean {
  return ['CREATE', 'MERGE', 'SET', 'REMOVE', 'DELETE'].some((privilege) => may(grants, privilege, database));
}

export function canManageUsers(grants: readonly Grant[]): boolean {
  return may(grants, 'MANAGE_USERS', ALL_DATABASES);
}

export function canManageRoles(grants: readonly Grant[]): boolean {
  return may(grants, 'MANAGE_ROLES', ALL_DATABASES);
}

/**
 * What the UI knows about the current session.
 *
 * `authEnabled` false is a server started with `--no-auth`: there is no login page to draw and no user to name, and it
 * is deliberately distinguishable from "authentication is on and nobody is signed in yet".
 */
export interface Session {
  authEnabled: boolean;
  authenticated: boolean;
  user: string | null;
  grants: Grant[];
}

/**
 * Whether a login form should be shown.
 *
 * Both conditions, and the second is the one that is easy to forget: on a server with authentication off there is
 * nothing to log in to, and showing a form there would be a dead end.
 */
export function needsLogin(session: Session | null): boolean {
  return session !== null && session.authEnabled && !session.authenticated;
}

/**
 * Whether every affordance should be offered without checking a grant.
 *
 * True only with authentication off, where the server permits everything and reports no grants — so asking `may()`
 * would answer no to all of it and leave a UI that refuses what the server would allow.
 */
export function unrestricted(session: Session | null): boolean {
  return session !== null && !session.authEnabled;
}

/** `may()` against a session, taking the unrestricted case into account. */
export function sessionMay(session: Session | null, privilege: string, database: string): boolean {
  if (session === null) {
    return false;
  }
  if (unrestricted(session)) {
    return true;
  }
  return may(session.grants, privilege, database);
}

/** `canWrite()` against a session, taking the unrestricted case into account. */
export function sessionCanWrite(session: Session | null, database: string): boolean {
  if (session === null) {
    return false;
  }
  return unrestricted(session) || canWrite(session.grants, database);
}
