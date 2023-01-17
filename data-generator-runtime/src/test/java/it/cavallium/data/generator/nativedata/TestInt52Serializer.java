package it.cavallium.data.generator.nativedata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestInt52Serializer {

	@Test
	public void testInt52Serialization() throws IOException {
		for (int i = 0; i <= 300; i++) {
			testInt52Serialization(i);
		}
		testInt52Serialization(0xF_FF_FF_FF_FF_FF_FFL);
		testInt52Serialization(1099511627775L);
		testInt52Serialization(999619292661L);
	}

	public void testInt52Serialization(long n) throws IOException {
		var serializer = new Int52Serializer();
		byte[] out;
		try (var baos = new ByteArrayOutputStream()) {
			try (var dos = new DataOutputStream(baos)) {
				serializer.serialize(dos, Int52.fromLong(n));
			}
			out = baos.toByteArray();
		}

		var bais = new ByteArrayInputStream(out);
		var dis = new DataInputStream(bais);
		Assertions.assertEquals(n, serializer.deserialize(dis).longValue(), "Deserialized number differ");
	}

	@Test
	public void testInt52OptionalSerialization() throws IOException {
		testInt52OptionalSerialization(null);
		for (long i = 0; i <= 300; i++) {
			testInt52OptionalSerialization(i);
		}
		testInt52OptionalSerialization(0xF_FF_FF_FF_FF_FF_FFL);
	}

	public void testInt52OptionalSerialization(@Nullable Long n) throws IOException {
		var serializer = new NullableInt52Serializer();
		byte[] out;
		try (var baos = new ByteArrayOutputStream()) {
			try (var dos = new DataOutputStream(baos)) {
				if (n == null) {
					serializer.serialize(dos, NullableInt52.empty());
				} else {
					serializer.serialize(dos, NullableInt52.of(Int52.fromLong(n)));
				}
			}
			out = baos.toByteArray();
		}

		var bais = new ByteArrayInputStream(out);
		var dis = new DataInputStream(bais);
		if (n == null) {
			Assertions.assertNull(serializer.deserialize(dis).getNullable(), "Deserialized number is not empty");
		} else {
			Assertions.assertEquals(n, serializer.deserialize(dis).get().longValue(), "Deserialized number differ");
		}
	}

}
