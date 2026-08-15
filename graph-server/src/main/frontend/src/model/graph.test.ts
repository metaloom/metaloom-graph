import { describe, expect, test } from 'vitest';
import type { ApiGraph, ApiLink, ApiNode } from '../api';
import { applyResult, clear, emptyGraph, PENDING_LIMIT, removeNode } from './graph';

function node(id: string, caption = id): ApiNode {
  return { id, labels: ['Person'], caption, properties: { name: caption } };
}

function link(id: string, source: string, target: string, type = 'KNOWS'): ApiLink {
  return { id, source, target, type, materialised: true, properties: {} };
}

function result(nodes: ApiNode[], links: ApiLink[] = [], danglingLinks: ApiLink[] = []): ApiGraph {
  return { nodes, links, danglingLinks };
}

test('every change advances the version', () => {
  // The canvas re-renders on this number and on nothing else, so a change that does not move it is a change the user
  // never sees. Replace mode had exactly that defect — it rebuilt from an empty graph, so every query produced
  // version 1 and the second one was silently ignored.
  let graph = emptyGraph();
  const versions = [graph.version];
  graph = applyResult(graph, result([node('1')]), 'replace');
  versions.push(graph.version);
  graph = applyResult(graph, result([node('2')]), 'replace');
  versions.push(graph.version);
  graph = applyResult(graph, result([node('3')]), 'merge');
  versions.push(graph.version);
  graph = clear(graph);
  versions.push(graph.version);
  graph = applyResult(graph, result([node('4')]), 'replace');
  versions.push(graph.version);

  expect(versions).toEqual([...versions].sort((a, b) => a - b));
  expect(new Set(versions).size).toBe(versions.length);
});

test('clear empties the graph', () => {
  const graph = clear(applyResult(emptyGraph(), result([node('1'), node('2')], [link('r1', '1', '2')]), 'merge'));
  expect(graph.nodes).toEqual([]);
  expect(graph.links).toEqual([]);
  expect(graph.pending).toEqual([]);
});

describe('replace', () => {
  test('shows exactly the new result', () => {
    const first = applyResult(emptyGraph(), result([node('1'), node('2')], [link('r1', '1', '2')]), 'replace');
    const second = applyResult(first, result([node('3')]), 'replace');

    expect(second.nodes.map(n => n.id)).toEqual(['3']);
    expect(second.links).toEqual([]);
  });

  test('drops a link whose far end the query did not return', () => {
    // Nothing left in the view could ever resolve it, so holding it would be holding it forever.
    const graph = applyResult(emptyGraph(), result([node('1')], [], [link('r1', '1', '2')]), 'replace');
    expect(graph.links).toEqual([]);
    expect(graph.pending).toEqual([]);
  });
});

describe('merge', () => {
  test('keeps the identical node object, so the layout keeps its position', () => {
    // The load-bearing one. A fresh object per result means d3-force sees a new node, drops x/y, and the whole
    // picture jumps every time a query mentions a node that was already on screen.
    const first = applyResult(emptyGraph(), result([node('1')]), 'merge');
    const alice = first.nodes[0];
    alice.x = 120;
    alice.y = -40;

    const second = applyResult(first, result([node('1'), node('2')]), 'merge');

    expect(second.nodes[0]).toBe(alice);
    expect(second.nodes[0].x).toBe(120);
    expect(second.nodes[0].y).toBe(-40);
    expect(second.nodes.map(n => n.id)).toEqual(['1', '2']);
  });

  test('updates the properties of a node seen again', () => {
    const first = applyResult(emptyGraph(), result([node('1', 'Alice')]), 'merge');
    const richer: ApiNode = { id: '1', labels: ['Person', 'Employee'], caption: 'Alice', properties: { age: 34 } };

    const second = applyResult(first, result([richer]), 'merge');

    expect(second.nodes).toHaveLength(1);
    expect(second.nodes[0].labels).toEqual(['Person', 'Employee']);
    expect(second.nodes[0].properties).toEqual({ age: 34 });
  });

  test('draws a relationship once however many results return it', () => {
    const pair = result([node('1'), node('2')], [link('r1', '1', '2')]);
    const first = applyResult(emptyGraph(), pair, 'merge');
    const second = applyResult(first, pair, 'merge');

    expect(second.links).toHaveLength(1);
  });

  test('holds a dangling link and draws it once its far end arrives', () => {
    // Building a picture one query at a time is the point of merge mode, and this is what makes it work: query one
    // returns a, query two returns b and the edge between them, which the server had to send as dangling because b's
    // partner was not in that result.
    const first = applyResult(emptyGraph(), result([node('1')]), 'merge');
    const second = applyResult(first, result([node('2')], [], [link('r1', '1', '2')]), 'merge');
    expect(second.links).toHaveLength(1);
    expect(second.pending).toHaveLength(0);
  });

  test('keeps holding a link whose far end never arrives', () => {
    const graph = applyResult(emptyGraph(), result([node('1')], [], [link('r1', '1', '99')]), 'merge');
    expect(graph.links).toHaveLength(0);
    expect(graph.pending).toHaveLength(1);
  });

  test('bounds the held links', () => {
    const dangling = Array.from({ length: PENDING_LIMIT + 50 }, (_, i) => link(`r${i}`, '1', `far${i}`));
    const graph = applyResult(emptyGraph(), result([node('1')], [], dangling), 'merge');

    expect(graph.pending).toHaveLength(PENDING_LIMIT);
    // The newest are the ones worth keeping: an old unresolved link is the least likely to ever resolve.
    expect(graph.pending[graph.pending.length - 1].id).toBe(`r${PENDING_LIMIT + 49}`);
  });

  test('a lightweight edge merges by its endpoints and type', () => {
    // It has no relationship record and so no id; the server keys it on start/type/end, which must dedupe as well as
    // a real id does.
    const edge: ApiLink = { id: '1/BOUGHT/2', source: '1', target: '2', type: 'BOUGHT', materialised: false, properties: {} };
    const first = applyResult(emptyGraph(), result([node('1'), node('2')], [edge]), 'merge');
    const second = applyResult(first, result([node('1'), node('2')], [{ ...edge }]), 'merge');

    expect(second.links).toHaveLength(1);
    expect(second.links[0].materialised).toBe(false);
  });
});

describe('removeNode', () => {
  test('takes its relationships with it, and can get them back', () => {
    const graph = applyResult(emptyGraph(), result([node('1'), node('2')], [link('r1', '1', '2')]), 'merge');
    const pruned = removeNode(graph, '2');

    expect(pruned.nodes.map(n => n.id)).toEqual(['1']);
    expect(pruned.links).toHaveLength(0);

    const back = applyResult(pruned, result([node('2')]), 'merge');
    expect(back.links).toHaveLength(1);
  });
});
