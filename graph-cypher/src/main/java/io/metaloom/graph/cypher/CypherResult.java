package io.metaloom.graph.cypher;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The rows a query returned, with their column names.
 *
 * <p>
 * Fully materialised. A streaming result would let a caller stop early, but it would also have to keep the read view
 * open until the caller finished with it — and a result that holds a snapshot open for an unknown length of time is
 * exactly the leak {@code SnapshotStats} exists to report. Materialising means the view closes when
 * {@link Cypher#query} returns, and the cost is stated in spec/CYPHER.md rather than discovered.
 * </p>
 */
public final class CypherResult implements Iterable<CypherRow> {

	private final List<String> columns;
	private final List<List<Object>> rows;

	public CypherResult(List<String> columns, List<List<Object>> rows) {
		this.columns = List.copyOf(columns);
		this.rows = rows;
	}

	public List<String> columns() {
		return columns;
	}

	public int size() {
		return rows.size();
	}

	public boolean isEmpty() {
		return rows.isEmpty();
	}

	public CypherRow row(int index) {
		return new CypherRow(columns, rows.get(index));
	}

	/** The raw values, one list per row, in column order. */
	public List<List<Object>> values() {
		return rows;
	}

	/** Every value of one column, which is the shape most single-column queries actually want. */
	public List<Object> column(String name) {
		int index = columns.indexOf(name);
		if (index < 0) {
			throw new IllegalArgumentException("No column '" + name + "'. Columns: " + columns);
		}
		List<Object> out = new ArrayList<>(rows.size());
		for (List<Object> row : rows) {
			out.add(row.get(index));
		}
		return out;
	}

	@Override
	public Iterator<CypherRow> iterator() {
		Iterator<List<Object>> underlying = rows.iterator();
		return new Iterator<>() {

			@Override
			public boolean hasNext() {
				return underlying.hasNext();
			}

			@Override
			public CypherRow next() {
				return new CypherRow(columns, underlying.next());
			}
		};
	}

	@Override
	public String toString() {
		StringBuilder out = new StringBuilder(String.join(" | ", columns));
		for (List<Object> row : rows) {
			out.append('\n');
			for (int i = 0; i < row.size(); i++) {
				out.append(i == 0 ? "" : " | ").append(row.get(i));
			}
		}
		return out.toString();
	}
}
