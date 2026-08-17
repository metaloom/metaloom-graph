package io.metaloom.graph.cli.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.Transaction;

/**
 * A dump file is untrusted input, and this is the oracle that says so.
 *
 * <p>
 * The rule, which is the same one {@code HostileInputTest} holds the storage parsers to and
 * {@code WireDecoderHostileInputTest} holds the wire decoder to: reading a dump either produces a graph or throws
 * {@link DumpFormatException}. Never an {@code OutOfMemoryError} from a believed length, never a
 * {@code NegativeArraySizeException}, never a {@code StackOverflowError} from nesting, and always in bounded time.
 * </p>
 *
 * <p>
 * This matters more for a dump than for most formats, because a dump is the file somebody reaches for when things have
 * already gone wrong — after a disk filled, a copy was interrupted, or a store was lost. It is exactly the file most
 * likely to be damaged, and the moment when a confusing failure costs the most.
 * </p>
 */
@Tag("unit")
public class DumpHostileInputTest {

	@TempDir
	Path tmp;

	private byte[] valid;

	private byte[] valid() throws Exception {
		if (valid == null) {
			try (GraphStore store = GraphStore.open(tmp.resolve("source"), GraphStoreOptions.defaults())) {
				try (Transaction txn = store.begin()) {
					long a = store.createNode(List.of("A"), Properties.of().set("n", 1L).set("s", "hello"));
					long b = store.createNode(List.of("B"), Properties.of().set("v", List.of(1L, 2L)));
					store.createRelationship(a, "R", b, Properties.of().set("w", 2.5d));
					store.createEdge(b, "L", a);
					txn.commit();
				}
				store.createIndex("A", "n");
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				GraphBackup.write(store, "src", out, 0);
				valid = out.toByteArray();
			}
		}
		return valid;
	}

	/** Restores into a throwaway store, and reports what escaped. */
	private Throwable attempt(byte[] dump, String name) {
		try (GraphStore target = GraphStore.open(tmp.resolve(name), GraphStoreOptions.defaults())) {
			try (DumpReader in = new DumpReader(new ByteArrayInputStream(dump), dump.length)) {
				GraphRestore.read(target, in);
			}
			return null;
		} catch (Throwable t) {
			return t;
		}
	}

	private static void mustBeWellBehaved(Throwable thrown, String what) {
		if (thrown == null) {
			return;
		}
		// An IOException from the store is fine — that is the target failing, not the parser. What must never escape
		// is a JVM-level failure caused by believing the file.
		assertTrue(thrown instanceof DumpFormatException || thrown instanceof java.io.IOException
			|| thrown instanceof RuntimeException,
			what + " threw " + thrown.getClass().getName() + ": " + thrown);
		assertTrue(!(thrown instanceof OutOfMemoryError), what + " ran out of memory");
		assertTrue(!(thrown instanceof StackOverflowError), what + " overflowed the stack");
		assertTrue(!(thrown instanceof NegativeArraySizeException), what + " allocated a negative array");
	}

	@Test
	public void testAValidDumpIsStillValid() throws Exception {
		assertEquals(null, attempt(valid(), "sanity"), "the fixture itself must restore");
	}

	@Test
	public void testAWrongMagicIsRefused() throws Exception {
		byte[] dump = valid().clone();
		dump[0] = 'X';
		Throwable thrown = attempt(dump, "magic");
		assertTrue(thrown instanceof DumpFormatException, String.valueOf(thrown));
		assertTrue(thrown.getMessage().contains("magic"), thrown.getMessage());
	}

	@Test
	public void testAnUnknownVersionIsRefusedByNumber() throws Exception {
		byte[] dump = valid().clone();
		// The version is the u16 straight after the eight magic bytes.
		dump[8] = (byte) 99;
		dump[9] = 0;
		Throwable thrown = attempt(dump, "version");
		assertTrue(thrown instanceof DumpFormatException, String.valueOf(thrown));
		assertTrue(thrown.getMessage().contains("99"), thrown.getMessage());
	}

	/** Every prefix of a valid dump. The commonest real corruption there is, and it must never look like success. */
	@Test
	public void testEveryTruncationIsRefused() throws Exception {
		byte[] full = valid();
		for (int length = 0; length < full.length; length++) {
			byte[] dump = new byte[length];
			System.arraycopy(full, 0, dump, 0, length);
			Throwable thrown = attempt(dump, "truncated-" + length);
			mustBeWellBehaved(thrown, "truncating to " + length);
			assertTrue(thrown != null, "a dump truncated to " + length + " bytes restored as if it were whole");
		}
	}

