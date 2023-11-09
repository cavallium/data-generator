package it.cavallium.datagen.nativedata;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestBoolean {

	@Test
	public void testBoolean() {
		var nullableTrue = Nullableboolean.of(true);
		var nullableFalse = Nullableboolean.of(false);
		var nullableNull = Nullableboolean.empty();
		Assertions.assertSame(Nullableboolean.of(true), nullableTrue);
		Assertions.assertSame(Nullableboolean.of(false), nullableFalse);
		Assertions.assertSame(Nullableboolean.ofNullable(null), nullableNull);
	}
}
