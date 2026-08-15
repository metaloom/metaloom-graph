package io.metaloom.graph.core.api;

import io.metaloom.graph.core.store.adj.PackedEdge;

/** Traversal direction. */
public enum Direction {

	OUT(PackedEdge.DIR_OUT),
	IN(PackedEdge.DIR_IN),
	BOTH(-1);

	private final int packedDir;

	Direction(int packedDir) {
		this.packedDir = packedDir;
	}

	public int packedDir() {
		if (packedDir < 0) {
			throw new IllegalStateException("BOTH has no single packed direction");
		}
		return packedDir;
	}

	public boolean matches(int dir) {
		return this == BOTH || dir == packedDir;
	}

	public Direction opposite() {
		return switch (this) {
			case OUT -> IN;
			case IN -> OUT;
			case BOTH -> BOTH;
		};
	}
}
