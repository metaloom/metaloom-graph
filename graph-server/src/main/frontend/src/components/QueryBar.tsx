import { useEffect, useRef } from 'react';
import type { MergeMode } from '../model/graph';
import { ApiError } from '../api';

interface Props {
  query: string;
  onQueryChange: (query: string) => void;
  mode: MergeMode;
  onModeChange: (mode: MergeMode) => void;
  onRun: () => void;
  running: boolean;
  error: ApiError | null;
  status: string | null;
}

/**
 * The query editor.
 *
 * Ctrl/Cmd+Enter runs it, because a query is several lines often enough that Enter has to mean newline.
 *
 * The replace/merge switch lives here rather than in a menu: it changes what pressing Run does to the picture, so it
 * belongs next to Run where it can be seen before the click rather than regretted after it.
 */
export function QueryBar({ query, onQueryChange, mode, onModeChange, onRun, running, error, status }: Props) {
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    textareaRef.current?.focus();
  }, []);

  return (
    <section className="queryBar">
      <div className="queryRow">
        <textarea
          ref={textareaRef}
          data-testid="query-input"
          className="queryInput"
          spellCheck={false}
          rows={3}
          value={query}
          placeholder="MATCH (n)-[r]->(m) RETURN n, r, m LIMIT 50"
          onChange={event => onQueryChange(event.target.value)}
          onKeyDown={event => {
            if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
              event.preventDefault();
              onRun();
            }
          }}
        />
        <div className="queryActions">
          <button data-testid="run" className="run" onClick={onRun} disabled={running || query.trim().length === 0}>
            {running ? 'Running…' : 'Run'}
          </button>
          <div className="modeSwitch" role="radiogroup" aria-label="What a new result does to the view">
            <button
              role="radio"
              data-testid="mode-replace"
              aria-checked={mode === 'replace'}
              className={mode === 'replace' ? 'active' : ''}
              onClick={() => onModeChange('replace')}
              title="Each result replaces what is on the canvas"
            >
              Replace
            </button>
            <button
              role="radio"
              data-testid="mode-merge"
              aria-checked={mode === 'merge'}
              className={mode === 'merge' ? 'active' : ''}
              onClick={() => onModeChange('merge')}
              title="Each result is added to what is on the canvas, keeping existing positions"
            >
              Merge
            </button>
          </div>
          <span className="shortcut">Ctrl+Enter</span>
        </div>
      </div>

      {error && (
        <div data-testid="error" className={`message error ${error.hasCaret ? 'caret' : ''}`}>
          <span className="errorKind">{error.kind}</span>
          {/* The engine's caret line only lines up in a monospace block, which is the whole reason it exists. */}
          <pre>{error.message}</pre>
        </div>
      )}
      {!error && status && <div data-testid="status" className="message status">{status}</div>}
    </section>
  );
}
