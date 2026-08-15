/**
 * The HTTP surface of graph-server, and the types it answers with.
 *
 * Every element id crosses as a string. The server sends them that way because a 64-bit id does not survive a
 * JavaScript number — see ResultJson's javadoc — so nothing here may ever parse one into a number.
 */

export interface ApiNode {
  id: string;
  labels: string[];
  caption: string;
  properties: Record<string, unknown>;
}

export interface ApiLink {
  id: string;
  source: string;
  target: string;
  type: string;
  /** False for a lightweight edge, which has no relationship record and so no id and no properties. */
  materialised: boolean;
  properties: Record<string, unknown>;
}

export interface ApiGraph {
  nodes: ApiNode[];
  links: ApiLink[];
  /** Links whose far end this result did not return. Only merge mode can ever resolve one. */
  danglingLinks: ApiLink[];
}

export interface QueryResult {
  columns: string[];
  rows: unknown[][];
  rowCount: number;
  elapsedMillis: number;
  graph: ApiGraph;
}

export interface NamedCount {
  name: string;
  count: number;
}

export interface Schema {
  labels: NamedCount[];
  relationshipTypes: NamedCount[];
  nodeCount: number;
  relationshipCount: number;
  unlabelledNodeCount: number;
  elapsedMillis: number;
  cached: boolean;
}

export interface DatabaseSummary {
  name: string;
  /** True for the one a request that names none gets. */
  default: boolean;
  /** Whether the server has this store open. Counts are only present when it does. */
  open: boolean;
  nodes?: number;
  relationships?: number;
}

export interface DatabaseList {
  databases: DatabaseSummary[];
  default: string;
  /** False unless the server was started with --allow-drop, in which case the UI must not offer to drop. */
  allowDrop: boolean;
}

export interface Stats {
  database: string;
  nodes: number;
  relationships: number;
  labels: number;
  propertyKeys: number;
  storageBytes: number;
  openViews: number;
}

/**
 * A failure the server described in JSON.
 *
 * The server distinguishes "your query is wrong" (400, with the engine's caret line) from "the server broke" (500),
 * and the UI shows that distinction rather than flattening both into "request failed".
 */
export class ApiError extends Error {
  readonly kind: string;
  readonly line: number;
  readonly column: number;

  constructor(kind: string, message: string, line = 0, column = 0) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.line = line;
    this.column = column;
  }

  /** True when the message carries the parser's caret line, which is worth showing in a monospace block. */
  get hasCaret(): boolean {
    return this.message.includes('^');
  }
}

async function parse<T>(response: Response): Promise<T> {
  const text = await response.text();
  let body: unknown;
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    throw new ApiError('transport', `${response.status} ${response.statusText}: ${text.slice(0, 200)}`);
  }
  if (!response.ok) {
    const error = body as { error?: string; message?: string; line?: number; column?: number };
    throw new ApiError(error.error ?? 'http', error.message ?? `${response.status} ${response.statusText}`,
      error.line ?? 0, error.column ?? 0);
  }
  return body as T;
}

/**
 * Which database every request below is about.
 *
 * The name is a query parameter rather than part of the path because scoping a query to a database is a property of
 * the query, not a different resource. The collection at api/databases is the exception, because there it is.
 *
 * An empty name means "whatever the server calls its default". The UI never hard-codes 'default': a server started
 * with --database=sales would then have a picker that silently queried a database nobody chose.
 */
function scoped(path: string, database: string, extra?: string): string {
  const params = new URLSearchParams();
  if (database) {
    params.set('database', database);
  }
  if (extra) {
    params.set(extra, 'true');
  }
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}

export async function runQuery(
  query: string,
  database = '',
  parameters: Record<string, unknown> = {}
): Promise<QueryResult> {
  const response = await fetch(scoped('api/query', database), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ query, parameters })
  });
  return parse<QueryResult>(response);
}

export async function fetchSchema(database = '', refresh = false): Promise<Schema> {
  return parse<Schema>(await fetch(scoped('api/schema', database, refresh ? 'refresh' : undefined)));
}

export async function fetchStats(database = ''): Promise<Stats> {
  return parse<Stats>(await fetch(scoped('api/stats', database)));
}

export async function fetchDatabases(): Promise<DatabaseList> {
  return parse<DatabaseList>(await fetch('api/databases'));
}

export async function createDatabase(name: string): Promise<void> {
  await parse<unknown>(await fetch(`api/databases/${encodeURIComponent(name)}`, { method: 'PUT' }));
}

export async function dropDatabase(name: string): Promise<void> {
  await parse<unknown>(await fetch(`api/databases/${encodeURIComponent(name)}`, { method: 'DELETE' }));
}
