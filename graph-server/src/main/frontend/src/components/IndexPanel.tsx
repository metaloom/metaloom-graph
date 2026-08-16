import { useState } from 'react';
import type { IndexList } from '../api';

/**
 * The declared composite indexes, and the form that declares one.
 *
 * A separate panel from the schema above it because they answer differently-priced questions. The schema is a scan of
 * the data, cached for a minute; this is a read of one catalog page and is exact right now. They also differ in kind:
 * labels and relationship types are *what is in the store*, and an index is *a decision someone made about it*.
 *
 * The panel exists because that decision has no other surface. There is no `CREATE INDEX` in this Cypher subset — the
 * planner uses a declared index and the query language cannot declare one — so before this, `createIndex` was
 * reachable only from Java.
 */
export function IndexPanel({
  indexes,
  busy,
  error,
  onCreate,
  onDrop,
  onPick
}: {
  indexes: IndexList | null;
  busy: boolean;
  error: string | null;
  onCreate: (label: string, key: string, unique: boolean) => void;
  onDrop: (label: string, key: string) => void;
  onPick: (label: string, key: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [label, setLabel] = useState('');
  const [key, setKey] = useState('');
  const [unique, setUnique] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const declared = indexes?.indexes ?? [];
  const allowWrite = indexes?.allowWrite ?? false;

  function submit() {
    const trimmedLabel = label.trim();
    const trimmedKey = key.trim();
    if (trimmedLabel.length === 0 || trimmedKey.length === 0) {
      setProblem('A label and a property key are both required');
      return;
    }
    if (declared.some(index => index.label === trimmedLabel && index.key === trimmedKey)) {
      // The server would accept this and change nothing — createIndex is idempotent — but answering "declared" to a
      // click that declared nothing is the sort of thing that makes a user click it again.
      setProblem(`:${trimmedLabel}.${trimmedKey} is already declared`);
      return;
    }
    setProblem(null);
    onCreate(trimmedLabel, trimmedKey, unique);
    setLabel('');
    setKey('');
    setUnique(false);
    setOpen(false);
  }

  return (
    <div className="panel indexes">
      <header>
        <h2>Indexes</h2>
        {allowWrite && (
          // '+ Index' rather than the '+ New' the database picker uses: two buttons with one name on one screen is
          // ambiguous to a screen reader and to a test, and the noun is the useful half anyway.
          <button className="ghost" data-testid="new-index" onClick={() => setOpen(value => !value)} disabled={busy}
            title="Declare a composite index">
            {open ? 'Cancel' : '+ Index'}
          </button>
        )}
      </header>

      {declared.length === 0 && (
        <p className="empty" data-testid="no-indexes">
          No composite index declared. Labels and property values are always indexed on their own; a declared
          <code> (label, key)</code> pair turns a labelled property lookup into one probe.
        </p>
      )}

      <ul className="legend" data-testid="indexes">
        {declared.map(index => (
          <li key={`${index.label}.${index.key}`} data-index={`${index.label}.${index.key}`}>
            <button onClick={() => onPick(index.label, index.key)}
              title={`Write a query that uses :${index.label}.${index.key}`}>
              <span className="name">
                :{index.label}.{index.key}
              </span>
              {index.unique && (
                <span className="uniqueTag" title="Also a label-scoped unique constraint">unique</span>
              )}
            </button>
            {allowWrite && (
              <button
                className="ghost drop"
                data-testid={`drop-${index.label}.${index.key}`}
                disabled={busy}
                title={`Drop the index on :${index.label}.${index.key}`}
                onClick={() => {
                  // A confirm rather than the typed name a database drop demands: an index is rebuildable from the
                  // data by declaring it again. The unique case is not, in the sense that matters — the constraint
                  // goes with it, because in this engine they are one declaration — so it says so.
                  const warning = index.unique
                    ? `Dropping :${index.label}.${index.key} also drops its unique constraint, because they are one`
                      + ` declaration. Two :${index.label} nodes may then share a ${index.key}.\n\nDrop it?`
                    : `Drop the index on :${index.label}.${index.key}? Queries still answer, by probing the property`
                      + ` index and verifying the label.`;
                  if (window.confirm(warning)) {
                    onDrop(index.label, index.key);
                  }
                }}
              >
                Drop
              </button>
            )}
          </li>
        ))}
      </ul>

      {open && (
        <div className="indexForm">
          <input
            autoFocus
            value={label}
            placeholder="Label"
            aria-label="Index label"
            onChange={event => { setLabel(event.target.value); setProblem(null); }}
            onKeyDown={event => { if (event.key === 'Enter') { submit(); } }}
          />
          <input
            value={key}
            placeholder="Property key"
            aria-label="Index property key"
            onChange={event => { setKey(event.target.value); setProblem(null); }}
            onKeyDown={event => { if (event.key === 'Enter') { submit(); } }}
          />
          <label className="check" title="Refuse two nodes with this label that share this value">
            <input type="checkbox" checked={unique} onChange={event => setUnique(event.target.checked)} />
            unique
          </label>
          <button type="button" onClick={submit} disabled={busy} data-testid="create-index">
            Declare
          </button>
          <p className="footnote">
            Declaring scans the store, so the index is complete when it appears. A unique one is refused if the
            data already breaks it.
          </p>
        </div>
      )}

      {problem && <p className="message error small">{problem}</p>}
      {error && <p className="message error small" data-testid="index-error">{error}</p>}

      {indexes && indexes.uniqueConstraints.length > 0 && (
        <p className="footnote" data-testid="unique-constraints">
          Store-wide unique keys: {indexes.uniqueConstraints.join(', ')} — over every node whatever its labels, and
          declared through the Java API only.
        </p>
      )}
      {indexes && !allowWrite && (
        <p className="footnote">Read-only server: start it with --allow-write to declare or drop one.</p>
      )}
    </div>
  );
}
