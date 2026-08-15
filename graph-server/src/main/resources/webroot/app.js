/*
 * The browser side: send a query, draw the answer.
 *
 * Plain modules-free JavaScript against a vendored D3 v7. No build step, because a build step for four hundred lines
 * of UI is a second toolchain to keep working, and this one has to survive being offline.
 */
'use strict';

const $ = (id) => document.getElementById(id);

const EXAMPLES = [
  'MATCH (n)-[r]->(m) RETURN n, r, m LIMIT 50',
  'MATCH (n) RETURN labels(n) AS labels, count(*) AS n ORDER BY n DESC',
  'MATCH p = (a)-[*1..3]->(b) RETURN p LIMIT 25',
  'MATCH (n) RETURN n LIMIT 100',
];

let simulation = null;
let selected = null;

// ---------------------------------------------------------------- wiring

function init() {
  $('run').addEventListener('click', run);
  $('query').addEventListener('keydown', (event) => {
    if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
      event.preventDefault();
      run();
    }
  });

  for (const example of EXAMPLES) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = example.length > 52 ? example.slice(0, 49) + '…' : example;
    button.title = example;
    button.addEventListener('click', () => {
      $('query').value = example;
      run();
    });
    $('examples').appendChild(button);
  }

  for (const tab of document.querySelectorAll('.tab')) {
    tab.addEventListener('click', () => selectView(tab.dataset.view));
  }

  window.addEventListener('resize', () => {
    if (simulation) {
      const { width, height } = canvasSize();
      simulation.force('centre', d3.forceCenter(width / 2, height / 2)).alpha(0.3).restart();
    }
  });

  loadStats();
  run();
}

function selectView(view) {
  for (const tab of document.querySelectorAll('.tab')) {
    tab.classList.toggle('active', tab.dataset.view === view);
  }
  $('graph-view').hidden = view !== 'graph';
  $('table-view').hidden = view !== 'table';
  // The force layout sizes itself from the SVG's client box, which is zero while the tab is hidden.
  if (view === 'graph' && simulation) {
    const { width, height } = canvasSize();
    simulation.force('centre', d3.forceCenter(width / 2, height / 2)).alpha(0.3).restart();
  }
}

async function loadStats() {
  try {
    const response = await fetch('api/stats');
    const stats = await response.json();
    $('stats').textContent =
      `${stats.nodes.toLocaleString()} nodes · ${stats.relationships.toLocaleString()} relationships · `
      + `${stats.labels} labels · ${formatBytes(stats.storageBytes)}`;
  } catch (error) {
    $('stats').textContent = 'store unavailable';
  }
}

// ---------------------------------------------------------------- querying

async function run() {
  const query = $('query').value.trim();
  if (!query) return;

  $('run').disabled = true;
  $('error').hidden = true;
  $('timing').textContent = 'running…';

  try {
    const response = await fetch('api/query', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ query }),
    });
    const payload = await response.json();

    if (!response.ok) {
      showError(payload);
      return;
    }
    $('timing').textContent = `${payload.rowCount} row${payload.rowCount === 1 ? '' : 's'} in ${payload.elapsedMillis} ms`;
    drawTable(payload);
    drawGraph(payload.graph);
    loadStats();
  } catch (error) {
    showError({ message: String(error) });
  } finally {
    $('run').disabled = false;
  }
}

function showError(payload) {
  // The server sends the engine's own message, and for a syntax error that message already contains the source line
  // and a caret under the offending token. Rendering it verbatim in a monospace block is what keeps the caret aligned.
  $('error').textContent = payload.message || 'Query failed';
  $('error').hidden = false;
  $('timing').textContent = '';
}

// ---------------------------------------------------------------- table

function drawTable(payload) {
  const wrap = $('table-wrap');
  wrap.textContent = '';
  if (!payload.columns.length) return;

  const table = document.createElement('table');
  const head = table.createTHead().insertRow();
  for (const column of payload.columns) {
    const th = document.createElement('th');
    th.textContent = column;
    head.appendChild(th);
  }
  const body = table.createTBody();
  for (const row of payload.rows) {
    const tr = body.insertRow();
    for (const value of row) {
      const td = tr.insertCell();
      if (value === null || value === undefined) {
        const span = document.createElement('span');
        span.className = 'null';
        span.textContent = 'null';
        td.appendChild(span);
      } else {
        td.textContent = renderValue(value);
      }
    }
  }
  wrap.appendChild(table);
}

