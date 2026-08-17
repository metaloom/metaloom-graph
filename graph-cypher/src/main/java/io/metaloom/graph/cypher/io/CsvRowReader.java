package io.metaloom.graph.cypher.io;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180, read one row at a time.
 *
 * <p>
 * Hand written, like the lexer beside it and for the same reason: the dependency's size is not the problem, its
 * presence on a path that must not fail is — and the error messages, which for a bulk import are most of what makes a
 * bad file fixable.
 * </p>
 *
 * <h2>Why this is not a loop over lines</h2>
 *
 * <p>
 * The obvious implementation reads a line and splits it, and the repository already has one:
 * {@code Corpus.splitCsv} in the cypher tests, which is honest about being "RFC 4180 enough for this file". It cannot
 * be reused here, because <b>a quoted field may contain a newline</b> — an address, a description, any exported text
 * column — and a line-oriented reader splits such a record in half and then reports a column count that is wrong for
 * both halves. So this reads characters and lets the quote state decide what a row ends at, which is the only way the
 * quoted-newline case can work at all.
 * </p>
 *
 * <p>
 * The rest of the format, in the order the state machine meets it:
 * </p>
 *
 * <ul>
 * <li><b>A UTF-8 BOM</b> is dropped if it leads the stream. It is not part of the data, and a first column named
 * {@code ﻿id} fails to match {@code row.id} in a way that is very hard to see in a terminal.</li>
 * <li><b>{@code \r\n} and {@code \n}</b> both end a row; a lone {@code \r} does too. Inside quotes, none of them do.</li>
 * <li><b>{@code ""} inside a quoted field</b> is one literal quote — the escape RFC 4180 specifies, and the reason a
 * quote cannot simply close the field.</li>
 * <li><b>An unterminated quote</b> is an error naming the line the field <em>opened</em> on, not the end of the file.
 * The opening quote is the typo; the end of the file is only where the consequence became visible.</li>
 * <li><b>A blank line is a row with one empty field</b>, not a skipped row and not the end of the file. Trailing
 * newlines at the end of a file are the exception: the reader stops at the stream's end rather than emitting a final
 * empty row for the newline that terminated the last real one.</li>
 * </ul>
 *
 * <p>
 * Nothing here interprets a value: every field is returned as a {@code String}, and an unquoted empty field is returned
 * as {@code null} so that a missing value and an empty one stay distinguishable. Typing is the query's job — see
 * {@code spec/CYPHER.md} on why {@code LOAD CSV} does not guess.
 * </p>
 *
 * @see <a href="../../../../../../../../spec/CYPHER.md">spec/CYPHER.md</a>
 */
public final class CsvRowReader implements AutoCloseable {

	/** The byte-order mark, as it arrives once the stream has been decoded as UTF-8. */
	private static final char BOM = '﻿';

	private static final int BUFFER_CHARS = 8192;

	private final Reader reader;
	private final char terminator;
	private final char[] buffer = new char[BUFFER_CHARS];

	private int length;
	private int position;
	private boolean exhausted;
	private boolean started;

	/** Where the reader has got to. 1-based. */
	private long line = 1;

	/**
	 * The line the record most recently returned <em>started</em> on.
	 *
	 * <p>
	 * Held separately because {@link #line} has already moved past the record by the time the caller sees it: the
	 * newline that ended the row was consumed to know the row had ended. Reporting that number would name the line
	 * <em>after</em> the mistake, which for a record containing its own newlines could be several lines after.
	 * </p>
	 */
	private long recordLine = 1;

	public CsvRowReader(Reader reader, char terminator) {
		if (terminator == '"' || terminator == '\r' || terminator == '\n') {
			throw new IllegalArgumentException("A field terminator may not be a quote or a line break");
		}
		this.reader = reader;
		this.terminator = terminator;
	}

	/** The line the row most recently returned by {@link #next()} began on. */
	public long line() {
		return recordLine;
	}

	/**
	 * The next row, or null at the end of the stream.
	 *
	 * @throws UncheckedIOException
	 *             wrapping a read failure, so this can be called from inside the executor's lambdas
	 * @throws CsvFormatException
	 *             if the file is malformed
	 */
	public List<String> next() {
		if (!started) {
			started = true;
			skipByteOrderMark();
		}
		if (peek() < 0) {
			return null;
		}
		recordLine = line;
		List<String> row = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		boolean wasQuoted = false;
		long openedAt = line;

		while (true) {
			int c = read();
			if (c < 0) {
				if (quoted) {
					throw new CsvFormatException("A quoted field opened on line " + openedAt
						+ " is never closed; the file ends inside it");
				}
				row.add(value(field, wasQuoted));
				return row;
			}
			if (quoted) {
				if (c == '"') {
					// A doubled quote is one literal quote; a single one ends the field.
					if (peek() == '"') {
						read();
						field.append('"');
						continue;
					}
					quoted = false;
					continue;
				}
				if (c == '\n') {
					line++;
				}
				field.append((char) c);
				continue;
			}
			if (c == '"' && field.isEmpty()) {
				// Only an opening quote at the start of a field opens one. A quote in the middle of an unquoted field
				// is data — plenty of real exports contain 6" — and treating it as a delimiter loses the rest of the
				// row.
				quoted = true;
				wasQuoted = true;
				openedAt = line;
				continue;
			}
			if (c == terminator) {
				row.add(value(field, wasQuoted));
				field.setLength(0);
				wasQuoted = false;
				continue;
			}
			if (c == '\r') {
				if (peek() == '\n') {
					read();
				}
				line++;
				row.add(value(field, wasQuoted));
				return row;
			}
			if (c == '\n') {
				line++;
				row.add(value(field, wasQuoted));
				return row;
			}
			field.append((char) c);
		}
	}

	/**
	 * An unquoted empty field is null; a quoted one is the empty string.
	 *
	 * <p>
	 * The distinction is the only one the format offers between "no value" and "a value that happens to be empty", and
	 * it is worth keeping: {@code SET n.x = row.x} should leave the property unset for the first and set it to
	 * {@code ''} for the second. Cypher's null semantics then do the rest.
	 * </p>
	 */
	private static String value(StringBuilder field, boolean wasQuoted) {
		return !wasQuoted && field.isEmpty() ? null : field.toString();
	}

	private void skipByteOrderMark() {
		if (peek() == BOM) {
			read();
		}
	}

	private int read() {
		int c = peek();
		if (c >= 0) {
			position++;
		}
		return c;
	}

	private int peek() {
		if (position < length) {
			return buffer[position];
		}
		if (exhausted) {
			return -1;
		}
		try {
			length = reader.read(buffer);
		} catch (IOException e) {
			throw new UncheckedIOException("Reading the CSV failed on line " + line, e);
		}
		position = 0;
		if (length <= 0) {
			exhausted = true;
			length = 0;
			return -1;
		}
		return buffer[0];
	}

	@Override
	public void close() {
		try {
			reader.close();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
