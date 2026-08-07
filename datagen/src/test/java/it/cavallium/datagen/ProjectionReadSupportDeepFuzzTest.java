package it.cavallium.datagen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataInput;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataInputStream;
import it.cavallium.stream.SafeInputStream;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Fuzzes the allocation, progress, and bulk-read helpers used by generated projections. */
class ProjectionReadSupportDeepFuzzTest {

	private static final long VALIDATION_SEED = 0x4E8B_173D_A096_C25FL;
	private static final long SKIP_SEED = 0x71C5_E029_8A4D_36BFL;
	private static final long ARRAY_SEED = 0x293A_D860_5F1C_B47EL;
	private static final int VALIDATION_CASES = 50_000;
	private static final int SKIP_CASES = 35_000;
	private static final int ARRAY_CASES = 12_000;
	private static final DecodeLimits UNLIMITED = DecodeLimits.unlimited();

	@Test
	void allocationAndPayloadValidationFuzzLimitsTruncationOverflowAndFailureAtomicity() {
		var random = new Random(VALIDATION_SEED);
		for (int caseIndex = 0; caseIndex < VALIDATION_CASES; caseIndex++) {
			int remaining = random.nextInt(257);
			int elements = boundaryInt(random, 160);
			int elementBytes = boundaryInt(random, 17);
			int perArray = random.nextInt(129);
			long cumulativeArray = random.nextInt(257);
			DecodeLimits limits = new DecodeLimits(perArray, 256, cumulativeArray, 512, 16);
			BufDataInput input = BufDataInput.create(Buf.createZeroes(remaining), limits);
			int positionBefore = input.position();
			String diagnostic = diagnostic(VALIDATION_SEED, caseIndex, elements, elementBytes);

			Class<? extends Throwable> expected = allocationFailure(
					elements, elementBytes, remaining, perArray, cumulativeArray);
			if (expected == null) {
				assertDoesNotThrow(() -> ProjectionReadSupport.prepareArrayAllocation(
						input, elements, elementBytes), diagnostic);
				assertEquals(elements, input.decodeBudget().claimedArrayElements(), diagnostic);
			} else {
				assertThrows(expected, () -> ProjectionReadSupport.prepareArrayAllocation(
						input, elements, elementBytes), diagnostic);
				assertEquals(0, input.decodeBudget().claimedArrayElements(), diagnostic);
			}
			assertEquals(positionBefore, input.position(), diagnostic);

			int payloadBytes = boundaryInt(random, 320);
			int payloadLimit = random.nextInt(257);
			long cumulativePayload = random.nextInt(513);
			DecodeLimits payloadLimits = new DecodeLimits(256, payloadLimit, 512, cumulativePayload, 16);
			BufDataInput payloadInput = BufDataInput.create(Buf.createZeroes(remaining), payloadLimits);
			Class<? extends Throwable> payloadFailure = payloadFailure(
					payloadBytes, remaining, payloadLimit, cumulativePayload);
			if (payloadFailure == null) {
				assertDoesNotThrow(() -> ProjectionReadSupport.preparePayload(payloadInput, payloadBytes), diagnostic);
				assertEquals(payloadBytes, payloadInput.decodeBudget().claimedPayloadBytes(), diagnostic);
			} else {
				assertThrows(payloadFailure,
						() -> ProjectionReadSupport.preparePayload(payloadInput, payloadBytes), diagnostic);
				assertEquals(0, payloadInput.decodeBudget().claimedPayloadBytes(), diagnostic);
			}
			assertEquals(0, payloadInput.position(), diagnostic);
		}
	}

	@Test
	void skipBytesDifferentiallyFuzzesAtomicRandomAccessAndZeroProgressForwardInputs() {
		var random = new Random(SKIP_SEED);
		for (int caseIndex = 0; caseIndex < SKIP_CASES; caseIndex++) {
			byte[] bytes = new byte[random.nextInt(513)];
			random.nextBytes(bytes);
			int start = random.nextInt(bytes.length + 1);
			int requested = random.nextInt(bytes.length + 65) - 32;
			String diagnostic = diagnostic(SKIP_SEED, caseIndex, start, requested);

			BufDataInput randomAccess = BufDataInput.create(Buf.wrap(bytes), UNLIMITED);
			randomAccess.position(start);
			if (requested < 0 || requested > bytes.length - start) {
				assertThrows(MalformedDataException.class,
						() -> ProjectionReadSupport.skipBytes(randomAccess, requested), diagnostic);
				assertEquals(start, randomAccess.position(), diagnostic);
			} else {
				ProjectionReadSupport.skipBytes(randomAccess, requested);
				assertEquals(start + requested, randomAccess.position(), diagnostic);
			}

			AdversarialProgressStream hostile = new AdversarialProgressStream(bytes, start);
			SafeDataInputStream forward = new SafeDataInputStream(hostile, UNLIMITED);
			if (requested < 0) {
				assertThrows(MalformedDataException.class,
						() -> ProjectionReadSupport.skipBytes(forward, requested), diagnostic);
				assertEquals(start, hostile.position, diagnostic);
			} else if (requested > bytes.length - start) {
				assertThrows(MalformedDataException.class,
						() -> ProjectionReadSupport.skipBytes(forward, requested), diagnostic);
				assertEquals(bytes.length, hostile.position, diagnostic);
			} else {
				ProjectionReadSupport.skipBytes(forward, requested);
				assertEquals(start + requested, hostile.position, diagnostic);
			}
		}
	}

