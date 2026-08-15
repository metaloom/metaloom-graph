package io.metaloom.graph.server.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The one arithmetic fact an operator has to know, enforced rather than documented.
 *
 * <p>
 * The transport trial measured this exact weakness: the framed protocol held 4.7x more than gRPC under a slow client,
 * because a write queue is a local reaction to bytes already accepted while HTTP/2's window is a limit the peer grants.
 * It won on other grounds. The condition attached to that decision was that its buffering ceiling be treated as a real
 * limit and not an accident — which means a test, not a paragraph.
 * </p>
 */
@Tag("unit")
public class WireServerOptionsTest {

	@Test
	public void testTheDefaultsAreWellInsideTheCeiling() {
		WireServerOptions options = WireServerOptions.defaults();
		assertEquals(8L * (4 * 1024 * 1024 + 256 * 1024), options.maxBufferedBytes());
		assertEquals(8L * 4 * 1024 * 1024, options.maxWriteQueueBytes());
		assertTrue(options.maxBufferedBytes() < WireServerOptions.MAX_BUFFERED_BYTES / 2,
			"the defaults should leave room to be raised without immediately hitting the ceiling");
	}

	@Test
	public void testTheKernelSendBufferCountsTowardsTheBound() {
		// It is memory held for a peer that is not reading, and leaving it out of the product was not a rounding
		// error: Linux autotunes a loopback socket into the megabytes, which is how a four-megabyte result went out
		// in full against a client reading nothing while the write queue never looked full.
		WireServerOptions options = WireServerOptions.defaults();
		assertTrue(options.maxBufferedBytes() > options.maxWriteQueueBytes(),
			"the send buffer has to be inside the number an operator multiplies, or the number is not the bound");
	}

	@Test
	public void testTheFactorsAreRefusedTogetherRatherThanSeparately() {
		// Neither of these is unreasonable on its own, which is the whole point: 80 concurrent streams is a
		// throughput decision and a 40 MiB write queue is a latency decision, and nobody multiplies them.
		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
			() -> WireServerOptions.defaults()
				.withMaxConcurrentStreams(80)
				.withWriteQueueMaxBytes(40 * 1024 * 1024));

		String message = thrown.getMessage();
		assertTrue(message.contains("maxConcurrentStreams"), message);
		assertTrue(message.contains("writeQueueMaxBytes"), message);
		assertTrue(message.contains("socketSendBufferBytes"), message);
		assertTrue(message.contains("stopped reading"),
			"the message has to say what the number means, not only that it is too big: " + message);
	}

	@Test
	public void testRaisingOneFactorAloneIsStillRefusedOnceTheProductCrosses() {
		assertThrows(IllegalArgumentException.class,
			() -> WireServerOptions.defaults().withMaxConcurrentStreams(64));
		assertThrows(IllegalArgumentException.class,
			() -> WireServerOptions.defaults().withWriteQueueMaxBytes(64 * 1024 * 1024));
		assertThrows(IllegalArgumentException.class,
			() -> WireServerOptions.defaults().withSocketSendBufferBytes(32 * 1024 * 1024));
	}

	@Test
	public void testTheBindAddressIsLoopbackByDefault() {
		// A new port with no authentication and no transport security must not default to every interface.
		assertEquals("127.0.0.1", WireServerOptions.defaults().host());
	}

	@Test
	public void testTheObviouslyWrongValuesAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> WireServerOptions.defaults().withMaxConcurrentStreams(0));
		assertThrows(IllegalArgumentException.class, () -> WireServerOptions.defaults().withPort(-1));
		assertThrows(IllegalArgumentException.class, () -> WireServerOptions.defaults().withPort(65536));
		assertThrows(IllegalArgumentException.class, () -> WireServerOptions.defaults().withQueryTimeoutMillis(-1));
		assertThrows(IllegalArgumentException.class, () -> WireServerOptions.defaults().withWriteQueueMaxBytes(1024));
		assertThrows(IllegalArgumentException.class, () -> WireServerOptions.defaults().withSocketSendBufferBytes(0));
		assertThrows(IllegalArgumentException.class,
			() -> WireServerOptions.defaults().withRetentionSoftLimitBytes(-1));
	}

	@Test
	public void testZeroMeansNoLimitWhereThatIsTheDocumentedMeaning() {
		assertEquals(0, WireServerOptions.defaults().withQueryTimeoutMillis(0).queryTimeoutMillis());
		assertEquals(0, WireServerOptions.defaults().withRetentionSoftLimitBytes(0).retentionSoftLimitBytes());
		assertEquals(0, WireServerOptions.defaults().withPort(0).port());
	}
}
