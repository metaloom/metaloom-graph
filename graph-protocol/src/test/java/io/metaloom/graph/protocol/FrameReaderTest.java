package io.metaloom.graph.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * Reassembly, against the delivery patterns TCP actually produces.
 *
 * <p>
 * The interesting cases are not "a frame arrives" — they are "a frame arrives in eleven pieces", "three frames arrive
 * in one piece", and "a header says the next frame is two gigabytes". A protocol that only works when each write lands
 * as one read works on localhost and nowhere else.
 * </p>
 */
@Tag("unit")
public class FrameReaderTest {

	/** A stream the test drives by hand, so delivery can be split exactly where it is interesting. */
	private static final class FakeStream implements ReadStream<Buffer> {

		private Handler<Buffer> handler;

		void deliver(Buffer buffer) {
			handler.handle(buffer);
		}

		@Override
		public ReadStream<Buffer> handler(Handler<Buffer> handler) {
			this.handler = handler;
			return this;
		}

		@Override
		public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
			return this;
		}

		@Override
		public ReadStream<Buffer> endHandler(Handler<Void> handler) {
			return this;
		}

		@Override
		public ReadStream<Buffer> pause() {
			return this;
		}

		@Override
		public ReadStream<Buffer> resume() {
			return this;
		}

		@Override
		public ReadStream<Buffer> fetch(long amount) {
			return this;
		}
	}

	private record Received(int type, int streamId, Buffer payload) {
	}

	private final List<Received> received = new ArrayList<>();
	private final List<WireFormatException> malformed = new ArrayList<>();

	private FakeStream attach() {
		FakeStream stream = new FakeStream();
		FrameReader.attach(stream,
			(type, streamId, payload) -> received.add(new Received(type, streamId, payload.copy())),
			malformed::add);
		return stream;
	}

	private static Buffer rowsFrame(int streamId, String payload) {
		return Frames.frame(Frames.ROWS, streamId, Buffer.buffer(payload));
	}

	@Test
	public void testAFrameSplitAcrossEveryByteBoundaryStillArrivesOnce() {
		Buffer whole = rowsFrame(7, "the quick brown fox");
		for (int split = 0; split <= whole.length(); split++) {
			received.clear();
			FakeStream stream = attach();
			stream.deliver(whole.slice(0, split).copy());
			stream.deliver(whole.slice(split, whole.length()).copy());

			assertEquals(1, received.size(), "split after " + split + " of " + whole.length() + " bytes");
			assertEquals(Frames.ROWS, received.get(0).type());
			assertEquals(7, received.get(0).streamId());
			assertEquals("the quick brown fox", received.get(0).payload().toString());
		}
	}

	@Test
	public void testOneByteAtATime() {
		Buffer whole = rowsFrame(1, "x".repeat(300));
		FakeStream stream = attach();
		for (int i = 0; i < whole.length(); i++) {
			stream.deliver(whole.slice(i, i + 1).copy());
		}
		assertEquals(1, received.size());
		assertEquals(300, received.get(0).payload().length());
	}

	@Test
	public void testSeveralFramesInOneChunkArriveInOrder() {
		Buffer chunk = Buffer.buffer()
			.appendBuffer(rowsFrame(1, "first"))
			.appendBuffer(Frames.frame(Frames.CANCEL, 2, Buffer.buffer()))
			.appendBuffer(rowsFrame(3, "third"));

		attach().deliver(chunk);

		assertEquals(3, received.size());
		assertEquals("first", received.get(0).payload().toString());
		// An empty payload is a legitimate frame, not the absence of one: CANCEL carries nothing but its stream id.
		assertEquals(Frames.CANCEL, received.get(1).type());
		assertEquals(2, received.get(1).streamId());
		assertEquals(0, received.get(1).payload().length());
		assertEquals("third", received.get(2).payload().toString());
	}

	@Test
	public void testAnOversizedLengthIsRefusedAndNothingAfterItIsParsed() {
		Buffer header = Buffer.buffer()
			.appendIntLE(Frames.MAX_FRAME_BYTES + 1)
			.appendByte((byte) Frames.ROWS)
			.appendByte((byte) 0)
			.appendShortLE((short) 1);
		// A legitimate frame behind the bad header, to prove the reader stops rather than resynchronising onto
		// whatever happens to look like a header next.
		attach().deliver(header.appendBuffer(rowsFrame(1, "should never be seen")));

		assertEquals(1, malformed.size());
		assertTrue(malformed.get(0).getMessage().contains("outside"), malformed.get(0).getMessage());
		assertEquals(0, received.size(), "no frame may be delivered after the peer has been declared malformed");
	}

	@Test
	public void testANegativeLengthIsRefused() {
		Buffer header = Buffer.buffer()
			.appendIntLE(-1)
			.appendByte((byte) Frames.ROWS)
			.appendByte((byte) 0)
			.appendShortLE((short) 1);
		attach().deliver(header);

		assertEquals(1, malformed.size());
		assertEquals(0, received.size());
	}

	@Test
	public void testRandomBytesNeverEscapeTheReader() {
		// Not a fuzz test's worth of input, but enough to catch a reader that throws something other than a
		// WireFormatException at whoever is holding the socket.
		Random random = new Random(20260815L);
		for (int i = 0; i < 500; i++) {
			received.clear();
			malformed.clear();
			byte[] noise = new byte[random.nextInt(256)];
			random.nextBytes(noise);
			attach().deliver(Buffer.buffer(noise));
			// Either it parsed something plausible or it complained. Both are fine; a RuntimeException reaching this
			// line is not, and is what the test is here to prevent.
		}
	}

	@Test
	public void testStreamIdsUseTheWholeUnsignedRange() {
		// A signed read of the id field would turn 40000 into a negative number and route the frame nowhere.
		attach().deliver(rowsFrame(0xFFFF, "high"));
		assertEquals(1, received.size());
		assertEquals(0xFFFF, received.get(0).streamId());
	}

	@Test
	public void testAStreamIdOutsideTheRangeIsRefusedByTheWriter() {
		WireFormatException thrown = org.junit.jupiter.api.Assertions.assertThrows(WireFormatException.class,
			() -> Frames.header(Frames.ROWS, 0x10000, 0));
		assertNotNull(thrown.getMessage());
	}
}
