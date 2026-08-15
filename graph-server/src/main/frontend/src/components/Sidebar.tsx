import type { Schema, Stats } from '../api';
import type { GraphData } from '../model/graph';
import { colorOfNode, nodeColor, relColor } from '../model/colors';
import type { Selected } from '../selection';
import { Inspector } from './Inspector';

interface Props {
  schema: Schema | null;
  schemaError: string | null;
  schemaLoading: boolean;
  onRefreshSchema: () => void;
  onPickLabel: (label: string) => void;
  onPickType: (type: string) => void;
  stats: Stats | null;
  graph: GraphData;
  selected: Selected | null;
  onSelect: (selected: Selected | null) => void;
  onRemove: (id: string) => void;
}

/**
 * What is in the store, and what is selected.
 *
 * The two halves answer the two questions a user has in front of an unfamiliar graph: what kinds of thing are in here,
 * and what is this thing I just clicked. The counts come from a scan (see SchemaScanner), so they describe the store,
 * not the canvas — clicking one is how you get from the store to the canvas.
 */
export function Sidebar(props: Props) {
  const { schema, schemaError, schemaLoading, onRefreshSchema, onPickLabel, onPickType, stats } = props;

  return (
    <aside className="sidebar">
      <div className="panel schema">
        <header>
          <h2>Store</h2>
          <button className="ghost" onClick={onRefreshSchema} disabled={schemaLoading} title="Rescan the store">
            {schemaLoading ? '…' : 'Rescan'}
          </button>
        </header>

        {stats && (
          <p className="storeSummary">
            {stats.nodes.toLocaleString()} nodes · {stats.relationships.toLocaleString()} relationships ·{' '}
            {formatBytes(stats.storageBytes)}
          </p>
        )}

        {schemaError && <p className="message error small">{schemaError}</p>}

        <h3>Node labels</h3>
        {schema?.labels.length === 0 && <p className="empty">No labelled nodes.</p>}
        <ul className="legend" data-testid="labels">
          {schema?.labels.map(label => (
            <li key={label.name} data-label={label.name}>
              <button onClick={() => onPickLabel(label.name)} title={`Show ${label.name} nodes`}>
                <span className="swatch" style={{ background: nodeColor(label.name) }} />
                <span className="name">{label.name}</span>
                <span className="count">{label.count.toLocaleString()}</span>
              </button>
            </li>
          ))}
        </ul>

        <h3>Relationship types</h3>
        {schema?.relationshipTypes.length === 0 && <p className="empty">No relationships.</p>}
        <ul className="legend" data-testid="relationshipTypes">
          {schema?.relationshipTypes.map(type => (
            <li key={type.name} data-type={type.name}>
              <button onClick={() => onPickType(type.name)} title={`Show ${type.name} relationships`}>
                <span className="swatch line" style={{ background: relColor(type.name) }} />
                <span className="name">{type.name}</span>
                <span className="count">{type.count.toLocaleString()}</span>
              </button>
            </li>
          ))}
        </ul>

        {schema && schema.unlabelledNodeCount > 0 && (
          <p className="footnote" data-testid="unlabelled">
            <span className="swatch" style={{ background: colorOfNode([]) }} />
            {schema.unlabelledNodeCount.toLocaleString()}{' '}
            {schema.unlabelledNodeCount === 1 ? 'node carries' : 'nodes carry'} no label
          </p>
        )}
        {schema && (
          <p className="footnote">
            Scanned in {schema.elapsedMillis} ms{schema.cached ? ', from cache' : ''}
          </p>
        )}
      </div>

      <Inspector graph={props.graph} selected={props.selected} onSelect={props.onSelect} onRemove={props.onRemove} />
    </aside>
  );
}

function formatBytes(bytes: number): string {
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value < 10 && unit > 0 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}
