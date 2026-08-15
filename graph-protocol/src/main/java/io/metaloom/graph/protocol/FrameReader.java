package io.metaloom.graph.protocol;

import java.util.function.Consumer;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.parsetools.RecordParser;
import io.vertx.core.streams.ReadStream;

/**
 * Turns a byte stream back into frames.
 *
 * <p>
 * Written once and used by both ends, because reassembly is the part of a hand-rolled protocol that is most often got
 * wrong and least often noticed: TCP delivers whatever it feels like, and a frame arrives in three pieces as readily as
 * in one. Two copies of this would be two chances to get it wrong and one chance to notice.
 * </p>
 *
 * <p>
 * It is also the only place in either process that acts on a length field written by the peer, which is why
 * {@link Frames#checkLength} is called <b>before</b> the parser is told to expect that many bytes rather than after.
 * A malformed length ends this connection through {@code onMalformed} and touches no other.
 * </p>
 */
public final class FrameReader {

	@FunctionalInterface
	public interface FrameHandler {

		/**
		 * @param payload
		 *            valid for the duration of the call. A handler that keeps it must copy — Vert.x owns the buffer
		 *            underneath.
		 */
		void frame(int type, int streamId, Buffer payload);
	}

	private FrameReader() {
	}

	/**
	 * Reads frames off {@code stream} until it ends or says something that is not this protocol.
	 *
	 * @param onMalformed
	 *            called at most once, after which no further frame is delivered. The caller closes the connection; this
	 *            does not, because what to do about a peer talking nonsense is a policy decision and the two ends make
	 *            it differently.
	 */
	public static void attach(ReadStream<Buffer> stream, FrameHandler handler, Consumer<WireFormatException> onMalformed) {
		RecordParser parser = RecordParser.newFixed(Frames.HEADER_BYTES);
		parser.handler(new Handler<>() {

			/** -1 while the next record is a header, otherwise the type of the payload being awaited. */
			private int awaitingType = -1;
			private int awaitingStream;
			private boolean failed;

			@Override
			public void handle(Buffer record) {
				if (failed) {
					// RecordParser can deliver several records from one chunk. Once the peer has been declared
					// malformed, the rest of that chunk is not worth parsing and must not reach the handler.
					return;
				}
				try {
					if (awaitingType < 0) {
						readHeader(record);
					} else {
						int type = awaitingType;
						int streamId = awaitingStream;
						awaitingType = -1;
						parser.fixedSizeMode(Frames.HEADER_BYTES);
						handler.frame(type, streamId, record);
					}
				} catch (WireFormatException e) {
					failed = true;
					onMalformed.accept(e);
				}
			}

			private void readHeader(Buffer header) {
				int length = header.getIntLE(0);
				int type = header.getUnsignedByte(4);
				int streamId = header.getUnsignedShortLE(6);
				// Before fixedSizeMode, not after. Telling the parser to wait for a length nobody has checked is the
				// whole of the unvalidated-length-field defect, wearing a framework's name.
				Frames.checkLength(length);
				if (length == 0) {
					handler.frame(type, streamId, Buffer.buffer(0));
					return;
				}
				awaitingType = type;
				awaitingStream = streamId;
				parser.fixedSizeMode(length);
			}
		});
		stream.handler(parser);
	}
}
