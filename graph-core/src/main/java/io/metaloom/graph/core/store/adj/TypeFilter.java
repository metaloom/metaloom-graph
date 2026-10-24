package io.metaloom.graph.core.store.adj;

import java.util.Arrays;
import java.util.Collection;

/**
 * A relationship-type filter resolved once per query into primitive form.
 * <p>
 * A traversal asks "is this type admitted" once per adjacency entry, and asking a {@code Set<Integer>} costs a box, a
 * hash and an {@code equals} each time. This answers from a bitset over the ids, and the block-level question from a
 * precomputed mask over {@link AdjacencyBlock#typeBit}, so neither iterates anything per entry or per block.
 * </p>
 * <p>
 * Null stands for "no filter" wherever a {@code TypeFilter} is accepted, exactly as a null set did. A filter whose names
 * all resolved to nothing is not null: it admits nothing, which is what a filter on an unknown type means.
 * </p>
 */
public final class TypeFilter {

	private final long[] bits;
	private final long blockMask;

	private TypeFilter(long[] bits, long blockMask) {
		this.bits = bits;
		this.blockMask = blockMask;
	}

	/** A filter admitting exactly these ids. Ids of zero or below are ignored, as no relationship has one. */
	public static TypeFilter of(Collection<Integer> typeIds) {
		int max = 0;
		for (int typeId : typeIds) {
			max = Math.max(max, typeId);
		}
		long[] bits = new long[(max >>> 6) + 1];
		long mask = 0;
		for (int typeId : typeIds) {
			if (typeId > 0) {
				bits[typeId >>> 6] |= 1L << typeId;
				mask |= AdjacencyBlock.typeBit(typeId);
			}
		}
		return new TypeFilter(bits, mask);
	}

	public static TypeFilter of(int... typeIds) {
		return of(Arrays.stream(typeIds).boxed().toList());
	}

	public boolean admits(int typeId) {
		int word = typeId >>> 6;
		return typeId > 0 && word < bits.length && (bits[word] & (1L << typeId)) != 0;
	}

	/** The OR of {@link AdjacencyBlock#typeBit} over every admitted id, for the block-level skip. */
	long blockMask() {
		return blockMask;
	}
}
