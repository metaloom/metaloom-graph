package io.metaloom.graph.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.graph.core.fixture.TempStore;

/**
 * {@link ForwardingGraphStore} is the one way to decorate a sealed {@link GraphStore}: it must forward everything,
 * let an override observe, and close what it wraps.
 */
public class ForwardingGraphStoreTest {

	@RegisterExtension
	TempStore stores = new TempStore();

	@Test
	public void testADecoratorObservesAndForwards() throws Exception {
		GraphStore store = stores.open();
		AtomicInteger created = new AtomicInteger();
		try (GraphStore counting = new ForwardingGraphStore(store) {

			@Override
			public long createNode(String label) throws IOException {
				created.incrementAndGet();
				return super.createNode(label);
			}
		}) {
			long id = counting.createNode("Person");
			assertEquals(1, created.get(), "the override saw the call");
			assertEquals(List.of(id), store.nodesByLabel("Person"), "and it reached the wrapped store");
			assertEquals(List.of(id), counting.nodesByLabel("Person"), "an unoverridden method is forwarded");
			try (Transaction txn = counting.begin()) {
				counting.createNode("Person");
				txn.commit();
			}
			assertEquals(2, store.nodesByLabel("Person").size());
		}
		assertThrows(GraphStoreException.class, () -> store.nodesByLabel("Person"),
			"closing the decorator closes the store it wraps");
	}

	/**
	 * Concrete, so the compiler already refuses a missing forward; this pins that it stays concrete and that every
	 * method is overridden here rather than inherited as a default, which would skip the delegate.
	 */
	@Test
	public void testEveryMethodIsForwardedRatherThanInherited() throws Exception {
		assertTrue(!Modifier.isAbstract(ForwardingGraphStore.class.getModifiers()), "ForwardingGraphStore must stay concrete");
		for (Method method : GraphStore.class.getMethods()) {
			if (Modifier.isStatic(method.getModifiers()) || method.isDefault()) {
				continue;
			}
			Method forwarded = ForwardingGraphStore.class.getMethod(method.getName(), method.getParameterTypes());
			assertEquals(ForwardingGraphStore.class, forwarded.getDeclaringClass(),
				method + " is not forwarded by ForwardingGraphStore");
		}
	}
}
