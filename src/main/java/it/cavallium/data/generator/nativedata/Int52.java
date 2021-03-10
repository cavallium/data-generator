package it.cavallium.data.generator.nativedata;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public class Int52 extends Number implements Comparable<Int52> {

	private final long value;

	private Int52(long value) {
		this.value = value;
	}

	public static Int52 fromLong(long value) {
		if (value < 0) {
			throw new IllegalArgumentException("Only positive values are supported");
		}
		if (value > 0x0F_FF_FF_FF_FF_FF_FFL) {
			throw new IllegalArgumentException("Only values below or equal to " + 0xFFFFFFFFFFFFFL + " are supported");
		}
		return new Int52(value);
	}

	long getValue() {
		return value;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		Int52 int52 = (Int52) o;
		return value == int52.value;
	}

	@Override
	public int hashCode() {
		return Objects.hash(value);
	}

	@Override
	public String toString() {
		return Long.toString(value);
	}

	@Override
	public int intValue() {
		return (int) value;
	}

	@Override
	public long longValue() {
		return value;
	}

	@Override
	public float floatValue() {
		return (float) value;
	}

	@Override
	public double doubleValue() {
		return (double) value;
	}

	@Override
	public int compareTo(@NotNull Int52 o) {
		return Long.compareUnsigned(this.value, o.value);
	}
}
