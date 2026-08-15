import { useState } from 'react';
import type { DatabaseList } from '../api';

/**
 * Which database the rest of the UI is looking at.
 *
 * Switching is not a filter: every query, the schema sidebar and the canvas belong to one database, so a switch
 * clears the canvas rather than merging two graphs whose ids mean different things. An id is only unique within a
 * store, so two databases can legitimately hand back the same id for different nodes — merging them would draw one
 * circle for two records and there would be nothing on screen to say so.
 */
export function DatabasePicker({
  databases,
  current,
  busy,
  onSelect,
  onCreate,
  onDrop
}: {
  databases: DatabaseList | null;
  current: string;
  busy: boolean;
  onSelect: (name: string) => void;
  onCreate: (name: string) => void;
  onDrop: (name: string) => void;
}) {
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  const names = databases?.databases ?? [];
  const canDrop = (databases?.allowDrop ?? false) && names.length > 1;

  /**
   * The same rule the server enforces, applied before the request rather than instead of it.
   *
   * The server validates too — it has to, since it is reachable without this page — but a caller who is told at the
   * keystroke does not have to send a request to find out that a space is not allowed.
   */
  function validate(name: string): string | null {
    if (name.length === 0) {
      return 'A name is required';
    }
    if (name.length > 64) {
      return 'At most 64 characters';
    }
    if (!/^[A-Za-z0-9_][A-Za-z0-9_-]*$/.test(name)) {
      return "Letters, digits, '-' and '_' only, and not starting with '-'";
    }
    if (names.some((entry) => entry.name === name)) {
      return `'${name}' already exists`;
    }
    return null;
  }

  function submit() {
    const name = draft.trim();
    const failure = validate(name);
    setProblem(failure);
    if (failure) {
      return;
    }
    onCreate(name);
    setDraft('');
    setCreating(false);
  }

  return (
    <div className="database-picker">
      <label htmlFor="database-select">Database</label>
      <select
        id="database-select"
        value={current}
        disabled={busy || names.length === 0}
        onChange={(event) => onSelect(event.target.value)}
      >
        {names.map((entry) => (
          <option key={entry.name} value={entry.name}>
            {entry.name}
            {entry.open && entry.nodes !== undefined ? ` · ${entry.nodes.toLocaleString()} nodes` : ''}
            {entry.default ? ' · default' : ''}
          </option>
        ))}
      </select>

      {creating ? (
        <span className="database-create">
          <input
            autoFocus
            value={draft}
            placeholder="new-database"
            aria-label="New database name"
            onChange={(event) => {
              setDraft(event.target.value);
              setProblem(null);
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                submit();
              } else if (event.key === 'Escape') {
                setCreating(false);
                setDraft('');
                setProblem(null);
              }
            }}
          />
          <button type="button" onClick={submit} disabled={busy}>
            Create
          </button>
          <button
            type="button"
            className="ghost"
            onClick={() => {
              setCreating(false);
              setDraft('');
              setProblem(null);
            }}
          >
            Cancel
          </button>
        </span>
      ) : (
        <button type="button" title="Create a database" onClick={() => setCreating(true)} disabled={busy}>
          + New
        </button>
      )}

      {canDrop && !creating && (
        <button
          type="button"
          className="danger"
          title={`Delete '${current}' and everything in it`}
          disabled={busy}
          onClick={() => {
            // Typing the name, not an OK button. This unlinks a whole directory in one request — including databases
            // no query addressed — which is why it stays behind its own flag even on a server that accepts writes.
            const typed = window.prompt(
              `Deleting '${current}' removes the database and everything in it, permanently.\n\n`
                + `Type its name to confirm:`
            );
            if (typed === current) {
              onDrop(current);
            }
          }}
        >
          Drop
        </button>
      )}

      {problem && <span className="database-problem">{problem}</span>}
    </div>
  );
}
