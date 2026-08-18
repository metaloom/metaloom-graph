import { useCallback, useEffect, useState } from 'react';
import {
  ApiError, createDatabase, createIndex, dropDatabase, dropIndex, fetchDatabases, fetchIndexes, fetchSchema,
  fetchStats, logout, runQuery, whoami,
  type DatabaseList, type IndexList, type QueryResult, type Schema, type Stats
} from './api';
import {
  needsLogin, parseGrants, sessionCanWrite, sessionMay, unrestricted, type Session
} from './auth';
import { LoginForm } from './components/LoginForm';
import { UserPanel } from './components/UserPanel';
import { applyResult, clear, emptyGraph, removeNode, type GraphData, type MergeMode } from './model/graph';
import { DatabasePicker } from './components/DatabasePicker';
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
  const [indexes, setIndexes] = useState<IndexList | null>(null);
  const [indexError, setIndexError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [tab, setTab] = useState<'graph' | 'table'>('graph');
  const [fitKey, setFitKey] = useState(0);
  const [databases, setDatabases] = useState<DatabaseList | null>(null);
  const [database, setDatabase] = useState('');
  /**
   * Who this browser is, or null until the server has been asked.
   *
   * Null is "not known yet" and is deliberately distinct from a session with `authenticated: false`: the first renders
   * nothing, the second renders a login form. Rendering the app during the gap would fire half a dozen requests that
   * all come back 401.
   */
  const [session, setSession] = useState<Session | null>(null);
  const [showAdmin, setShowAdmin] = useState(false);

  /**
   * What the server will accept, not what this page would like.
   *
   * Both gates, because both refuse independently: the flag bounds the whole deployment and the grant bounds this user
   * within it. Offering the editor when either says no would produce a 403 on every run.
   */
  const allowWrite = (databases?.allowWrite ?? false) && sessionCanWrite(session, database);

  /** Asked once, before anything else: it decides whether there is an application to render at all. */
  useEffect(() => {
    void (async () => {
      try {
        const who = await whoami();
        setSession({
          authEnabled: who.authEnabled,
          authenticated: who.authenticated,
          user: who.user,
          grants: parseGrants(who.grants)
        });
      } catch {
        // Unreachable or not a graph server. Assume a login is needed rather than rendering an app that cannot work.
        setSession({ authEnabled: true, authenticated: false, user: null, grants: [] });
      }
    })();
  }, []);

  /**
   * Drops back to the login screen when a session has gone.
   *
   * Called from every catch, because an idle timeout does not announce itself: it presents as the next request
   * failing, and without this the app would render "request failed" over a UI that can no longer do anything.
   */
  const signedOut = useCallback((failure: unknown) => {
    if (failure instanceof ApiError && failure.isUnauthenticated) {
      setSession(previous => previous === null
        ? { authEnabled: true, authenticated: false, user: null, grants: [] }
        : { ...previous, authenticated: false, user: null, grants: [] });
      return true;
    }
    return false;
  }, []);

  const loadSchema = useCallback(async (name: string, refresh: boolean) => {
    setSchemaLoading(true);
    setSchemaError(null);
    try {
      setSchema(await fetchSchema(name, refresh));
      setStats(await fetchStats(name));
    } catch (failure) {
      if (!signedOut(failure)) {
        setSchemaError(failure instanceof Error ? failure.message : String(failure));
      }
    } finally {
      setSchemaLoading(false);
    }
  }, [signedOut]);

  /**
   * The declared indexes of one database.
   *
   * Its own fetch rather than a field on the schema: the schema is a cached scan of the data and this is one catalog
   * page, so folding them together would either stale the declarations or make listing three of them cost a scan.
   */
  const loadIndexes = useCallback(async (name: string) => {
    try {
      setIndexes(await fetchIndexes(name));
      setIndexError(null);
    } catch (failure) {
      if (!signedOut(failure)) {
        setIndexError(failure instanceof Error ? failure.message : String(failure));
      }
    }
  }, [signedOut]);

  const declareIndex = useCallback(async (label: string, key: string, unique: boolean) => {
    try {
      await createIndex(label, key, unique, database);
      setIndexError(null);
      await loadIndexes(database);
    } catch (failure) {
      // Shown in the panel rather than the query bar: declaring a unique index over data that already breaks it is
      // refused with a message naming the two nodes, and that message is the most useful thing on the screen.
      setIndexError(failure instanceof Error ? failure.message : String(failure));
    }
  }, [database, loadIndexes]);

  const removeIndex = useCallback(async (label: string, key: string) => {
    try {
      await dropIndex(label, key, database);
      setIndexError(null);
      await loadIndexes(database);
    } catch (failure) {
      setIndexError(failure instanceof Error ? failure.message : String(failure));
    }
  }, [database, loadIndexes]);

  const loadDatabases = useCallback(async (): Promise<DatabaseList | null> => {
    try {
      const list = await fetchDatabases();
      setDatabases(list);
      return list;
    } catch (failure) {
      setSchemaError(failure instanceof Error ? failure.message : String(failure));
      return null;
    }
  }, []);

  useEffect(() => {
    // The default comes from the server rather than being assumed to be 'default'. A server started with
    // --database=sales would otherwise open a picker showing one database and querying another.
    void (async () => {
      const list = await loadDatabases();
      const initial = list?.default ?? '';
      setDatabase(initial);
      await loadSchema(initial, false);
      await loadIndexes(initial);
    })();
  }, [loadDatabases, loadSchema, loadIndexes]);

  /**
   * Switching database clears the canvas.
   *
   * Not caution — an element id is only unique within a store, so two databases can hand back the same id for
   * different nodes. Merging their graphs would draw one circle for two records, with nothing on screen to say so.
   */
  const selectDatabase = useCallback(async (name: string) => {
    setDatabase(name);
    setGraph(emptyGraph);
    setResult(null);
    setSelected(null);
    setStatus(null);
    setError(null);
    setIndexError(null);
    await loadSchema(name, false);
    await loadIndexes(name);
    await loadDatabases();
  }, [loadSchema, loadIndexes, loadDatabases]);

  const addDatabase = useCallback(async (name: string) => {
    try {
      await createDatabase(name);
      await loadDatabases();
      await selectDatabase(name);
    } catch (failure) {
      setError(failure instanceof ApiError ? failure : new ApiError('transport', String(failure)));
    }
  }, [loadDatabases, selectDatabase]);

  const removeDatabase = useCallback(async (name: string) => {
    try {
      await dropDatabase(name);
      const list = await loadDatabases();
      await selectDatabase(list?.default ?? '');
    } catch (failure) {
      setError(failure instanceof ApiError ? failure : new ApiError('transport', String(failure)));
    }
  }, [loadDatabases, selectDatabase]);

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
      const response = await runQuery(text, database);
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
      // A write with no RETURN has no rows and no graph, so "0 rows · 0 nodes on the canvas" would be true and tell
      // the user nothing about the three nodes they just created. What it changed leads instead.
      const changed = response.stats ? `${response.stats.summary} · ` : '';
      setStatus(`${changed}${response.rowCount} row${response.rowCount === 1 ? '' : 's'} in ${response.elapsedMillis} ms · ${drawn}${held}`);
      if (response.graph.nodes.length === 0 && response.rowCount > 0) {
        setTab('table');
      } else if (response.graph.nodes.length > 0) {
        setTab('graph');
        setFitKey(key => key + 1);
      }
      if (response.stats) {
        // The sidebar's counts come from a scan of the data, so a write can add a label that was not there before.
        // The server has already thrown its cached scan away; this is the half that asks for the new one.
        void loadSchema(database, false);
      }
    } catch (failure) {
      // A session that expired mid-session presents as the next query failing. Without this the screen would read
      // "request failed" over a UI that can no longer do anything at all.
      if (!signedOut(failure)) {
        setError(failure instanceof ApiError ? failure : new ApiError('transport', String(failure)));
        setStatus(null);
      }
    } finally {
      setRunning(false);
    }
  }, [graph, database, loadSchema]);

  const runCurrent = useCallback(() => void run(query, mode), [run, query, mode]);

  const pick = useCallback((text: string) => {
    setQuery(text);
    void run(text, mode);
  }, [run, mode]);

  // Nothing at all until the server has said whether it wants a login. Rendering the app in the gap would fire half a
  // dozen requests that all come back 401 and leave the screen covered in failures that mean nothing.
  if (session === null) {
    return <div className="app" data-testid="loading" />;
  }

  if (needsLogin(session)) {
    return <LoginForm onSignedIn={setSession} />;
  }

  const mayAdminister = unrestricted(session)
    || sessionMay(session, 'MANAGE_USERS', '*') || sessionMay(session, 'MANAGE_ROLES', '*');

  return (
    <div className="app">
      <header className="topBar">
        <h1>MetaLoom // Graph</h1>
        <span className="subtitle">
          openCypher{allowWrite ? '' : ', read-only'}
        </span>
        <span className="spacer" />
        {mayAdminister && (
          <button
            data-testid="toggle-admin"
            className={showAdmin ? 'active' : ''}
            onClick={() => setShowAdmin(!showAdmin)}
          >Users</button>
        )}
        {session.authenticated && (
          <>
            <span className="signedInAs" data-testid="signed-in-as">{session.user}</span>
            <button
              data-testid="sign-out"
              onClick={() => void (async () => {
                try {
                  await logout();
                } finally {
                  // Locally regardless of what the server said: a logout that failed still means this browser is
                  // finished with the session, and leaving the app up would be worse than a stale server-side entry
                  // that expires on its own.
                  setSession({ authEnabled: true, authenticated: false, user: null, grants: [] });
                }
              })()}
            >Sign out</button>
          </>
        )}
        <DatabasePicker
          databases={databases}
          current={database}
          busy={running || schemaLoading}
          onSelect={name => void selectDatabase(name)}
          onCreate={name => void addDatabase(name)}
          onDrop={name => void removeDatabase(name)}
        />
      </header>

      {showAdmin && mayAdminister && (
        <div className="body">
          <UserPanel session={session} />
        </div>
      )}

      <div className="body" style={showAdmin && mayAdminister ? { display: 'none' } : undefined}>
        <Sidebar
          schema={schema}
          schemaError={schemaError}
          schemaLoading={schemaLoading}
          onRefreshSchema={() => { void loadSchema(database, true); void loadIndexes(database); }}
          onPickLabel={label => pick(`MATCH (n:${escapeName(label)})\nRETURN n\nLIMIT 100`)}
          onPickType={type => pick(`MATCH (a)-[r:${escapeName(type)}]->(b)\nRETURN a, r, b\nLIMIT 100`)}
          stats={stats}
          indexes={indexes}
          indexError={indexError}
          onCreateIndex={(label, key, unique) => void declareIndex(label, key, unique)}
          onDropIndex={(label, key) => void removeIndex(label, key)}
          onPickIndex={(label, key) =>
            // Set, not run: a probe needs a value only the user has, so running it now would return nothing and read
            // as a broken index. The template is what the index answers in one probe.
            setQuery(`MATCH (n:${escapeName(label)} {${escapeName(key)}: 'value'})\nRETURN n\nLIMIT 100`)}
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
