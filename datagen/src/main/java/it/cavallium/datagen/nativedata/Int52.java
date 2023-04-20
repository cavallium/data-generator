package it.cavallium.datagen.nativedata;

import java.lang.annotation.Native;
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
	public static final long MAX_VALUE_L = 0x0F_FF_FF_FF_FF_FF_FFL;
	public static final long MIN_VALUE_L = 0;
	public static final Int52 MAX_VALUE = fromLongSafe(MAX_VALUE_L);
	public static final Int52 MIN_VALUE = ZERO;

	private final long value;

	private Int52(long value) {
		this.value = value;
	}

	public static void checkValidity(long value) {
		if (value < 0 || value > MAX_VALUE_L) {
			throw new IllegalArgumentException("Only positive values below or equal to " + MAX_VALUE_L + " are supported: " + value);
		}
	}

	public static Int52 fromLong(long value) {
		checkValidity(value);
		return fromLongSafe(value);
	}

	private static Int52 fromLongSafe(long value) {
		if (value == 0) {
			return ZERO;
		} else if (value == 1) {
			return ONE;
		} else if (value == 2) {
			return TWO;
		} else if (value == 10) {
			return TEN;
		} else {
			return new Int52(value);
		}
	}

	public static Int52 fromByteArray(byte[] bytes) {
		return fromLongSafe(fromByteArrayL(bytes));
	}

	public static Int52 fromBytes(byte b1, byte b2, byte b3, byte b4, byte b5, byte b6, byte b7) {
		return fromLongSafe(fromBytesL(b1, b2, b3, b4, b5, b6, b7));
	}

	public static long fromByteArrayL(byte[] bytes) {
		return fromBytesL(bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6]);
	}

	public static long fromBytesL(byte b1, byte b2, byte b3, byte b4, byte b5, byte b6, byte b7) {
		return (b1 & 0xFFL) << 48
				| (b2 & 0xFFL) << 40
				| (b3 & 0xFFL) << 32
				| (b4 & 0xFFL) << 24
				| (b5 & 0xFFL) << 16
				| (b6 & 0xFFL) << 8
				| (b7 & 0xFFL);
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
