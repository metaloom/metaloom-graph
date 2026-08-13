package io.metaloom.graph.core.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

public class MemoryLayoutAPITest {

	public static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
		ValueLayout.JAVA_CHAR.withName("a"),
		ValueLayout.JAVA_CHAR.withName("b"));

	public static final VarHandle A_HANDLE = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("a"));
	public static final VarHandle B_HANDLE = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("a"));

	@Test
	public void testAPI() {
		// Define the key and value strings
		String key = "myKey";
		String value = "myValue";

		Arena arena = Arena.ofShared();

		// Allocate memory for the key and value strings
		MemorySegment keySegment = arena.allocateFrom(key, StandardCharsets.UTF_8);
		MemorySegment valueSegment = arena.allocateFrom(value, StandardCharsets.UTF_8);

		// Write the key and value strings into memory
		keySegment.copyFrom(MemorySegment.ofArray(key.getBytes()));
		valueSegment.copyFrom(MemorySegment.ofArray(value.getBytes()));

		A_HANDLE.set(keySegment, 0L, 'R');

		// Print the key and value strings
		System.out.println("Key: " + read(keySegment));
		System.out.println("Value: " + read(valueSegment));

		MemorySegment readSegment = MemorySegment.ofAddress(keySegment.address());
		readSegment = readSegment.reinterpret(256);
		System.out.println("Key (read again): " + read(readSegment));
	}

	private String read(MemorySegment keySegment) {
		String value = keySegment.getString(0, StandardCharsets.UTF_8);
		return value;
	}
}