	@Test
	void everyPrimitiveBulkReaderMatchesBetweenRandomAccessAndForwardOnlyPaths() {
		var random = new Random(ARRAY_SEED);
		for (int caseIndex = 0; caseIndex < ARRAY_CASES; caseIndex++) {
			boolean[] booleans = randomBooleans(random);
			byte[] bytes = randomBytes(random);
			short[] shorts = randomShorts(random);
			char[] chars = randomChars(random);
			int[] ints = randomInts(random);
			long[] longs = randomLongs(random);
			float[] floats = randomFloats(random);
			double[] doubles = randomDoubles(random);
			BufDataOutput output = BufDataOutput.create();
			for (boolean value : booleans) output.writeBoolean(value);
			output.write(bytes);
			for (short value : shorts) output.writeShort(value);
			for (char value : chars) output.writeChar(value);
			for (int value : ints) output.writeInt(value);
			for (long value : longs) output.writeLong(value);
			for (float value : floats) output.writeFloat(value);
			for (double value : doubles) output.writeDouble(value);
			byte[] wire = output.asList().asArray().clone();
			String diagnostic = diagnostic(ARRAY_SEED, caseIndex, wire.length, ints.length);

			assertAllArrays(booleans, bytes, shorts, chars, ints, longs, floats, doubles,
					BufDataInput.create(Buf.wrap(wire), arrayLimits(booleans, bytes, shorts, chars,
							ints, longs, floats, doubles)), diagnostic + ", random-access");
			assertAllArrays(booleans, bytes, shorts, chars, ints, longs, floats, doubles,
					new SafeDataInputStream(new AdversarialProgressStream(wire, 0),
							arrayLimits(booleans, bytes, shorts, chars, ints, longs, floats, doubles)),
					diagnostic + ", forward-only");
		}
	}

	@Test
	void fixedArrayAndNullableSkipsFuzzPrefixesBudgetsSentinelsAndTruncation() {
		var random = new Random(SKIP_SEED ^ Long.MIN_VALUE);
		for (int caseIndex = 0; caseIndex < SKIP_CASES; caseIndex++) {
			int elements = random.nextInt(65);
			int width = random.nextInt(9);
			int bodyBytes = elements * width;
			byte[] body = new byte[bodyBytes];
			random.nextBytes(body);
			int sentinel = random.nextInt();
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(elements);
			output.write(body);
			output.writeInt(sentinel);
			byte[] wire = output.asList().asArray().clone();
			DecodeLimits exact = new DecodeLimits(elements, Integer.MAX_VALUE,
					elements, Long.MAX_VALUE, 4);
			String diagnostic = diagnostic(SKIP_SEED, caseIndex, elements, width);

			for (SafeDataInput input : new SafeDataInput[] {
					BufDataInput.create(Buf.wrap(wire), exact),
					new SafeDataInputStream(new AdversarialProgressStream(wire, 0), exact)}) {
				ProjectionReadSupport.skipFixedArray(input, width);
				assertEquals(sentinel, input.readInt(), diagnostic);
				assertEquals(elements, input.decodeBudget().claimedArrayElements(), diagnostic);
			}

			for (int cut = 0; cut < Integer.BYTES + bodyBytes; cut++) {
				byte[] truncated = Arrays.copyOf(wire, cut);
				BufDataInput input = BufDataInput.create(Buf.wrap(truncated), exact);
				assertThrows(MalformedDataException.class,
						() -> ProjectionReadSupport.skipFixedArray(input, width), diagnostic + ", cut=" + cut);
			}

			for (int firstByte = 0; firstByte < 256; firstByte += 17) {
				int valueSize = random.nextInt(17);
				byte[] nullable = new byte[1 + valueSize + Integer.BYTES];
				nullable[0] = (byte) firstByte;
				putInt(nullable, 1 + valueSize, sentinel);
				BufDataInput nullableInput = BufDataInput.create(Buf.wrap(nullable), UNLIMITED);
				ProjectionReadSupport.skipNullableFixed(nullableInput, valueSize);
				int expectedPosition = firstByte == 0 ? 1 : 1 + valueSize;
				assertEquals(expectedPosition, nullableInput.position(), diagnostic);
				if (firstByte != 0) assertEquals(sentinel, nullableInput.readInt(), diagnostic);
			}
		}

		BufDataInput empty = BufDataInput.create(Buf.create(), UNLIMITED);
		assertThrows(IllegalArgumentException.class,
				() -> ProjectionReadSupport.skipFixedArray(empty, -1));
		assertThrows(IllegalArgumentException.class,
				() -> ProjectionReadSupport.skipNullableFixed(empty, -1));
	}

