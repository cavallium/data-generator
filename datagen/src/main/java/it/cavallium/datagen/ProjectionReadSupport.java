package it.cavallium.datagen;

import it.cavallium.buffer.RandomAccessDataInput;
import it.cavallium.stream.SafeDataInput;

/** Runtime helpers used by generated projection readers. */
public final class ProjectionReadSupport {

	private ProjectionReadSupport() {}

	public static void skipBytes(SafeDataInput input, int length) {
		if (length < 0) {
			throw new MalformedDataException("Negative skip length: " + length);
		}
		if (input instanceof RandomAccessDataInput randomInput) {
			randomInput.skipExact(length);
			return;
		}
		int remaining = length;
		while (remaining != 0) {
			int skipped = input.skipBytes(remaining);
			if (skipped <= 0) {
				input.readByte();
				remaining--;
				continue;
			}
			remaining -= skipped;
		}
	}

	public static int readLength(SafeDataInput input) {
		int length = input.readInt();
		if (length < 0) {
			throw new MalformedDataException("Negative length: " + length);
		}
		return length;
	}

	/**
	 * Validates a prefix-driven array before allocation. The minimum serialized size may be zero;
	 * the mandatory element budget still bounds such arrays.
	 */
	public static void prepareArrayAllocation(SafeDataInput input, int elements, int minimumElementBytes) {
		if (elements < 0) {
			throw new MalformedDataException("Negative array length: " + elements);
		}
		if (minimumElementBytes < 0) {
			throw new IllegalArgumentException("Negative minimum element size: " + minimumElementBytes);
		}
		final int minimumBodyBytes;
		try {
			minimumBodyBytes = Math.multiplyExact(elements, minimumElementBytes);
		} catch (ArithmeticException exception) {
			throw new MalformedDataException("Array minimum serialized size overflow: " + elements
					+ " * " + minimumElementBytes, exception);
		}
		requireRemaining(input, minimumBodyBytes);
		input.decodeBudget().claimArrayElements(elements);
	}

	/** Validates one payload before allocating or consuming its bytes. */
	public static void preparePayload(SafeDataInput input, int bytes) {
		if (bytes < 0) {
			throw new MalformedDataException("Negative payload length: " + bytes);
		}
		requireRemaining(input, bytes);
		input.decodeBudget().claimPayloadBytes(bytes);
	}

	/** Requires exact bytes only when the input can report its remaining length. */
	public static void requireRemaining(SafeDataInput input, long requiredBytes) {
		if (requiredBytes < 0) {
			throw new MalformedDataException("Negative required byte count: " + requiredBytes);
		}
		long remaining = input.remainingBytesIfKnown();
		if (remaining >= 0 && requiredBytes > remaining) {
			throw new MalformedDataException("Truncated input: need " + requiredBytes
					+ " bytes, have " + remaining);
		}
	}

	public static void skipPayload(SafeDataInput input, int bytes) {
		preparePayload(input, bytes);
		skipBytes(input, bytes);
	}

	/** Reads a non-negative element count and skips one fixed-width encoded array body. */
	public static void skipFixedArray(SafeDataInput input, int elementSize) {
		if (elementSize < 0) {
			throw new IllegalArgumentException("Negative element size: " + elementSize);
		}
		int elements = readLength(input);
		prepareArrayAllocation(input, elements, elementSize);
		skipBytes(input, checkedArrayBytes(elements, elementSize));
	}

	/** Skips the payload of a boolean-tagged nullable fixed-width value. */
	public static void skipNullableFixed(SafeDataInput input, int valueSize) {
		if (valueSize < 0) {
			throw new IllegalArgumentException("Negative value size: " + valueSize);
		}
		if (input.readBoolean()) {
			skipBytes(input, valueSize);
		}
	}

	public static void readBooleans(SafeDataInput input, boolean[] destination) {
		if (input instanceof RandomAccessDataInput randomInput) {
			randomInput.readBooleans(destination, 0, destination.length);
			return;
		}
		for (int i = 0; i < destination.length; i++) destination[i] = input.readBoolean();
	}

	public static void readBytes(SafeDataInput input, byte[] destination) {
		input.readFully(destination);
	}

	public static void readShorts(SafeDataInput input, short[] destination) {
		if (input instanceof RandomAccessDataInput randomInput) {
			randomInput.readShorts(destination, 0, destination.length);
			return;
		}
		for (int i = 0; i < destination.length; i++) destination[i] = input.readShort();
	}

