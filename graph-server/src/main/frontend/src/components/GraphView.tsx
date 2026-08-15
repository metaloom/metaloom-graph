import { useEffect, useRef } from 'react';
import { drag as d3drag } from 'd3-drag';
import {
  forceCenter, forceCollide, forceLink, forceManyBody, forceSimulation, forceX, forceY,
  type Simulation, type SimulationLinkDatum
} from 'd3-force';
import { select, type Selection as D3Selection } from 'd3-selection';
import { zoom as d3zoom, zoomIdentity, type ZoomBehavior } from 'd3-zoom';
import type { GraphData, GraphLink, GraphNode } from '../model/graph';
import { colorOfNode, relColor } from '../model/colors';
import type { Selected } from '../selection';

const NODE_RADIUS = 22;
/** Where the arrowhead stops, so it sits against the circle instead of under it. */
const ARROW_GAP = NODE_RADIUS + 9;
/** Above this many links the type labels are dropped: they stop being readable and start costing frames. */
const LABEL_LIMIT = 250;
/** A graph of three nodes framed to fill the canvas is three enormous circles. */
const MAX_FIT_SCALE = 1.5;

interface SimLink extends SimulationLinkDatum<GraphNode> {
  id: string;
  type: string;
  materialised: boolean;
  source: string | GraphNode;
  target: string | GraphNode;
  /** 0 for the only link between two nodes; ±1, ±2… for the rest, which is how they are drawn apart. */
  curve: number;
}

interface Props {
  graph: GraphData;
  selected: Selected | null;
  onSelect: (selected: Selected | null) => void;
  /** Changing this refits the view to the graph. */
  fitKey: number;
}

/**
 * The force layout.
 *
 * <h3>Who owns the DOM</h3>
 *
 * React owns everything outside this `<svg>`; d3 owns everything inside it. They do not share: the simulation writes
 * x/y onto the node objects sixty times a second, and putting that through React state would re-render the whole
 * application on every tick. So the svg is created once and updated by data joins, and the only React state that
 * crosses the boundary is the props above.
 *
 * <h3>Why positions survive a merge</h3>
 *
 * The simulation is given the model's node objects, not copies. Merge mode reuses those objects for nodes it has seen
 * before (see model/graph.ts), so their x/y are still there and only genuinely new nodes have to find a place. That
 * is what makes "add to view" feel like adding rather than redrawing.
 */
