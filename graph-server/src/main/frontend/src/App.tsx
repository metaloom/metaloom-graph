import { useCallback, useEffect, useState } from 'react';
import { ApiError, fetchSchema, fetchStats, runQuery, type QueryResult, type Schema, type Stats } from './api';
import { applyResult, clear, emptyGraph, removeNode, type GraphData, type MergeMode } from './model/graph';
import { GraphView } from './components/GraphView';
import { QueryBar } from './components/QueryBar';
import { ResultTable } from './components/ResultTable';
import { Sidebar } from './components/Sidebar';
import type { Selected } from './selection';

const STARTER_QUERY = 'MATCH (n)-[r]->(m)\nRETURN n, r, m\nLIMIT 50';

export function App() {
  const [query, setQuery] = useState(STARTER_QUERY);
  const [mode, setMode] = useState<MergeMode>('replace');
  const [graph, setGraph] = useState<GraphData>(emptyGraph);
  const [result, setResult] = useState<QueryResult | null>(null);
  const [selected, setSelected] = useState<Selected | null>(null);
  const [schema, setSchema] = useState<Schema | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [stats, setStats] = useState<Stats | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [tab, setTab] = useState<'graph' | 'table'>('graph');
  const [fitKey, setFitKey] = useState(0);

  const loadSchema = useCallback(async (refresh: boolean) => {
    setSchemaLoading(true);
    setSchemaError(null);
    try {
      setSchema(await fetchSchema(refresh));
      setStats(await fetchStats());
    } catch (failure) {
      setSchemaError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setSchemaLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadSchema(false);
  }, [loadSchema]);

  /**
   * Runs a query and folds its result into the canvas.
   *
   * The query text is passed in rather than read from state because clicking a label in the sidebar both sets the
   * editor's contents and runs it, and a state update is not visible to the same tick that scheduled it.
   */
  const run = useCallback(async (text: string, into: MergeMode) => {
    if (text.trim().length === 0) {
      return;
    }
    setRunning(true);
    setError(null);
    try {
      const response = await runQuery(text);
      setResult(response);
      const next = applyResult(graph, response.graph, into);
      setGraph(next);
      if (into === 'replace') {
        setSelected(null);
      }
      const drawn = `${next.nodes.length} node${next.nodes.length === 1 ? '' : 's'}, `
        + `${next.links.length} relationship${next.links.length === 1 ? '' : 's'} on the canvas`;
      const held = next.pending.length > 0
        ? ` · ${next.pending.length} relationship${next.pending.length === 1 ? '' : 's'} waiting for the other end`
        : '';
      setStatus(`${response.rowCount} row${response.rowCount === 1 ? '' : 's'} in ${response.elapsedMillis} ms · ${drawn}${held}`);
      if (response.graph.nodes.length === 0 && response.rowCount > 0) {
        setTab('table');
      } else if (response.graph.nodes.length > 0) {
        setTab('graph');
        setFitKey(key => key + 1);
      }
    } catch (failure) {
      setError(failure instanceof ApiError ? failure : new ApiError('transport', String(failure)));
      setStatus(null);
    } finally {
      setRunning(false);
    }
  }, [graph]);

  const runCurrent = useCallback(() => void run(query, mode), [run, query, mode]);

  const pick = useCallback((text: string) => {
    setQuery(text);
    void run(text, mode);
  }, [run, mode]);

  return (
    <div className="app">
      <header className="topBar">
        <h1>Graph browser</h1>
        <span className="subtitle">openCypher, read-only</span>
      </header>

      <div className="body">
        <Sidebar
          schema={schema}
          schemaError={schemaError}
          schemaLoading={schemaLoading}
          onRefreshSchema={() => void loadSchema(true)}
          onPickLabel={label => pick(`MATCH (n:${escapeName(label)})\nRETURN n\nLIMIT 100`)}
          onPickType={type => pick(`MATCH (a)-[r:${escapeName(type)}]->(b)\nRETURN a, r, b\nLIMIT 100`)}
          stats={stats}
          graph={graph}
          selected={selected}
          onSelect={setSelected}
          onRemove={id => { setGraph(removeNode(graph, id)); setSelected(null); }}
        />

        <main className="main">
          <QueryBar
            query={query}
            onQueryChange={setQuery}
            mode={mode}
            onModeChange={setMode}
            onRun={runCurrent}
            running={running}
            error={error}
            status={status}
          />

          <div className="viewTabs">
            <button data-testid="tab-graph" className={tab === 'graph' ? 'active' : ''} onClick={() => setTab('graph')}>
              Graph
            </button>
            <button data-testid="tab-table" className={tab === 'table' ? 'active' : ''} onClick={() => setTab('table')}>
              Table{result ? ` (${result.rowCount})` : ''}
            </button>
            <span className="spacer" />
            {tab === 'graph' && (
              <>
                <button className="ghost" onClick={() => setFitKey(key => key + 1)} disabled={graph.nodes.length === 0}>
                  Fit
                </button>
                <button
                  className="ghost"
                  onClick={() => { setGraph(clear(graph)); setSelected(null); setStatus(null); }}
                  disabled={graph.nodes.length === 0}
                >
                  Clear
                </button>
              </>
            )}
          </div>

          <div className="view">
            {/* Both stay mounted: unmounting the canvas would throw away every position the layout has found. */}
            <div className={tab === 'graph' ? 'pane' : 'pane hidden'}>
              <GraphView graph={graph} selected={selected} onSelect={setSelected} fitKey={fitKey} />
            </div>
            <div className={tab === 'table' ? 'pane' : 'pane hidden'}>
              <ResultTable result={result} onSelect={setSelected} />
            </div>
          </div>
        </main>
      </div>
    </div>
  );
}

/**
 * A label is arbitrary text but a Cypher identifier is not, so anything but a plain identifier is backtick-quoted —
 * and a backtick inside one is doubled, which is how openCypher escapes it.
 */
function escapeName(name: string): string {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(name) ? name : `\`${name.replace(/`/g, '``')}\``;
}
