package io.metaloom.graph.core.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

public class MemoryAPITest {

	public static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
		MemoryLayout.sequenceLayout(256, ValueLayout.JAVA_CHAR.withName("key")),
		MemoryLayout.sequenceLayout(256, ValueLayout.JAVA_CHAR.withName("value")));



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
