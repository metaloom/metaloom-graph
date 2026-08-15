import type { QueryResult } from '../api';
import type { Selected } from '../selection';
import { formatValue } from './Inspector';

interface Props {
  result: QueryResult | null;
  onSelect: (selected: Selected) => void;
}

/**
 * The rows, exactly as the query returned them.
 *
 * The canvas can only show what is renderable, so a query returning `count(*)` or `n.name` would leave a user staring
 * at an empty picture with no way to see the answer. This is that way. A cell holding a node or a relationship is
 * clickable and selects it, which ties the table back to the canvas.
 */
export function ResultTable({ result, onSelect }: Props) {
  if (!result) {
    return <p className="empty pad">No results yet.</p>;
  }
  if (result.rowCount === 0) {
    return <p className="empty pad">The query matched nothing.</p>;
  }
  return (
    <div className="tableScroll">
      <table className="results">
        <thead>
          <tr>{result.columns.map(column => <th key={column}>{column}</th>)}</tr>
        </thead>
        <tbody>
          {result.rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {row.map((cell, cellIndex) => (
                <td key={cellIndex}>{renderCell(cell, onSelect)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

interface CellObject {
  _type?: string;
  id?: string | null;
  labels?: string[];
  relType?: string;
  start?: string;
  end?: string;
  text?: string;
  properties?: Record<string, unknown>;
}

function renderCell(cell: unknown, onSelect: (selected: Selected) => void) {
  if (cell !== null && typeof cell === 'object' && '_type' in (cell as CellObject)) {
    const value = cell as CellObject;
    if (value._type === 'node' && value.id) {
      const id = value.id;
      return (
        <button className="linkish" onClick={() => onSelect({ kind: 'node', id })}>
          ({(value.labels ?? []).join(':') || 'node'} {summarise(value.properties)})
        </button>
      );
    }
    if (value._type === 'relationship') {
      // A lightweight edge has no id, so the canvas keys it on start/type/end; the same key is used here so that
      // clicking the cell selects the same line that is drawn.
      const id = value.id ?? `${value.start}/${value.relType}/${value.end}`;
      return (
        <button className="linkish" onClick={() => onSelect({ kind: 'link', id })}>
          [:{value.relType} {summarise(value.properties)}]
        </button>
      );
    }
    if (value._type === 'path') {
      return <span className="mono">{value.text}</span>;
    }
  }
  return <span className={typeof cell === 'number' ? 'mono number' : undefined}>{formatValue(cell)}</span>;
}

function summarise(properties: Record<string, unknown> | undefined): string {
  if (!properties) {
    return '';
  }
  const entries = Object.entries(properties).slice(0, 2);
  if (entries.length === 0) {
    return '';
  }
  return `{${entries.map(([key, value]) => `${key}: ${formatValue(value)}`).join(', ')}${
    Object.keys(properties).length > 2 ? ', …' : ''}}`;
}
