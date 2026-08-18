import { useState } from 'react';
import { ApiError, login } from '../api';
import type { Session } from '../auth';
import { parseGrants } from '../auth';

/**
 * The login screen, rendered instead of the application rather than over it.
 *
 * There is no router in this app — navigation is a `useState` tab in `App.tsx` — so this is a conditional render. That
 * is also the honest shape for it: while nobody is signed in there is no graph to draw behind a dialog, and every API
 * call the app would make on mount comes back 401.
 */
export function LoginForm({ onSignedIn }: { onSignedIn: (session: Session) => void }) {
  const [user, setUser] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const who = await login(user, password);
      onSignedIn({
        authEnabled: true,
        authenticated: true,
        user: who.user,
        grants: parseGrants(who.grants)
      });
    } catch (failure) {
      // The server answers one message for a wrong password, an unknown user and a disabled account, so there is
      // nothing to distinguish here and nothing worth adding — repeating it is the most this screen can honestly say.
      setError(failure instanceof ApiError ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="loginScreen" data-testid="login-screen">
      <form className="loginCard" onSubmit={event => void submit(event)}>
        <h1>MetaLoom // Graph</h1>
        <p className="loginHint">Sign in to query this server.</p>

        <label htmlFor="login-user">User</label>
        <input
          id="login-user"
          data-testid="login-user"
          autoComplete="username"
          autoFocus
          value={user}
          onChange={event => setUser(event.target.value)}
        />

        <label htmlFor="login-password">Password</label>
        <input
          id="login-password"
          data-testid="login-password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={event => setPassword(event.target.value)}
        />

        <button type="submit" data-testid="login-submit" disabled={busy || user === '' || password === ''}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>

        {error !== null && (
          <p className="loginError" data-testid="login-error" role="alert">{error}</p>
        )}

        <p className="loginFooter">
          A first start prints a generated password for <code>admin</code> to the server's standard output. If it is
          lost, <code>metaloom-graph-cli auth reset-admin --path=&lt;root&gt;</code> replaces it.
        </p>
      </form>
    </div>
  );
}