export function GraphView({ graph, selected, onSelect, fitKey }: Props) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const svgRef = useRef<SVGSVGElement | null>(null);
  const rootRef = useRef<SVGGElement | null>(null);
  const simRef = useRef<Simulation<GraphNode, SimLink> | null>(null);
  const zoomRef = useRef<ZoomBehavior<SVGSVGElement, unknown> | null>(null);
  const sizeRef = useRef({ width: 800, height: 600 });
  const graphRef = useRef(graph);
  graphRef.current = graph;
  /** A fit asked for while the layout is still moving, to be honoured when it stops. */
  const pendingFitRef = useRef(false);
  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;

  /**
   * Frames the whole graph.
   *
   * Only ever called against settled positions. Fitting mid-layout was the first version and it was worse than not
   * fitting at all: a second after a query the nodes are still bunched near the centre, so the computed scale is the
   * maximum, and the simulation then spreads everything out at that magnification and off the edges of the screen.
   */
  const fitNow = () => {
    const nodes = graphRef.current.nodes;
    if (nodes.length === 0) {
      return;
    }
    const { width, height } = sizeRef.current;
    const xs = nodes.map(node => node.x ?? 0);
    const ys = nodes.map(node => node.y ?? 0);
    const minX = Math.min(...xs) - NODE_RADIUS * 2;
    const maxX = Math.max(...xs) + NODE_RADIUS * 2;
    const minY = Math.min(...ys) - NODE_RADIUS * 2;
    const maxY = Math.max(...ys) + NODE_RADIUS * 2;
    const fitted = 0.92 / Math.max((maxX - minX) / width, (maxY - minY) / height);
    // Capped: a graph of four nodes framed to fill the canvas is four enormous circles, not a useful picture.
    const scale = Number.isFinite(fitted) && fitted > 0 ? Math.min(MAX_FIT_SCALE, fitted) : 1;
    const transform = zoomIdentity
      .translate(width / 2, height / 2)
      .scale(scale)
      .translate(-(minX + maxX) / 2, -(minY + maxY) / 2);
    select(svgRef.current!).call(zoomRef.current!.transform, transform);
  };

  // ------------------------------------------------------------------ setup, once
  useEffect(() => {
    const svg = select(svgRef.current!);
    const root = select(rootRef.current!);

    const zoomBehavior = d3zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.08, 6])
      .on('zoom', event => root.attr('transform', event.transform.toString()));
    svg.call(zoomBehavior).on('dblclick.zoom', null);
    zoomRef.current = zoomBehavior;

    // A click that lands on the background, not on an element, clears the selection.
    svg.on('click', event => {
      if (event.target === svgRef.current) {
        onSelectRef.current(null);
      }
    });

    const simulation = forceSimulation<GraphNode, SimLink>([])
      .force('link', forceLink<GraphNode, SimLink>([]).id(node => node.id).distance(130).strength(0.5))
      .force('charge', forceManyBody().strength(-520).distanceMax(900))
      .force('collide', forceCollide(NODE_RADIUS + 12))
      .force('x', forceX().strength(0.02))
      .force('y', forceY().strength(0.02))
      .on('tick', () => tick(root))
      .on('end', () => {
        if (pendingFitRef.current) {
          pendingFitRef.current = false;
          fitNow();
        }
      });
    simRef.current = simulation;

    const observer = new ResizeObserver(entries => {
      const box = entries[0].contentRect;
      sizeRef.current = { width: box.width, height: box.height };
      simulation.force('center', forceCenter(box.width / 2, box.height / 2));
      simulation.alpha(Math.max(simulation.alpha(), 0.05)).restart();
    });
    observer.observe(hostRef.current!);

    return () => {
      observer.disconnect();
      simulation.stop();
    };
  }, []);

  // ------------------------------------------------------------------ data join
  useEffect(() => {
    const simulation = simRef.current;
    if (!simulation) {
      return;
    }
    const root = select(rootRef.current!);
    const { width, height } = sizeRef.current;

    const nodes = graph.nodes;
    const known = new Map(nodes.map(node => [node.id, node]));
    const links = withCurvature(graph.links).map(link => ({ ...link })) as SimLink[];

    // A node the layout has not placed yet starts next to something it is connected to, if it can, so a merge grows
    // the picture outwards instead of dropping the new nodes in a heap on top of the old ones.
    let placed = 0;
    for (const node of nodes) {
      if (node.x !== undefined) {
        continue;
      }
      placed++;
      const anchor = links
        .map(link => (link.source === node.id ? known.get(link.target as string)
          : link.target === node.id ? known.get(link.source as string) : undefined))
        .find(candidate => candidate?.x !== undefined);
      node.x = (anchor?.x ?? width / 2) + (Math.random() - 0.5) * 120;
      node.y = (anchor?.y ?? height / 2) + (Math.random() - 0.5) * 120;
    }

    joinLinks(root, links, onSelectRef);
    joinNodes(root, nodes, simulation, onSelectRef);

    simulation.nodes(nodes);
    (simulation.force('link') as ReturnType<typeof forceLink<GraphNode, SimLink>>).links(links);
    // A result that placed nothing new needs a nudge, not a rearrangement. Re-running the same query used to throw
    // the whole layout back into motion and land every node somewhere else, which reads as the picture changing when
    // nothing has.
    simulation.alpha(placed > 0 ? 0.9 : 0.15).restart();
    tick(root);
  }, [graph.version]);

  // ------------------------------------------------------------------ selection highlight
  useEffect(() => {
    const root = select(rootRef.current!);
    root.selectAll<SVGGElement, GraphNode>('g.node')
      .classed('selected', node => selected?.kind === 'node' && selected.id === node.id);
    root.selectAll<SVGPathElement, SimLink>('path.link')
      .classed('selected', link => selected?.kind === 'link' && selected.id === link.id);
    root.selectAll<SVGGElement, GraphNode>('g.node').classed('dimmed', node =>
      selected?.kind === 'node' ? !isNeighbour(graph, selected.id, node.id) : false);
  }, [selected, graph.version]);

  // ------------------------------------------------------------------ fit
  useEffect(() => {
    const simulation = simRef.current;
    if (fitKey === 0 || !simulation || graph.nodes.length === 0) {
      return;
    }
    if (simulation.alpha() <= simulation.alphaMin()) {
      // Nothing is moving — the Fit button on a settled graph.
      fitNow();
    } else {
      pendingFitRef.current = true;
    }
  }, [fitKey]);

  return (
    <div className="graphHost" ref={hostRef}>
      <svg ref={svgRef} className="graph">
        <g ref={rootRef}>
          <g className="linkLayer" />
          <g className="linkHitLayer" />
          <g className="linkLabelLayer" />
          <g className="nodeLayer" />
        </g>
      </svg>
      {graph.nodes.length === 0 && (
        <div className="canvasEmpty">
          <p>Nothing to draw yet.</p>
          <p className="hint">
            Run a query that returns nodes or relationships — <code>MATCH (n)-[r]-&gt;(m) RETURN n, r, m LIMIT 50</code>
            — or pick a label from the left.
          </p>
        </div>
      )}
    </div>
  );
}