	public static void readChars(SafeDataInput input, char[] destination) {
		if (input instanceof RandomAccessDataInput randomInput) {
			randomInput.readChars(destination, 0, destination.length);
			return;
		}
		for (int i = 0; i < destination.length; i++) destination[i] = input.readChar();
	}

	public static void readInts(SafeDataInput input, int[] destination) {
		if (input instanceof RandomAccessDataInput randomInput) {
			randomInput.readInts(destination, 0, destination.length);
			return;
		}
		for (int i = 0; i < destination.length; i++) destination[i] = input.readInt();
	}

	public static void readLongs(SafeDataInput input, long[] destination) {
		if (input instanceof RandomAccessDataInput randomInput) {
			randomInput.readLongs(destination, 0, destination.length);
			return;
		}
		for (int i = 0; i < destination.length; i++) destination[i] = input.readLong();
	}

	public static void readFloats(SafeDataInput input, float[] destination) {
		if (input instanceof RandomAccessDataInput randomInput) {
			randomInput.readFloats(destination, 0, destination.length);
			return;
		}
		for (int i = 0; i < destination.length; i++) destination[i] = input.readFloat();
	}

	public static void readDoubles(SafeDataInput input, double[] destination) {
		if (input instanceof RandomAccessDataInput randomInput) {
			randomInput.readDoubles(destination, 0, destination.length);
			return;
		}
		for (int i = 0; i < destination.length; i++) destination[i] = input.readDouble();
	}

	public static boolean[] readBooleanArray(SafeDataInput input, int length) {
		if (input instanceof RandomAccessDataInput randomInput) return randomInput.readBooleanArray(length);
		prepareArrayAllocation(input, length, Byte.BYTES);
		boolean[] result = new boolean[length];
		readBooleans(input, result);
		return result;
	}

	public static byte[] readByteArray(SafeDataInput input, int length) {
		if (input instanceof RandomAccessDataInput randomInput) return randomInput.readByteArray(length);
		prepareArrayAllocation(input, length, Byte.BYTES);
		byte[] result = new byte[length];
		readBytes(input, result);
		return result;
	}

	public static short[] readShortArray(SafeDataInput input, int length) {
		if (input instanceof RandomAccessDataInput randomInput) return randomInput.readShortArray(length);
		prepareArrayAllocation(input, length, Short.BYTES);
		short[] result = new short[length];
		readShorts(input, result);
		return result;
	}

	public static char[] readCharArray(SafeDataInput input, int length) {
		if (input instanceof RandomAccessDataInput randomInput) return randomInput.readCharArray(length);
		prepareArrayAllocation(input, length, Character.BYTES);
		char[] result = new char[length];
		readChars(input, result);
		return result;
	}

	public static int[] readIntArray(SafeDataInput input, int length) {
		if (input instanceof RandomAccessDataInput randomInput) return randomInput.readIntArray(length);
		prepareArrayAllocation(input, length, Integer.BYTES);
		int[] result = new int[length];
		readInts(input, result);
		return result;
	}

	public static long[] readLongArray(SafeDataInput input, int length) {
		if (input instanceof RandomAccessDataInput randomInput) return randomInput.readLongArray(length);
		prepareArrayAllocation(input, length, Long.BYTES);
		long[] result = new long[length];
		readLongs(input, result);
		return result;
	}

	public static float[] readFloatArray(SafeDataInput input, int length) {
		if (input instanceof RandomAccessDataInput randomInput) return randomInput.readFloatArray(length);
		prepareArrayAllocation(input, length, Float.BYTES);
		float[] result = new float[length];
		readFloats(input, result);
		return result;
	}

	public static double[] readDoubleArray(SafeDataInput input, int length) {
		if (input instanceof RandomAccessDataInput randomInput) return randomInput.readDoubleArray(length);
		prepareArrayAllocation(input, length, Double.BYTES);
		double[] result = new double[length];
		readDoubles(input, result);
		return result;
	}

	public static int checkedArrayBytes(int elements, int elementBytes) {
		try {
			return Math.multiplyExact(elements, elementBytes);
		} catch (ArithmeticException exception) {
			throw new MalformedDataException("Array serialized size overflow: " + elements
					+ " * " + elementBytes, exception);
		}
	}
}
