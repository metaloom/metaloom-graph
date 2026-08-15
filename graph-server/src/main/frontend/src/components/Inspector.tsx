import type { GraphData } from '../model/graph';
import { colorOfNode, relColor } from '../model/colors';
import type { Selected } from '../selection';

interface Props {
  graph: GraphData;
  selected: Selected | null;
  onSelect: (selected: Selected | null) => void;
  /** Takes a node off the canvas without touching the store. */
  onRemove: (id: string) => void;
}

/**
 * The properties of whatever is selected.
 *
 * A node also gets its incident relationships, each one a link to the thing at the other end — which is how you walk a
 * graph you do not know the shape of without writing a query for every step.
 */
export function Inspector({ graph, selected, onSelect, onRemove }: Props) {
  if (!selected) {
    return (
      <div className="panel inspector" data-testid="inspector">
        <header><h2>Selection</h2></header>
        <p className="empty">Click a node or a relationship.</p>
      </div>
    );
  }

  if (selected.kind === 'node') {
    const node = graph.nodes.find(candidate => candidate.id === selected.id);
    if (!node) {
      return <NotHere onSelect={onSelect} />;
    }
    const incident = graph.links.filter(link => link.source === node.id || link.target === node.id);
    return (
      <div className="panel inspector" data-testid="inspector">
        <header>
          <h2>Node</h2>
          {/* Pruning a crowded canvas — the view, not the store. Deleting a node is something the query editor can
              now do, and this button deliberately is not that: hiding is undone by running the query again. */}
          <button className="ghost" data-testid="hide-node" onClick={() => onRemove(node.id)} title="Remove from the canvas">
            Hide
          </button>
        </header>
        <div className="chips">
          {node.labels.length === 0 && <span className="chip muted">no label</span>}
          {node.labels.map(label => (
            <span key={label} className="chip" style={{ background: colorOfNode([label]) }}>{label}</span>
          ))}
        </div>
        <PropertyTable properties={node.properties} id={node.id} />

        {incident.length > 0 && (
          <>
            <h3>Relationships ({incident.length})</h3>
            <ul className="incident">
              {incident.map(link => {
                const outgoing = link.source === node.id;
                const otherId = outgoing ? link.target : link.source;
                const other = graph.nodes.find(candidate => candidate.id === otherId);
                return (
                  <li key={link.id}>
                    <button onClick={() => onSelect({ kind: 'node', id: otherId })}>
                      <span className="direction">{outgoing ? '→' : '←'}</span>
                      <span className="relType" style={{ color: relColor(link.type) }}>{link.type}</span>
                      <span className="name">{other?.caption ?? otherId}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </>
        )}
      </div>
    );
  }

  const link = graph.links.find(candidate => candidate.id === selected.id);
  if (!link) {
    return <NotHere onSelect={onSelect} />;
  }
  const from = graph.nodes.find(node => node.id === link.source);
  const to = graph.nodes.find(node => node.id === link.target);
  return (
    <div className="panel inspector" data-testid="inspector">
      <header><h2>Relationship</h2></header>
      <div className="chips">
        <span className="chip" style={{ background: relColor(link.type), color: '#12161c' }}>{link.type}</span>
        {/*
          A lightweight edge lives entirely inside the adjacency list: no record, so no id and no properties. Saying so
          is better than showing an empty property table and letting the user wonder what went missing.
        */}
        {!link.materialised && <span className="chip muted" title="Stored in the adjacency list only">lightweight</span>}
      </div>
      <p className="endpoints">
        <button className="linkish" onClick={() => onSelect({ kind: 'node', id: link.source })}>
          {from?.caption ?? link.source}
        </button>
        <span className="arrow">→</span>
        <button className="linkish" onClick={() => onSelect({ kind: 'node', id: link.target })}>
          {to?.caption ?? link.target}
        </button>
      </p>
      {link.materialised
        ? <PropertyTable properties={link.properties} id={link.id} />
        : <p className="empty">No properties: a lightweight edge has no relationship record to hold them.</p>}
    </div>
  );
}

function NotHere({ onSelect }: { onSelect: (selected: Selected | null) => void }) {
  return (
    <div className="panel inspector" data-testid="inspector">
      <header><h2>Selection</h2></header>
      <p className="empty">That element is no longer on the canvas.</p>
      <button className="ghost" onClick={() => onSelect(null)}>Clear</button>
    </div>
  );
}

function PropertyTable({ properties, id }: { properties: Record<string, unknown>; id: string }) {
  const keys = Object.keys(properties);
  return (
    <>
      <h3>Properties</h3>
      <table className="properties">
        <tbody>
          <tr data-property="id">
            {/* The id is worth showing because it is what you paste into a query. It is a string all the way. */}
            <th>&lt;id&gt;</th>
            <td className="mono">{id}</td>
          </tr>
          {keys.map(key => (
            <tr key={key} data-property={key}>
              <th>{key}</th>
              <td>{formatValue(properties[key])}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {keys.length === 0 && <p className="empty">No properties.</p>}
    </>
  );
}

export function formatValue(value: unknown): string {
  if (value === null || value === undefined) {
    return 'null';
  }
  if (Array.isArray(value)) {
    return `[${value.map(formatValue).join(', ')}]`;
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
}