// -------------------------------------------------------------------- joins

type Root = D3Selection<SVGGElement, unknown, null, undefined>;
type SelectRef = { current: (selected: Selected | null) => void };

function joinLinks(root: Root, links: SimLink[], onSelect: SelectRef) {
  root.select('g.linkLayer')
    .selectAll<SVGPathElement, SimLink>('path.link')
    .data(links, link => link.id)
    .join(enter => enter.append('path').attr('class', 'link'))
    .attr('stroke', link => relColor(link.type))
    .attr('stroke-dasharray', link => (link.materialised ? null : '5 4'))
    .attr('marker-end', link => `url(#${arrowId(link.type)})`);

  // An invisible fat copy of every line, carrying the click. A 1.6px stroke is a 1.6px target: reaching it with a
  // mouse takes aim, and a test driving a click at the middle of the line's bounding box misses it outright.
  root.select('g.linkHitLayer')
    .selectAll<SVGPathElement, SimLink>('path.linkHit')
    .data(links, link => link.id)
    .join(enter => enter.append('path')
      .attr('class', 'linkHit')
      .on('click', (event, link) => {
        event.stopPropagation();
        onSelect.current({ kind: 'link', id: link.id });
      })
      // The hit path is in its own layer, so the visible line cannot be reached with a CSS sibling selector.
      .on('mouseenter', (_event, link) =>
        root.selectAll<SVGPathElement, SimLink>('path.link').classed('hover', other => other.id === link.id))
      .on('mouseleave', () => root.selectAll('path.link').classed('hover', false)));

  root.select('g.linkLabelLayer')
    .selectAll<SVGTextElement, SimLink>('text.linkLabel')
    .data(links.length <= LABEL_LIMIT ? links : [], link => link.id)
    .join('text')
    .attr('class', 'linkLabel')
    .attr('fill', link => relColor(link.type))
    .text(link => link.type);

  // One arrowhead per relationship type, coloured to match its lines. Markers cannot inherit a stroke colour, so
  // there is no way around defining them per type.
  const svg = select(root.node()!.ownerSVGElement!);
  let defs = svg.select<SVGDefsElement>('defs');
  if (defs.empty()) {
    defs = svg.append('defs');
  }
  const types = Array.from(new Set(links.map(link => link.type)));
  defs.selectAll<SVGMarkerElement, string>('marker')
    .data(types, type => type)
    .join(enter => {
      const marker = enter.append('marker')
        .attr('id', type => arrowId(type))
        .attr('viewBox', '0 -5 10 10')
        .attr('refX', 9)
        .attr('refY', 0)
        .attr('markerWidth', 7)
        .attr('markerHeight', 7)
        .attr('orient', 'auto');
      marker.append('path').attr('d', 'M0,-4L9,0L0,4');
      return marker;
    })
    .select('path')
    .attr('fill', type => relColor(type));
}

function joinNodes(root: Root, nodes: GraphNode[], simulation: Simulation<GraphNode, SimLink>, onSelect: SelectRef) {
  const dragBehavior = d3drag<SVGGElement, GraphNode>()
    .on('start', (event, node) => {
      if (!event.active) {
        simulation.alphaTarget(0.25).restart();
      }
      node.fx = node.x;
      node.fy = node.y;
    })
    .on('drag', (event, node) => {
      node.fx = event.x;
      node.fy = event.y;
    })
    .on('end', (event, node) => {
      if (!event.active) {
        simulation.alphaTarget(0);
      }
      // Released rather than pinned: a pinned node that the user has forgotten about distorts every later layout.
      node.fx = null;
      node.fy = null;
    });

  const groups = root.select('g.nodeLayer')
    .selectAll<SVGGElement, GraphNode>('g.node')
    .data(nodes, node => node.id)
    .join(enter => {
      const group = enter.append('g').attr('class', 'node');
      group.append('circle').attr('r', NODE_RADIUS);
      group.append('text').attr('class', 'caption').attr('dy', 4);
      group.append('title');
      group.on('click', (event, node) => {
        event.stopPropagation();
        onSelect.current({ kind: 'node', id: node.id });
      });
      return group;
    });

  groups.call(dragBehavior);
  groups.select('circle').attr('fill', node => colorOfNode(node.labels));
  groups.select('text.caption').text(node => truncate(node.caption));
  groups.select('title').text(node => `${node.labels.join(':') || 'no label'} ${node.caption}\nid ${node.id}`);
}