function renderValue(value) {
  if (value && value._type === 'node') {
    const labels = value.labels.map((l) => ':' + l).join('');
    return `(${labels} ${JSON.stringify(value.properties)})`;
  }
  if (value && value._type === 'relationship') {
    return `[:${value.relType} ${JSON.stringify(value.properties)}]`;
  }
  if (value && value._type === 'path') {
    return value.text;
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

// ---------------------------------------------------------------- graph

function canvasSize() {
  const box = $('canvas').getBoundingClientRect();
  return { width: Math.max(320, box.width), height: Math.max(240, box.height) };
}

/** A stable colour per label, so the same label keeps its colour across queries. */
const palette = d3.scaleOrdinal(d3.schemeTableau10);

function drawGraph(graph) {
  const svg = d3.select('#canvas');
  svg.selectAll('*').remove();
  hideInspector();

  $('graph-empty').hidden = graph.nodes.length > 0;
  if (!graph.nodes.length) {
    if (simulation) simulation.stop();
    simulation = null;
    return;
  }

  const { width, height } = canvasSize();

  // Arrowheads are defined once and referenced by every link.
  svg.append('defs').append('marker')
    .attr('id', 'arrow')
    .attr('viewBox', '0 -5 10 10')
    .attr('refX', 22)
    .attr('refY', 0)
    .attr('markerWidth', 6)
    .attr('markerHeight', 6)
    .attr('orient', 'auto')
    .append('path')
    .attr('d', 'M0,-5L10,0L0,5')
    .attr('fill', '#5b6478');

  const root = svg.append('g');
  svg.call(d3.zoom().scaleExtent([0.2, 4]).on('zoom', (event) => root.attr('transform', event.transform)));

  // D3's force layout mutates the objects it is given, so it gets copies: re-running a query must not depend on
  // whether the previous run left coordinates behind.
  const nodes = graph.nodes.map((n) => ({ ...n }));
  const links = graph.links.map((l) => ({ ...l }));

  const link = root.append('g').selectAll('line').data(links).join('line')
    .attr('stroke', '#5b6478')
    .attr('stroke-width', (d) => (d.materialised ? 1.6 : 1))
    // A lightweight edge has no record of its own, and the dashed line says so at a glance.
    .attr('stroke-dasharray', (d) => (d.materialised ? null : '4 3'))
    .attr('marker-end', 'url(#arrow)');

  const linkLabel = root.append('g').selectAll('text').data(links).join('text')
    .attr('class', 'rel-label')
    .attr('text-anchor', 'middle')
    .text((d) => d.type);

  const node = root.append('g').selectAll('g').data(nodes).join('g')
    .style('cursor', 'pointer')
    .on('click', (event, d) => showInspector(d))
    .call(drag());

  node.append('circle')
    .attr('r', 16)
    .attr('fill', (d) => palette(d.labels[0] || ''))
    .attr('stroke', '#0f1116')
    .attr('stroke-width', 1.5);

  node.append('text')
    .attr('class', 'node-label')
    .attr('text-anchor', 'middle')
    .attr('dy', 28)
    .text((d) => (d.caption.length > 18 ? d.caption.slice(0, 17) + '…' : d.caption));

  node.append('title').text((d) => d.labels.map((l) => ':' + l).join('') + ' ' + JSON.stringify(d.properties));

  simulation = d3.forceSimulation(nodes)
    .force('link', d3.forceLink(links).id((d) => d.id).distance(90).strength(0.6))
    .force('charge', d3.forceManyBody().strength(-320))
    .force('collide', d3.forceCollide(30))
    .force('centre', d3.forceCenter(width / 2, height / 2))
    .on('tick', () => {
      link.attr('x1', (d) => d.source.x).attr('y1', (d) => d.source.y)
        .attr('x2', (d) => d.target.x).attr('y2', (d) => d.target.y);
      linkLabel.attr('x', (d) => (d.source.x + d.target.x) / 2)
        .attr('y', (d) => (d.source.y + d.target.y) / 2 - 4);
      node.attr('transform', (d) => `translate(${d.x},${d.y})`);
    });
}

function drag() {
  return d3.drag()
    .on('start', (event, d) => {
      if (!event.active) simulation.alphaTarget(0.3).restart();
      d.fx = d.x;
      d.fy = d.y;
    })
    .on('drag', (event, d) => {
      d.fx = event.x;
      d.fy = event.y;
    })
    .on('end', (event, d) => {
      if (!event.active) simulation.alphaTarget(0);
      // Released rather than pinned: a pinned node quietly distorts every later layout.
      d.fx = null;
      d.fy = null;
    });
}

// ---------------------------------------------------------------- inspector

function showInspector(node) {
  selected = node;
  const panel = $('inspector');
  panel.textContent = '';

  const heading = document.createElement('h2');
  heading.textContent = node.labels.length ? node.labels.map((l) => ':' + l).join(' ') : 'no labels';
  panel.appendChild(heading);

  const list = document.createElement('dl');
  addRow(list, 'id', String(node.id));
  for (const [key, value] of Object.entries(node.properties)) {
    addRow(list, key, typeof value === 'object' ? JSON.stringify(value) : String(value));
  }
  panel.appendChild(list);
  panel.hidden = false;
}

function addRow(list, key, value) {
  const dt = document.createElement('dt');
  dt.textContent = key;
  const dd = document.createElement('dd');
  dd.textContent = value;
  list.appendChild(dt);
  list.appendChild(dd);
}

function hideInspector() {
  selected = null;
  $('inspector').hidden = true;
}

function formatBytes(bytes) {
  const units = ['B', 'KiB', 'MiB', 'GiB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

init();
