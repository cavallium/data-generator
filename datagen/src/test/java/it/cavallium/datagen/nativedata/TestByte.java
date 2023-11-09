package it.cavallium.datagen.nativedata;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestByte {

	@Test
	public void testByte() {
		for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
			Nullablebyte nullablebyte = Nullablebyte.of((byte) i);
			Assertions.assertEquals(i, nullablebyte.get());
			Assertions.assertSame(Nullablebyte.of((byte) i), nullablebyte);
		}
	}
}
