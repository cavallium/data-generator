package it.cavallium.datagen.nativedata;

import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeByteArrayOutputStream;
import it.cavallium.stream.SafeDataInputStream;
import it.cavallium.stream.SafeDataOutputStream;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestInt52Serializer {

	@Test
	public void testInt52Serialization() {
		for (int i = 0; i <= 300; i++) {
			testInt52Serialization(i);
		}
		testInt52Serialization(0xF_FF_FF_FF_FF_FF_FFL);
		testInt52Serialization(1099511627775L);
		testInt52Serialization(999619292661L);
	}

	public void testInt52Serialization(long n) {
		var serializer = new Int52Serializer();
		byte[] out;
		try (var baos = new SafeByteArrayOutputStream()) {
			try (var dos = new SafeDataOutputStream(baos)) {
				serializer.serialize(dos, Int52.fromLong(n));
			}
			out = baos.toByteArray();
		}

		var bais = new SafeByteArrayInputStream(out);
		var dis = new SafeDataInputStream(bais);
		Assertions.assertEquals(n, serializer.deserialize(dis).longValue(), "Deserialized number differ");
	}

	@Test
	public void testInt52OptionalSerialization() {
		testInt52OptionalSerialization(null);
		for (long i = 0; i <= 300; i++) {
			testInt52OptionalSerialization(i);
		}
		testInt52OptionalSerialization(0xF_FF_FF_FF_FF_FF_FFL);
	}

	public void testInt52OptionalSerialization(@Nullable Long n) {
		var serializer = new NullableInt52Serializer();
		byte[] out;
		try (var baos = new SafeByteArrayOutputStream()) {
			try (var dos = new SafeDataOutputStream(baos)) {
				if (n == null) {
					serializer.serialize(dos, NullableInt52.empty());
				} else {
					serializer.serialize(dos, NullableInt52.of(Int52.fromLong(n)));
				}
			}
			out = baos.toByteArray();
		}

		var bais = new SafeByteArrayInputStream(out);
		var dis = new SafeDataInputStream(bais);
		if (n == null) {
			Assertions.assertNull(serializer.deserialize(dis).getNullable(), "Deserialized number is not empty");
		} else {
			Assertions.assertEquals(n, serializer.deserialize(dis).get().longValue(), "Deserialized number differ");
		}
	}

}