	@Test
	void remainingAndCheckedMultiplicationFuzzKnownUnknownAndFullIntegerDomain() {
		var random = new Random(VALIDATION_SEED ^ Long.MIN_VALUE);
		for (int caseIndex = 0; caseIndex < VALIDATION_CASES; caseIndex++) {
			int remaining = random.nextInt(257);
			long required = switch (random.nextInt(6)) {
				case 0 -> -1;
				case 1 -> Long.MAX_VALUE;
				default -> random.nextInt(320);
			};
			BufDataInput known = BufDataInput.create(Buf.createZeroes(remaining), UNLIMITED);
			SafeDataInput unknown = new SafeDataInputStream(
					new AdversarialProgressStream(new byte[remaining], 0), UNLIMITED);
			String diagnostic = diagnostic(VALIDATION_SEED, caseIndex, remaining, (int) required);
			if (required < 0) {
				assertThrows(MalformedDataException.class,
						() -> ProjectionReadSupport.requireRemaining(known, required), diagnostic);
				assertThrows(MalformedDataException.class,
						() -> ProjectionReadSupport.requireRemaining(unknown, required), diagnostic);
			} else {
				if (required > remaining) {
					assertThrows(MalformedDataException.class,
							() -> ProjectionReadSupport.requireRemaining(known, required), diagnostic);
				} else {
					assertDoesNotThrow(() -> ProjectionReadSupport.requireRemaining(known, required), diagnostic);
				}
				assertDoesNotThrow(() -> ProjectionReadSupport.requireRemaining(unknown, required), diagnostic);
			}

			int elements = random.nextInt();
			int width = random.nextInt();
			long product = (long) elements * width;
			if (product < Integer.MIN_VALUE || product > Integer.MAX_VALUE) {
				assertThrows(MalformedDataException.class,
						() -> ProjectionReadSupport.checkedArrayBytes(elements, width), diagnostic);
			} else {
				assertEquals((int) product,
						ProjectionReadSupport.checkedArrayBytes(elements, width), diagnostic);
			}
		}
	}

	private static Class<? extends Throwable> allocationFailure(int elements, int width,
			int remaining, int perArray, long cumulativeArray) {
		if (elements < 0) return MalformedDataException.class;
		if (width < 0) return IllegalArgumentException.class;
		long bytes = (long) elements * width;
		if (bytes > Integer.MAX_VALUE) return MalformedDataException.class;
		if (bytes > remaining) return MalformedDataException.class;
		if (elements > perArray || elements > cumulativeArray) return DecodeLimitExceededException.class;
		return null;
	}

	private static Class<? extends Throwable> payloadFailure(int bytes, int remaining,
			int perPayload, long cumulativePayload) {
		if (bytes < 0) return MalformedDataException.class;
		if (bytes > remaining) return MalformedDataException.class;
		if (bytes > perPayload || bytes > cumulativePayload) return DecodeLimitExceededException.class;
		return null;
	}

	private static int boundaryInt(Random random, int ordinaryBound) {
		return switch (random.nextInt(12)) {
			case 0 -> -1;
			case 1 -> Integer.MIN_VALUE;
			case 2 -> Integer.MAX_VALUE;
			case 3 -> 0;
			case 4 -> 1;
			default -> random.nextInt(ordinaryBound) - 8;
		};
	}

	private static DecodeLimits arrayLimits(Object... arrays) {
		long total = 0;
		int maximum = 0;
		for (Object array : arrays) {
			int length = java.lang.reflect.Array.getLength(array);
			total += length;
			maximum = Math.max(maximum, length);
		}
		return new DecodeLimits(maximum, Integer.MAX_VALUE, total, Long.MAX_VALUE, 16);
	}