/** Runs on every simulation tick, so it does no allocation and no selection lookup beyond the two joins. */
function tick(root: Root) {
  // The visible line and its invisible click target are the same shape, so they are set together.
  root.selectAll<SVGPathElement, SimLink>('path.link, path.linkHit').attr('d', link => {
    const source = link.source as GraphNode;
    const target = link.target as GraphNode;
    if (source.x === undefined || target.x === undefined) {
      return '';
    }
    return arc(source, target, link.curve);
  });

  root.selectAll<SVGTextElement, SimLink>('text.linkLabel').each(function (link) {
    const source = link.source as GraphNode;
    const target = link.target as GraphNode;
    if (source.x === undefined || target.x === undefined) {
      return;
    }
    const midX = (source.x + target.x) / 2 + normalX(source, target) * link.curve * 22;
    const midY = (source.y! + target.y!) / 2 + normalY(source, target) * link.curve * 22;
    let angle = (Math.atan2(target.y! - source.y!, target.x - source.x) * 180) / Math.PI;
    // Keep the text the right way up: past vertical, read it from the other end.
    if (angle > 90 || angle < -90) {
      angle += 180;
    }
    select(this)
      .attr('transform', `translate(${midX},${midY}) rotate(${angle})`)
      .attr('dy', -4);
  });

  root.selectAll<SVGGElement, GraphNode>('g.node')
    .attr('transform', node => `translate(${node.x ?? 0},${node.y ?? 0})`);
}

// -------------------------------------------------------------------- geometry

/** A quadratic arc, straight when there is only one link between the pair, bowed when there are more. */
function arc(source: GraphNode, target: GraphNode, curve: number): string {
  const dx = target.x! - source.x!;
  const dy = target.y! - source.y!;
  const length = Math.hypot(dx, dy) || 1;
  // Stop short of the target so the arrowhead is visible against the circle rather than inside it.
  const endX = target.x! - (dx / length) * ARROW_GAP;
  const endY = target.y! - (dy / length) * ARROW_GAP;
  const startX = source.x! + (dx / length) * NODE_RADIUS;
  const startY = source.y! + (dy / length) * NODE_RADIUS;
  if (curve === 0) {
    return `M${startX},${startY}L${endX},${endY}`;
  }
  const controlX = (startX + endX) / 2 + normalX(source, target) * curve * 44;
  const controlY = (startY + endY) / 2 + normalY(source, target) * curve * 44;
  return `M${startX},${startY}Q${controlX},${controlY} ${endX},${endY}`;
}

function normalX(source: GraphNode, target: GraphNode): number {
  const dy = target.y! - source.y!;
  return -dy / (Math.hypot(target.x! - source.x!, dy) || 1);
}

function normalY(source: GraphNode, target: GraphNode): number {
  const dx = target.x! - source.x!;
  return dx / (Math.hypot(dx, target.y! - source.y!) || 1);
}

/**
 * Spreads parallel links apart.
 *
 * Two nodes joined by three relationships would otherwise be three lines drawn exactly on top of each other, and a
 * user would see one. Each pair gets its links bowed by alternating offsets. A self-loop always needs a bow.
 */
function withCurvature(links: GraphLink[]): (GraphLink & { curve: number })[] {
  const groups = new Map<string, number>();
  return links.map(link => {
    const key = link.source < link.target
      ? `${link.source} ${link.target}`
      : `${link.target} ${link.source}`;
    const index = groups.get(key) ?? 0;
    groups.set(key, index + 1);
    if (link.source === link.target) {
      return { ...link, curve: index + 1 };
    }
    // 0, +1, -1, +2, -2 …
    const magnitude = Math.ceil(index / 2);
    const curve = index === 0 ? 0 : (index % 2 === 1 ? magnitude : -magnitude);
    return { ...link, curve };
  });
}

function isNeighbour(graph: GraphData, selectedId: string, nodeId: string): boolean {
  if (selectedId === nodeId) {
    return true;
  }
  return graph.links.some(link =>
    (link.source === selectedId && link.target === nodeId) || (link.target === selectedId && link.source === nodeId));
}

function truncate(caption: string): string {
  return caption.length > 11 ? `${caption.slice(0, 10)}…` : caption;
}

/** Marker ids have to be valid in a `url(#…)` reference, and a relationship type is arbitrary text. */
function arrowId(type: string): string {
  let id = 'arrow-';
  for (const character of type) {
    id += /[A-Za-z0-9_]/.test(character) ? character : `_${character.codePointAt(0)!.toString(16)}`;
  }
  return id;
}
