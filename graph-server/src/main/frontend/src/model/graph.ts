import type { ApiGraph, ApiLink, ApiNode } from '../api';

/**
 * The graph the canvas is showing, and the rules for adding a query result to it.
 *
 * <h3>Why this is a module of its own</h3>
 *
 * Merging is where a graph browser is easy to get quietly wrong, and none of it needs a DOM: a node that appears in
 * two results must stay the *same object* or the force layout forgets where it was and the picture jumps; a
 * relationship returned by five rows must be drawn once; and a link whose far end arrives in a later query has to be
 * held until then. Those are testable rules, so they live here and are tested here.
 */

/** A node, plus the position fields d3-force writes onto it. The model never sets them; it must not lose them. */
export interface GraphNode extends ApiNode {
  x?: number;
  y?: number;
  vx?: number;
  vy?: number;
  fx?: number | null;
  fy?: number | null;
}

/** Endpoints stay as ids here. Only the simulation's own copies get object references. */
export type GraphLink = ApiLink;

export interface GraphData {
  nodes: GraphNode[];
  links: GraphLink[];
  /**
   * Links whose far end has not been seen yet.
   *
   * The server can only tell whether a link is drawable within one result, so a query returning `(a)-[r]->(b)` where
   * `b` came from an earlier query arrives as dangling. Holding it is what makes merge mode able to build a picture
   * one query at a time.
   */
  pending: GraphLink[];
  /** Incremented on every change, so the view can re-render on a scalar rather than a deep comparison. */
  version: number;
}

export type MergeMode = 'replace' | 'merge';

/** Held links are bounded: a long session of narrow queries would otherwise accumulate them without limit. */
export const PENDING_LIMIT = 5000;

export function emptyGraph(): GraphData {
  return { nodes: [], links: [], pending: [], version: 0 };
}

/**
 * Empties the canvas, keeping the version counter moving.
 *
 * The counter is what the view re-renders on, so anything that changes the graph has to advance it. Returning a plain
 * `emptyGraph()` here would reset it to zero, and the next result would land on version 1 — which the view may already
 * have drawn. That is not hypothetical: replace mode was written as `applyResult(emptyGraph(), …)` and so produced
 * version 1 for every query, leaving the second query's result invisible.
 */
export function clear(current: GraphData): GraphData {
  return { nodes: [], links: [], pending: [], version: current.version + 1 };
}

export function isEmpty(graph: GraphData): boolean {
  return graph.nodes.length === 0;
}

/**
 * Folds a query result into the displayed graph.
 *
 * `replace` shows exactly what the query returned and nothing else — including dropping links whose far end it did
 * not return, because there is nothing left that could ever resolve them. `merge` adds to what is there.
 */
export function applyResult(current: GraphData, incoming: ApiGraph, mode: MergeMode): GraphData {
  if (mode === 'replace') {
    // `current` contributes only its version: replace shows this result and nothing else. It must still be passed,
    // and the version must still advance, or the view has no way to tell that anything happened.
    const nodes = incoming.nodes.map(node => ({ ...node }));
    const ids = new Set(nodes.map(node => node.id));
    return {
      nodes,
      links: dedupe(incoming.links).filter(link => ids.has(link.source) && ids.has(link.target)),
      pending: [],
      version: current.version + 1
    };
  }

  const byId = new Map(current.nodes.map(node => [node.id, node]));
  const nodes = [...current.nodes];
  for (const node of incoming.nodes) {
    const existing = byId.get(node.id);
    if (existing) {
      // Mutated in place, deliberately. A fresh object would lose x/y and the node would jump across the canvas
      // every time a query mentioned it again.
      existing.labels = node.labels;
      existing.caption = node.caption;
      existing.properties = node.properties;
    } else {
      const copy: GraphNode = { ...node };
      byId.set(copy.id, copy);
      nodes.push(copy);
    }
  }

  const links = [...current.links];
  const seen = new Set(links.map(link => link.id));
  const held: GraphLink[] = [];
  for (const link of [...current.pending, ...incoming.links, ...incoming.danglingLinks]) {
    if (seen.has(link.id)) {
      continue;
    }
    if (byId.has(link.source) && byId.has(link.target)) {
      seen.add(link.id);
      links.push(link);
    } else {
      held.push(link);
    }
  }

  return {
    nodes,
    links,
    // Newest kept: an old unresolved link is the one least likely to ever be resolved.
    pending: held.slice(Math.max(0, held.length - PENDING_LIMIT)),
    version: current.version + 1
  };
}

/** Drops a node and every link that touched it, so a user can prune a crowded canvas. */
export function removeNode(current: GraphData, id: string): GraphData {
  const nodes = current.nodes.filter(node => node.id !== id);
  const [links, held] = partition(current.links, link => link.source !== id && link.target !== id);
  return {
    nodes,
    links,
    pending: [...current.pending, ...held].slice(-PENDING_LIMIT),
    version: current.version + 1
  };
}

function partition<T>(items: T[], keep: (item: T) => boolean): [T[], T[]] {
  const yes: T[] = [];
  const no: T[] = [];
  for (const item of items) {
    (keep(item) ? yes : no).push(item);
  }
  return [yes, no];
}

/** One relationship returned by many rows is one line. Ids are stable per relationship, materialised or not. */
function dedupe(links: ApiLink[]): GraphLink[] {
  const seen = new Set<string>();
  return links.filter(link => (seen.has(link.id) ? false : (seen.add(link.id), true)));
}
