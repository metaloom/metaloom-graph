package io.metaloom.graph.core.page;

/**
 * Publishes the active transaction's overlay to the segment layer, but only to the thread that owns it.
 * <p>
 * This is what keeps a writer's uncommitted changes invisible to concurrent readers: the overlay is reachable only
 * from the writing thread, and every other thread reads the mapped file.
 */
public final class OverlayHolder {

	private volatile Thread owner;
	private volatile PageOverlay overlay;

	public void set(PageOverlay overlay) {
		this.overlay = overlay;
		this.owner = Thread.currentThread();
	}

	public void clear() {
		this.owner = null;
		this.overlay = null;
	}

	/** The overlay for the calling thread, or null. */
	public PageOverlay current() {
		return Thread.currentThread() == owner ? overlay : null;
	}

	public boolean isActive() {
		return owner != null;
	}
}