	private static void assertAllArrays(boolean[] booleans, byte[] bytes, short[] shorts,
			char[] chars, int[] ints, long[] longs, float[] floats, double[] doubles,
			SafeDataInput input, String diagnostic) {
		assertArrayEquals(booleans, ProjectionReadSupport.readBooleanArray(input, booleans.length), diagnostic);
		assertArrayEquals(bytes, ProjectionReadSupport.readByteArray(input, bytes.length), diagnostic);
		assertArrayEquals(shorts, ProjectionReadSupport.readShortArray(input, shorts.length), diagnostic);
		assertArrayEquals(chars, ProjectionReadSupport.readCharArray(input, chars.length), diagnostic);
		assertArrayEquals(ints, ProjectionReadSupport.readIntArray(input, ints.length), diagnostic);
		assertArrayEquals(longs, ProjectionReadSupport.readLongArray(input, longs.length), diagnostic);
		assertFloatBits(floats, ProjectionReadSupport.readFloatArray(input, floats.length), diagnostic);
		assertDoubleBits(doubles, ProjectionReadSupport.readDoubleArray(input, doubles.length), diagnostic);
		long remaining = input.remainingBytesIfKnown();
		assertTrue(remaining == 0 || remaining == -1, diagnostic);
	}

	private static boolean[] randomBooleans(Random random) {
		boolean[] values = new boolean[random.nextInt(33)];
		for (int i = 0; i < values.length; i++) values[i] = random.nextBoolean();
		return values;
	}

	private static byte[] randomBytes(Random random) {
		byte[] values = new byte[random.nextInt(65)];
		random.nextBytes(values);
		return values;
	}

	private static short[] randomShorts(Random random) {
		short[] values = new short[random.nextInt(33)];
		for (int i = 0; i < values.length; i++) values[i] = (short) random.nextInt();
		return values;
	}

	private static char[] randomChars(Random random) {
		char[] values = new char[random.nextInt(33)];
		for (int i = 0; i < values.length; i++) values[i] = (char) random.nextInt();
		return values;
	}

	private static int[] randomInts(Random random) {
		int[] values = new int[random.nextInt(33)];
		for (int i = 0; i < values.length; i++) values[i] = random.nextInt();
		return values;
	}

	private static long[] randomLongs(Random random) {
		long[] values = new long[random.nextInt(33)];
		for (int i = 0; i < values.length; i++) values[i] = random.nextLong();
		return values;
	}

	private static float[] randomFloats(Random random) {
		float[] values = new float[random.nextInt(33)];
		for (int i = 0; i < values.length; i++) values[i] = Float.intBitsToFloat(random.nextInt());
		return values;
	}

	private static double[] randomDoubles(Random random) {
		double[] values = new double[random.nextInt(33)];
		for (int i = 0; i < values.length; i++) values[i] = Double.longBitsToDouble(random.nextLong());
		return values;
	}

	private static void assertFloatBits(float[] expected, float[] actual, String diagnostic) {
		assertEquals(expected.length, actual.length, diagnostic);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(Float.floatToIntBits(expected[i]), Float.floatToIntBits(actual[i]), diagnostic + ", i=" + i);
		}
	}

	private static void assertDoubleBits(double[] expected, double[] actual, String diagnostic) {
		assertEquals(expected.length, actual.length, diagnostic);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(Double.doubleToLongBits(expected[i]), Double.doubleToLongBits(actual[i]), diagnostic + ", i=" + i);
		}
	}

	private static void putInt(byte[] bytes, int offset, int value) {
		bytes[offset] = (byte) (value >>> 24);
		bytes[offset + 1] = (byte) (value >>> 16);
		bytes[offset + 2] = (byte) (value >>> 8);
		bytes[offset + 3] = (byte) value;
	}

	private static String diagnostic(long seed, int caseIndex, int first, int second) {
		return "seed=" + seed + ", case=" + caseIndex + ", first=" + first + ", second=" + second;
	}

	/** Returns zero from alternating bulk reads/skips to force every progress fallback. */
	private static final class AdversarialProgressStream extends SafeInputStream {
		private final byte[] bytes;
		private int position;
		private boolean zeroRead = true;
		private boolean zeroSkip = true;

		private AdversarialProgressStream(byte[] bytes, int position) {
			this.bytes = bytes;
			this.position = position;
		}

		@Override
		public int read() {
			return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]);
		}

		@Override
		public int read(byte[] destination, int offset, int length) {
			if (length == 0) return 0;
			if (position == bytes.length) return -1;
			if (zeroRead) {
				zeroRead = false;
				return 0;
			}
			zeroRead = true;
			int copied = Math.min(Math.min(length, 3), bytes.length - position);
			System.arraycopy(bytes, position, destination, offset, copied);
			position += copied;
			return copied;
		}

		@Override
		public long skip(long count) {
			if (count <= 0 || position == bytes.length) return 0;
			if (zeroSkip) {
				zeroSkip = false;
				return 0;
			}
			zeroSkip = true;
			int skipped = (int) Math.min(Math.min(count, 5), bytes.length - position);
			position += skipped;
			return skipped;
		}
	}
}
