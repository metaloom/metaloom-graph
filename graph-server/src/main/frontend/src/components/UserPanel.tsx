import { useCallback, useEffect, useState } from 'react';
import {
  createRole, createUser, dropRole, dropUser, fetchRoles, fetchUsers, grantToRole, revokeFromRole, setPassword,
  type ApiRole, type ApiUser
} from '../api';
import type { Session } from '../auth';
import { canManageRoles, canManageUsers, unrestricted } from '../auth';

/**
 * Users, roles and grants, for whoever may administer them.
 *
 * Two privileges gate two halves of this panel, and the split is not cosmetic: `MANAGE_USERS` is the helpdesk's — reset
 * a forgotten password, disable a leaver — while `MANAGE_ROLES` is the ability to grant anything to anyone, including
 * oneself. Somebody trusted with the first is not thereby trusted with the second, so the second half is not drawn for
 * them. The server refuses either way; this is so the buttons agree with it.
 */
export function UserPanel({ session }: { session: Session }) {
  const [users, setUsers] = useState<ApiUser[] | null>(null);
  const [roles, setRoles] = useState<ApiRole[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const mayUsers = unrestricted(session) || canManageUsers(session.grants);
  const mayRoles = unrestricted(session) || canManageRoles(session.grants);

  const reload = useCallback(async () => {
    setError(null);
    try {
      if (mayUsers) {
        setUsers(await fetchUsers());
      }
      if (mayRoles) {
        setRoles(await fetchRoles());
      }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
  }, [mayUsers, mayRoles]);

  useEffect(() => { void reload(); }, [reload]);

  async function act(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      await reload();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="userPanel" data-testid="user-panel">
      <h2>Users and roles</h2>
      {error !== null && <p className="panelError" data-testid="admin-error" role="alert">{error}</p>}

      {mayUsers && (
        <div className="panelSection">
          <h3>Users</h3>
          <ul data-testid="user-list">
            {(users ?? []).map(user => (
              <li key={user.name} data-testid={`user-${user.name}`}>
                <span className="userName">{user.name}</span>
                {!user.enabled && <span className="disabledBadge"> (disabled)</span>}
                {user.roles.length > 0 && <span className="userRoles"> {user.roles.join(', ')}</span>}
                <button
                  data-testid={`reset-${user.name}`}
                  disabled={busy}
                  onClick={() => {
                    const chosen = window.prompt(`New password for ${user.name}`);
                    if (chosen !== null && chosen !== '') {
                      void act(() => setPassword(user.name, chosen));
                    }
                  }}
                >Reset password</button>
                <button
                  data-testid={`drop-user-${user.name}`}
                  disabled={busy}
                  onClick={() => void act(() => dropUser(user.name))}
                >Remove</button>
              </li>
            ))}
          </ul>
          <NewUser roles={roles ?? []} busy={busy}
            onCreate={(name, password, chosen) => void act(() => createUser(name, password, chosen))} />
        </div>
      )}

      {mayRoles && (
        <div className="panelSection">
          <h3>Roles</h3>
          <ul data-testid="role-list">
            {(roles ?? []).map(role => (
              <li key={role.name} data-testid={`role-${role.name}`}>
                <span className="roleName">{role.name}</span>
                <ul className="grantList">
                  {role.grants.map(grant => (
                    <li key={grant}>
                      <code>{grant}</code>
                      <button
                        data-testid={`revoke-${role.name}-${grant.replace(/\s+/g, '-')}`}
                        disabled={busy}
                        onClick={() => void act(() => revokeFromRole(role.name, grant))}
                      >Revoke</button>
                    </li>
                  ))}
                </ul>
                <NewGrant role={role.name} busy={busy}
                  onGrant={grant => void act(() => grantToRole(role.name, grant))} />
                <button
                  data-testid={`drop-role-${role.name}`}
                  disabled={busy}
                  onClick={() => void act(() => dropRole(role.name))}
                >Remove role</button>
              </li>
            ))}
          </ul>
          <NewRole busy={busy} onCreate={name => void act(() => createRole(name))} />
        </div>
      )}

      {!mayUsers && !mayRoles && (
        <p className="panelHint">You have not been granted user or role administration.</p>
      )}
    </section>
  );
}

function NewUser(
  { roles, busy, onCreate }:
  { roles: ApiRole[]; busy: boolean; onCreate: (name: string, password: string, roles: string[]) => void }
) {
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [chosen, setChosen] = useState<string[]>([]);
  return (
    <div className="newRow">
      <input data-testid="new-user-name" placeholder="name" value={name}
        onChange={event => setName(event.target.value)} />
      <input data-testid="new-user-password" placeholder="password" type="password" value={password}
        onChange={event => setPassword(event.target.value)} />
      <select
        data-testid="new-user-roles"
        multiple
        value={chosen}
        onChange={event => setChosen(Array.from(event.target.selectedOptions, option => option.value))}
      >
        {roles.map(role => <option key={role.name} value={role.name}>{role.name}</option>)}
      </select>
      <button
        data-testid="new-user-create"
        disabled={busy || name === '' || password === ''}
        onClick={() => { onCreate(name, password, chosen); setName(''); setPassword(''); setChosen([]); }}
      >Add user</button>
    </div>
  );
}

function NewRole({ busy, onCreate }: { busy: boolean; onCreate: (name: string) => void }) {
  const [name, setName] = useState('');
  return (
    <div className="newRow">
      <input data-testid="new-role-name" placeholder="role name" value={name}
        onChange={event => setName(event.target.value)} />
      <button data-testid="new-role-create" disabled={busy || name === ''}
        onClick={() => { onCreate(name); setName(''); }}>Add role</button>
    </div>
  );
}

/**
 * A grant, typed in the form the server prints.
 *
 * `MERGE ON sales` rather than two fields, so what `metaloom-graph-cli auth list` shows can be pasted straight in and a
 * refusal names the same thing the listing did.
 */
function NewGrant({ role, busy, onGrant }: { role: string; busy: boolean; onGrant: (grant: string) => void }) {
  const [grant, setGrant] = useState('');
  return (
    <div className="newRow">
      <input
        data-testid={`new-grant-${role}`}
        placeholder="MATCH ON *"
        value={grant}
        onChange={event => setGrant(event.target.value)}
      />
      <button data-testid={`grant-${role}`} disabled={busy || grant === ''}
        onClick={() => { onGrant(grant); setGrant(''); }}>Grant</button>
    </div>
  );
}
