package io.metaloom.graph.core.util;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Explicitly little-endian, unaligned value layouts.
 * <p>
 * Every on-disk field in this project is accessed through one of these. Unaligned access is required because record
 * fields sit at arbitrary offsets within a mapped segment, and the explicit byte order makes a store written on one
 * machine readable on another (invariant I5 in spec/FILE_FORMAT.md).
 */
public final class Layouts {

	public static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

	public static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	public static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	public static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	public static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	public static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private Layouts() {
	}
}
