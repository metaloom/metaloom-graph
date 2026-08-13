package io.metaloom.graph.core.internal.tree;

import java.lang.foreign.Arena;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

public class LongBinaryTree {

	private static final GroupLayout NODE_LAYOUT = MemoryLayout.structLayout(
		ValueLayout.JAVA_LONG.withName("key"),
		ValueLayout.JAVA_LONG.withName("value"),
		ValueLayout.ADDRESS.withName("leftChildOffset"),
		ValueLayout.ADDRESS.withName("rightChildOffset"));

	private Arena arena = Arena.ofAuto();

	class Node {
		private MemorySegment segment;

		public Node(long key, long value) {
			this.segment = arena.allocate(NODE_LAYOUT);
			NODE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("key")).set(segment, 0, (long) key);
			NODE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("value")).set(segment, 0, (long) value);
			NODE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("leftChildOffset")).set(segment, 0, MemorySegment.NULL);
			NODE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("rightChildOffset")).set(segment, 0, MemorySegment.NULL);
		}

		public Node(MemorySegment segment) {
			Objects.requireNonNull(segment);
			this.segment = segment;
		}

		public long getKey() {
			return (long) NODE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("key")).get(segment, 0);
		}

		public long getValue() {
			return (long) NODE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("value")).get(segment, 0);
		}

		public void setValue(long value) {
			NODE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("value")).set(segment, 0, (long) value);
		}

		public Node getLeft() {
			MemorySegment left = (MemorySegment) NODE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("leftChildOffset"))
				.get(segment, 0);
			if (left.address() == 0) {
				return null;
			}
			left = left.reinterpret(NODE_LAYOUT.byteSize());
			return new Node(left);
		}

		public Node getRight() {
			MemorySegment right = (MemorySegment) NODE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("rightChildOffset"))
				.get(segment, 0);
			if (right.address() == 0) {
				return null;
			}
			right = right.reinterpret(NODE_LAYOUT.byteSize());
			if (right == null) {
				return null;
			}
			return new Node(right);
		}

		public void setLeft(Node node) {
			NODE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("leftChildOffset"))
				.set(segment, 0, node.segment);
		}

		public void setRight(Node node) {
			NODE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("rightChildOffset"))
				.set(segment, 0, node.segment);
		}
	}

	Node root;

	public LongBinaryTree() {
		this.root = null;
	}

	// Method to insert a new node
	public void insert(long key, long value) {
		root = insertRecursive(root, key, value);
	}

	// Recursive helper method for insertion
	private Node insertRecursive(Node current, long key, long value) {
		if (current == null) {
			return new Node(key, value);
		}

		if (key < current.getKey()) {
			current.setLeft(insertRecursive(current.getLeft(), key, value));
		} else if (key > current.getKey()) {
			current.setRight(insertRecursive(current.getRight(), key, value));
		} else {
			// If the key already exists, update its value
			current.setValue(value);
		}

		return current;
	}

	/**
	 * Recursively search the given key.
	 * 
	 * @param key
	 * @return
	 */
	public Long search(long key) {
		return searchRecursive(root, key);
	}

	private Long searchRecursive(Node current, long key) {
		// The key was not found
		if (current == null) {
			return null;
		}

		// Key located - return the value
		if (key == current.getKey()) {
			return current.getValue();
		}

		// Continue the recursion
		if (key < current.getKey()) {
			return searchRecursive(current.getLeft(), key);
		} else {
			return searchRecursive(current.getRight(), key);
		}
	}

	// Method for in-order traversal
	public void inOrderTraversal() {
		inOrderTraversalRecursive(root);
	}

	// Recursive helper method for in-order traversal
	private void inOrderTraversalRecursive(Node current) {
		if (current != null) {
			inOrderTraversalRecursive(current.getLeft());
			System.out.println("Key: " + current.getKey() + ", Value: " + current.getValue());
			inOrderTraversalRecursive(current.getRight());
		}
	}

	// Method for pre-order traversal
	public void preOrderTraversal() {
		preOrderTraversalRecursive(root);
	}

	// Recursive helper method for pre-order traversal
	private void preOrderTraversalRecursive(Node current) {
		if (current != null) {
			System.out.println("Key: " + current.getKey() + ", Value: " + current.getValue());
			preOrderTraversalRecursive(current.getLeft());
			preOrderTraversalRecursive(current.getRight());
		}
	}

	// Method for post-order traversal
	public void postOrderTraversal() {
		postOrderTraversalRecursive(root);
	}

	// Recursive helper method for post-order traversal
	private void postOrderTraversalRecursive(Node current) {
		if (current != null) {
			postOrderTraversalRecursive(current.getLeft());
			postOrderTraversalRecursive(current.getRight());
			System.out.println("Key: " + current.getKey() + ", Value: " + current.getValue());
		}
	}

}