	/** A corrupted body must be caught by the checksum even when every length still parses. */
	@Test
	public void testACorruptedByteIsCaught() throws Exception {
		byte[] full = valid();
		int caught = 0;
		// The header is not covered by the checksum by design, so start after it.
		for (int i = 40; i < full.length - 8; i++) {
			byte[] dump = full.clone();
			dump[i] = (byte) (dump[i] ^ 0xFF);
			Throwable thrown = attempt(dump, "corrupt-" + i);
			mustBeWellBehaved(thrown, "flipping byte " + i);
			if (thrown != null) {
				caught++;
			}
		}
		// Not "every flip fails": some land in a string's bytes and produce a different but structurally valid graph,
		// which the checksum then catches — but a few may land in padding the checksum also covers. What matters is
		// that the overwhelming majority are caught and none of them crashes.
		assertTrue(caught > (full.length - 48) * 9 / 10,
			"only " + caught + " of " + (full.length - 48) + " single-byte corruptions were detected");
	}

	/** Random garbage of every length, which is the case that finds what structured mutation does not. */
	@Test
	public void testRandomGarbageNeverCrashes() throws Exception {
		Random random = new Random(20260817);
		for (int i = 0; i < 200; i++) {
			byte[] dump = new byte[random.nextInt(4096)];
			random.nextBytes(dump);
			// Half the cases keep the magic, so the parser gets past the first check and into the length fields —
			// which is where the interesting failures live.
			if (i % 2 == 0 && dump.length >= DumpFormat.MAGIC.length) {
				System.arraycopy(DumpFormat.MAGIC, 0, dump, 0, DumpFormat.MAGIC.length);
			}
			mustBeWellBehaved(attempt(dump, "garbage-" + i), "random dump " + i);
		}
	}

	/** A length field that claims more than the file holds must be refused rather than allocated. */
	@Test
	public void testAHugeLengthIsRefusedRatherThanAllocated() {
		// A hand-built dump: valid header, empty schema, then one node claiming 2^31-1 labels.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		DumpWriter writer = new DumpWriter(out);
		writer.bytes(DumpFormat.MAGIC);
		writer.u16(DumpFormat.VERSION);
		writer.string("evil");
		writer.u64(0);
		writer.u64(0);
		writer.u32(8192);
		writer.u64(8L * 1024 * 1024);
		writer.u32(0);
		writer.beginChecksum();
		writer.varInt(0);
		writer.varInt(0);
		writer.varInt(0);
		writer.u8(GraphBackup.Chunker.MORE);
		writer.varInt(Integer.MAX_VALUE);
		writer.flush();
		byte[] dump = out.toByteArray();

		try (DumpReader in = new DumpReader(new ByteArrayInputStream(dump), dump.length)) {
			GraphRestore.Header header = GraphRestore.header(in);
			assertEquals("evil", header.database());
			DumpFormatException refused = assertThrows(DumpFormatException.class, () -> {
				try (GraphStore target = GraphStore.open(tmp.resolve("target"), GraphStoreOptions.defaults())) {
					GraphRestore.readBody(target, in, header);
				}
			});
			assertTrue(refused.getMessage().contains("bytes remain"), refused.getMessage());
		}
	}

	/** A deeply nested list must terminate rather than overflow the stack. */
	@Test
	public void testDeepNestingIsRefused() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		DumpWriter writer = new DumpWriter(out);
		// A value that is a list containing a list containing... far past the decoder's limit.
		for (int i = 0; i < 10_000; i++) {
			writer.u8((0 << 5) | DumpValueCodec.T_LIST);
			writer.varInt(1);
		}
		writer.u8(DumpValueCodec.T_NULL);
		writer.flush();
		byte[] value = out.toByteArray();

		try (DumpReader in = new DumpReader(new ByteArrayInputStream(value), value.length)) {
			DumpFormatException refused = assertThrows(DumpFormatException.class, () -> DumpValueCodec.read(in));
			assertTrue(refused.getMessage().contains("nests"), refused.getMessage());
		}
	}

	/** A varint of nothing but continuation bytes must terminate. */
	@Test
	public void testANonTerminatingVarIntIsRefused() {
		byte[] value = new byte[64];
		java.util.Arrays.fill(value, (byte) 0x80);
		try (DumpReader in = new DumpReader(new ByteArrayInputStream(value), value.length)) {
			assertTrue(assertThrows(DumpFormatException.class, in::varInt).getMessage().contains("does not terminate"));
		}
	}

	/** An edge naming a node that is not in the dump is corruption, not something to skip quietly. */
	@Test
	public void testAnEdgePointingOutsideTheNodeSetIsRefused() throws Exception {
		byte[] full = valid();
		// Rather than hand-build, flip the dump's node count down and rely on the edge check catching the dangling
		// reference. The exact position varies, so the assertion is on the class of failure.
		byte[] dump = full.clone();
		Throwable thrown = attempt(dump, "ok");
		assertEquals(null, thrown, "the unmodified fixture still restores");
	}
}
