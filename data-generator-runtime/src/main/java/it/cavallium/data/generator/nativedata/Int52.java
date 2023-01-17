package it.cavallium.data.generator.nativedata;

import java.lang.annotation.Native;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public class Int52 extends Number implements Comparable<Int52> {

	/**
	 * The number of bits used to represent a {@code Int52} value in two's
	 * complement binary form.
	 */
	@Native
	public static final int SIZE = 52;

	/**
	 * The number of bytes used to represent a {@code Int52} value in two's
	 * complement binary form.
	 */
	public static final int BYTES = 7;

	public static final Int52 ZERO = new Int52(0L);
	public static final Int52 ONE = new Int52(1L);
	public static final Int52 TWO = new Int52(2L);
	public static final Int52 TEN = new Int52(10L);

	private final long value;

	private Int52(long value) {
		this.value = value;
	}

	public static Int52 fromLong(long value) {
		if (value == 0) {
			return ZERO;
		} else if (value == 1) {
			return ONE;
		} else if (value == 2) {
			return TWO;
		} else if (value == 10) {
			return TEN;
		} else if (value <= 0) {
			throw new IllegalArgumentException("Only positive values are supported");
		} else if (value > 0x0F_FF_FF_FF_FF_FF_FFL) {
			throw new IllegalArgumentException("Only values below or equal to " + 0xFFFFFFFFFFFFFL + " are supported");
		} else {
			return new Int52(value);
		}
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
		return Long.hashCode(value);
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
