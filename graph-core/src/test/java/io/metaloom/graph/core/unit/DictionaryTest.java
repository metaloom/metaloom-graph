package io.metaloom.graph.core.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.catalog.Catalog;
import io.metaloom.graph.core.catalog.Dictionary;

/**
 * The append-only string dictionary: what survives a torn append, and what it refuses to intern because it could not
 * read it back.
 *
 * @see <a href="../../../../../../../../spec/PROPERTY_STORAGE.md">spec/PROPERTY_STORAGE.md</a> section 3
 */
@Tag("unit")
public class DictionaryTest {

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("dict").resolve("test.dict");
	}

	private Dictionary open() throws Exception {
		return new Dictionary(file(), Catalog.MAX_KEY_ID);
	}

	private void truncateBy(long bytes) throws Exception {
		try (FileChannel channel = FileChannel.open(file(), StandardOpenOption.WRITE)) {
			channel.truncate(channel.size() - bytes);
		}
	}

	@Test
	public void testEntriesSurviveAReopen() throws Exception {
		try (Dictionary dict = open()) {
			assertEquals(1, dict.intern("alpha"));
			assertEquals(2, dict.intern("beta"));
			assertEquals(1, dict.intern("alpha"), "interning is idempotent");
			dict.force();
		}
		try (Dictionary dict = open()) {
			assertEquals(2, dict.size());
			assertEquals("alpha", dict.resolve(1));
			assertEquals(2, dict.lookup("beta"));
		}
	}

	/** A body cut short by a crash is dropped, and the next append must not land behind the leftover bytes. */
	@Test
	public void testATornBodyIsTruncatedBeforeTheNextAppend() throws Exception {
		try (Dictionary dict = open()) {
			dict.intern("alpha");
			dict.intern("beta");
			dict.force();
		}
		truncateBy(2);
		try (Dictionary dict = open()) {
			assertEquals(1, dict.size(), "the torn entry is not loaded");
			assertEquals(2, dict.intern("gamma"));
			dict.force();
		}
		try (Dictionary dict = open()) {
			assertEquals(2, dict.size());
			assertEquals("gamma", dict.resolve(2), "the entry interned after the tear reads back as itself");
		}
	}

	/** A crash inside the four length bytes leaves fewer than four; that is a torn tail too, not a corrupt store. */
	@Test
	public void testATornHeaderIsTruncatedBeforeTheNextAppend() throws Exception {
		try (Dictionary dict = open()) {
			dict.intern("alpha");
			dict.intern("beta");
			dict.force();
		}
		truncateBy("beta".length() + 2);
		try (Dictionary dict = open()) {
			assertEquals(1, dict.size());
			assertEquals(2, dict.intern("gamma"));
			dict.force();
		}
		try (Dictionary dict = open()) {
			assertEquals("gamma", dict.resolve(2));
		}
	}

	/** Load refuses an entry over 1 MiB, so intern must refuse it too, or the store cannot be reopened. */
	@Test
	public void testAnEntryTooLongToReloadIsRefused() throws Exception {
		String huge = "x".repeat((1 << 20) + 1);
		try (Dictionary dict = open()) {
			dict.intern("alpha");
			assertThrows(IllegalArgumentException.class, () -> dict.intern(huge));
			assertEquals(0, dict.lookup(huge));
			dict.force();
		}
		try (Dictionary dict = open()) {
			assertEquals(1, dict.size());
			assertEquals(2, dict.intern("x".repeat(1 << 20)), "exactly the limit is still accepted");
			dict.force();
		}
		try (Dictionary dict = open()) {
			assertEquals(1 << 20, dict.resolve(2).length());
		}
	}

	/**
	 * A lone surrogate has no UTF-8 encoding; the JDK's encoder silently writes '?', so two distinct keys would become
	 * one after a reopen and every id behind the second would change meaning.
	 */
	@Test
	public void testALoneSurrogateIsRefused() throws Exception {
		try (Dictionary dict = open()) {
			assertThrows(IllegalArgumentException.class, () -> dict.intern("k\uD800"));
			assertThrows(IllegalArgumentException.class, () -> dict.intern("k\uDBFF"));
			assertEquals(1, dict.intern("k😀"), "a proper surrogate pair is fine");
			dict.force();
		}
		try (Dictionary dict = open()) {
			assertEquals(1, dict.size());
			assertEquals("k😀", dict.resolve(1));
		}
		assertEquals(4 + "k".length() + 4, Files.size(file()));
	}
}
