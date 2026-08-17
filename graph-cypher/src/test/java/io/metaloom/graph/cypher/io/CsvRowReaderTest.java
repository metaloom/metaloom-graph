package io.metaloom.graph.cypher.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The CSV reader, against the parts of RFC 4180 that a real export actually contains.
 *
 * <p>
 * Pure logic over a {@link StringReader}: no files, because the file part is somebody else's ({@link CsvSource}), and
 * because the cases worth testing here are all about characters rather than about paths.
 * </p>
 */
@Tag("unit")
public class CsvRowReaderTest {

	private static List<List<String>> rows(String text) {
		return rows(text, ',');
	}

	private static List<List<String>> rows(String text, char terminator) {
		List<List<String>> out = new ArrayList<>();
		try (CsvRowReader reader = new CsvRowReader(new StringReader(text), terminator)) {
			List<String> row;
			while ((row = reader.next()) != null) {
				out.add(row);
			}
		}
		return out;
	}

	private static List<String> row(String... cells) {
		return Arrays.asList(cells);
	}

	@Test
	public void testTheOrdinaryCase() {
		assertEquals(List.of(row("id", "name"), row("1", "Alice"), row("2", "Bob")),
			rows("id,name\n1,Alice\n2,Bob"));
	}

	@Test
	public void testATrailingNewlineDoesNotProduceAnEmptyRow() {
		assertEquals(List.of(row("id"), row("1")), rows("id\n1\n"));
	}

	@Test
	public void testCrLfAndLoneCr() {
		assertEquals(List.of(row("id", "name"), row("1", "Alice")), rows("id,name\r\n1,Alice\r\n"));
		assertEquals(List.of(row("a"), row("b")), rows("a\rb"));
	}

	@Test
	public void testAByteOrderMarkIsNotPartOfTheFirstColumnName() {
		// The failure this prevents is invisible in a terminal: the header reads `id`, `row.id` is null for every row.
		assertEquals("id", rows("﻿id,name\n1,x").get(0).get(0));
	}

	@Test
	public void testQuotedFields() {
		assertEquals(List.of(row("a,b", "c")), rows("\"a,b\",c"), "a comma inside quotes is data");
		assertEquals(List.of(row("say \"hi\"")), rows("\"say \"\"hi\"\"\""), "a doubled quote is one literal quote");
		assertEquals(List.of(row("plain")), rows("\"plain\""));
	}

	/** The case a line-oriented reader cannot handle at all, and the reason this class is a state machine. */
	@Test
	public void testANewlineInsideAQuotedFieldDoesNotEndTheRow() {
		List<List<String>> rows = rows("id,address\n1,\"12 High St\nBonn\"\n2,elsewhere");
		assertEquals(3, rows.size(), "three rows, not four");
		assertEquals(row("1", "12 High St\nBonn"), rows.get(1));
		assertEquals(row("2", "elsewhere"), rows.get(2));
	}

	@Test
	public void testAQuoteInsideAnUnquotedFieldIsData() {
		// 6" is a length, not a delimiter. Treating it as one would lose the rest of the row.
		assertEquals(List.of(row("6\" pipe", "x")), rows("6\" pipe,x"));
	}

	@Test
	public void testAnEmptyUnquotedFieldIsNullAndAnEmptyQuotedOneIsNot() {
		List<String> row = rows("a,,\"\"").get(0);
		assertEquals("a", row.get(0));
		assertNull(row.get(1), "no value");
		assertEquals("", row.get(2), "a value that is empty");
	}

	@Test
	public void testABlankLineIsARowWithOneEmptyField() {
		assertEquals(List.of(row("a"), row((String) null), row("b")), rows("a\n\nb"));
	}

	@Test
	public void testACustomFieldTerminator() {
		assertEquals(List.of(row("id", "name"), row("1", "Alice")), rows("id;name\n1;Alice", ';'));
		// And a comma is then ordinary data.
		assertEquals(List.of(row("a,b", "c")), rows("a,b;c", ';'));
	}

	@Test
	public void testAnUnterminatedQuoteNamesTheLineItOpenedOn() {
		CsvFormatException e = assertThrows(CsvFormatException.class,
			() -> rows("id,name\n1,\"Alice\n2,Bob\n3,Carol\n"));
		assertTrue(e.getMessage().contains("line 2"), e.getMessage());
		assertTrue(e.getMessage().contains("never closed"), e.getMessage());
	}

	@Test
	public void testATerminatorMayNotBeAQuoteOrALineBreak() {
		assertThrows(IllegalArgumentException.class, () -> new CsvRowReader(new StringReader(""), '"'));
		assertThrows(IllegalArgumentException.class, () -> new CsvRowReader(new StringReader(""), '\n'));
	}

	@Test
	public void testAnEmptyStreamHasNoRows() {
		assertEquals(List.of(), rows(""));
	}

	/**
	 * A row that straddles the internal buffer must read the same as one that does not.
	 *
	 * <p>
	 * The reader refills an 8 KiB buffer underneath, and every {@code peek}-then-{@code read} pair — the {@code ""}
	 * escape, the {@code \r\n} pair, the BOM — is a place where a refill can land between the two halves.
	 * </p>
	 */
	@Test
	public void testRecordsStraddlingTheBufferReadTheSame() {
		String wide = "x".repeat(9000);
		List<List<String>> rows = rows("a,\"" + wide + "\"\r\nb,\"say \"\"hi\"\"\"\n");
		assertEquals(2, rows.size());
		assertEquals(wide, rows.get(0).get(1));
		assertEquals("say \"hi\"", rows.get(1).get(1));
	}

	/**
	 * The reported line is where the record <em>started</em>, not where the reader has got to.
	 *
	 * <p>
	 * The two differ by at least one for every row, because the newline that ends a record has to be consumed to know
	 * the record ended — and by more than one for a record with embedded newlines. Reporting the wrong one sends
	 * somebody to the line after the mistake, which in a million-row file is a real cost.
	 * </p>
	 */
	@Test
	public void testTheReportedLineIsWhereTheRecordStarted() {
		try (CsvRowReader reader = new CsvRowReader(new StringReader("a\nb\n\"c\nd\"\ne"), ',')) {
			reader.next();
			assertEquals(1, reader.line());
			reader.next();
			assertEquals(2, reader.line());
			// This record spans lines 3 and 4; it is reported as starting on 3.
			assertEquals(List.of("c\nd"), reader.next());
			assertEquals(3, reader.line());
			reader.next();
			assertEquals(5, reader.line(), "and the embedded newline was still counted");
		}
	}
}
