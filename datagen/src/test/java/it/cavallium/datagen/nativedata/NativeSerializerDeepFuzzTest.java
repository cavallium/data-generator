package it.cavallium.datagen.nativedata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataInput;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.buffer.MemorySegmentBuf;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeLimitExceededException;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.datagen.ValueTooLargeException;
import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Deep differential fuzzing for every built-in native data codec. */
class NativeSerializerDeepFuzzTest {

	private static final long ROUND_TRIP_SEED = 0x6D31_8AF0_C247_5BE9L;
	private static final long TRUNCATION_SEED = 0x14E9_BC63_70A5_2DF8L;
	private static final long PREFIX_SEED = 0x58A4_1E7D_B920_C63FL;
	private static final long INT52_SEED = 0x37CF_62A1_9E48_05BDL;
	private static final int ROUND_TRIP_CASES = 18_000;
	private static final int TRUNCATION_CASES = 30_000;
	private static final int PREFIX_CASES = 12_000;
	private static final int INT52_CASES = 80_000;
	private static final DecodeLimits GENEROUS =
			new DecodeLimits(512, 8_192, 16_384, 262_144, 32);

	private static final List<CodecCase> CODECS = List.of(
			new CodecCase("string", codec(StringSerializer.INSTANCE), NativeSerializerDeepFuzzTest::randomString),
			new CodecCase("binary-string", codec(BinaryStringSerializer.INSTANCE),
					random -> new BinaryString(randomBytes(random, 257))),
			new CodecCase("int52", codec(Int52Serializer.INSTANCE), NativeSerializerDeepFuzzTest::randomInt52),
			new CodecCase("nullable-string", codec(NullableStringSerializer.INSTANCE),
					random -> random.nextBoolean() ? NullableString.empty() : NullableString.of(randomString(random))),
			new CodecCase("nullable-binary-string", codec(NullableBinaryStringSerializer.INSTANCE),
					random -> random.nextBoolean() ? NullableBinaryString.empty()
							: NullableBinaryString.of(new BinaryString(randomBytes(random, 257)))),
			new CodecCase("nullable-int52", codec(NullableInt52Serializer.INSTANCE),
					random -> random.nextBoolean() ? NullableInt52.empty() : NullableInt52.of(randomInt52(random))),
			new CodecCase("nullable-boolean", codec(NullablebooleanSerializer.INSTANCE),
					random -> random.nextInt(3) == 0 ? Nullableboolean.empty() : Nullableboolean.of(random.nextBoolean())),
			new CodecCase("nullable-byte", codec(NullablebyteSerializer.INSTANCE),
					random -> random.nextBoolean() ? Nullablebyte.empty() : Nullablebyte.of((byte) random.nextInt())),
			new CodecCase("nullable-short", codec(NullableshortSerializer.INSTANCE),
					random -> random.nextBoolean() ? Nullableshort.empty() : Nullableshort.of((short) random.nextInt())),
			new CodecCase("nullable-char", codec(NullablecharSerializer.INSTANCE),
					random -> random.nextBoolean() ? Nullablechar.empty() : Nullablechar.of((char) random.nextInt())),
			new CodecCase("nullable-int", codec(NullableintSerializer.INSTANCE),
					random -> random.nextBoolean() ? Nullableint.empty() : Nullableint.of(random.nextInt())),
			new CodecCase("nullable-long", codec(NullablelongSerializer.INSTANCE),
					random -> random.nextBoolean() ? Nullablelong.empty() : Nullablelong.of(random.nextLong())),
			new CodecCase("nullable-float", codec(NullablefloatSerializer.INSTANCE),
					random -> random.nextBoolean() ? Nullablefloat.empty()
							: Nullablefloat.of(Float.intBitsToFloat(random.nextInt()))),
			new CodecCase("nullable-double", codec(NullabledoubleSerializer.INSTANCE),
					random -> random.nextBoolean() ? Nullabledouble.empty()
							: Nullabledouble.of(Double.longBitsToDouble(random.nextLong()))),
			new CodecCase("array-binary-string", codec(new ArrayBinaryStringSerializer()),
					NativeSerializerDeepFuzzTest::randomBinaryStrings),
			new CodecCase("array-string", codec(new ArrayStringSerializer()),
					NativeSerializerDeepFuzzTest::randomStrings),
			new CodecCase("array-int52", codec(new ArrayInt52Serializer()),
					NativeSerializerDeepFuzzTest::randomInt52s),
			new CodecCase("array-boolean", codec(new ArraybooleanSerializer()),
					NativeSerializerDeepFuzzTest::randomBooleans),
			new CodecCase("array-byte", codec(new ArraybyteSerializer()),
					random -> randomBytes(random, 65)),
			new CodecCase("array-short", codec(new ArrayshortSerializer()),
					NativeSerializerDeepFuzzTest::randomShorts),
			new CodecCase("array-char", codec(new ArraycharSerializer()),
					NativeSerializerDeepFuzzTest::randomChars),
			new CodecCase("array-int", codec(new ArrayintSerializer()),
					NativeSerializerDeepFuzzTest::randomInts),
			new CodecCase("array-long", codec(new ArraylongSerializer()),
					NativeSerializerDeepFuzzTest::randomLongs),
			new CodecCase("array-float", codec(new ArrayfloatSerializer()),
					NativeSerializerDeepFuzzTest::randomFloats),
			new CodecCase("array-double", codec(new ArraydoubleSerializer()),
					NativeSerializerDeepFuzzTest::randomDoubles));

	private static final List<ArrayCase> ARRAYS = List.of(
			new ArrayCase("binary-string", codec(new ArrayBinaryStringSerializer()), Short.BYTES),
			new ArrayCase("string", codec(new ArrayStringSerializer()), Short.BYTES),
			new ArrayCase("int52", codec(new ArrayInt52Serializer()), 7),
			new ArrayCase("boolean", codec(new ArraybooleanSerializer()), 1),
			new ArrayCase("byte", codec(new ArraybyteSerializer()), 1),
			new ArrayCase("short", codec(new ArrayshortSerializer()), 2),
			new ArrayCase("char", codec(new ArraycharSerializer()), 2),
			new ArrayCase("int", codec(new ArrayintSerializer()), 4),
			new ArrayCase("long", codec(new ArraylongSerializer()), 8),
			new ArrayCase("float", codec(new ArrayfloatSerializer()), 4),
			new ArrayCase("double", codec(new ArraydoubleSerializer()), 8));

	@Test
	void allTwentyFiveCodecsRoundTripAndSkipAcrossEveryInputStorageFamily() {
		var random = new Random(ROUND_TRIP_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < ROUND_TRIP_CASES; caseIndex++) {
				CodecCase codecCase = CODECS.get(random.nextInt(CODECS.size()));
				Object value = codecCase.values().create(random);
				byte[] payload = serialize(codecCase.codec(), value);
				int prefix = 1 + random.nextInt(8);
				int suffix = 1 + random.nextInt(8);
				byte[] padded = new byte[prefix + payload.length + suffix];
				random.nextBytes(padded);
				System.arraycopy(payload, 0, padded, prefix, payload.length);
				MemorySegment segment = arena.allocate(padded.length, 1);
				segment.copyFrom(MemorySegment.ofArray(padded));
				List<Storage> storages = List.of(
						new Storage("heap", Buf.wrap(padded), prefix),
						new Storage("heap-slice", Buf.wrap(padded).subListForced(prefix,
								prefix + payload.length), 0),
						new Storage("native", new MemorySegmentBuf(segment), prefix),
						new Storage("fallback", forcedFallback(Buf.wrap(padded)), prefix));
				String diagnostic = diagnostic(ROUND_TRIP_SEED, caseIndex, codecCase.name(), payload.length);

				for (Storage storage : storages) {
					Object decoded = codecCase.codec().newReader(GENEROUS).read(
							storage.source(), storage.offset(), payload.length);
					assertValueEquals(value, decoded, diagnostic + ", storage=" + storage.name());
				}

				SafeDataInputStream forward = new SafeDataInputStream(
						new SafeByteArrayInputStream(payload), GENEROUS);
				assertValueEquals(value, codecCase.codec().read(forward), diagnostic + ", forward");
				assertEquals(0, forward.remainingBytesIfKnown(), diagnostic);

				int sentinel = random.nextInt();
				byte[] withSentinel = Arrays.copyOf(payload, payload.length + Integer.BYTES);
				putInt(withSentinel, payload.length, sentinel);
				for (SafeDataInput skipInput : new SafeDataInput[] {
						BufDataInput.create(Buf.wrap(withSentinel), GENEROUS),
						new SafeDataInputStream(new SafeByteArrayInputStream(withSentinel), GENEROUS)}) {
					codecCase.codec().skip(skipInput);
					assertEquals(sentinel, skipInput.readInt(), diagnostic + ", skip");
					assertEquals(0, skipInput.decodeBudget().structuralDepth(), diagnostic);
				}
			}
		}
	}

	@Test
	void randomizedTruncationOfEveryCodecFailsReadAndSkipThenAllowsCleanRecovery() {
		var random = new Random(TRUNCATION_SEED);
		for (int caseIndex = 0; caseIndex < TRUNCATION_CASES; caseIndex++) {
			CodecCase codecCase = CODECS.get(random.nextInt(CODECS.size()));
			Object value = codecCase.values().create(random);
			byte[] payload = serialize(codecCase.codec(), value);
			assertTrue(payload.length > 0, codecCase.name());
			int cut = random.nextInt(payload.length);
			byte[] truncated = Arrays.copyOf(payload, cut);
			String diagnostic = diagnostic(TRUNCATION_SEED, caseIndex, codecCase.name(), cut)
					+ ", fullLength=" + payload.length;

			BufDataInput readInput = BufDataInput.create(Buf.wrap(truncated), GENEROUS);
			assertThrows(MalformedDataException.class,
					() -> codecCase.codec().read(readInput), diagnostic);
			assertEquals(0, readInput.decodeBudget().structuralDepth(), diagnostic);

			BufDataInput skipInput = BufDataInput.create(Buf.wrap(truncated), GENEROUS);
			assertThrows(MalformedDataException.class,
					() -> codecCase.codec().skip(skipInput), diagnostic);
			assertEquals(0, skipInput.decodeBudget().structuralDepth(), diagnostic);

			assertValueEquals(value,
					codecCase.codec().newReader(GENEROUS).read(Buf.wrap(payload)),
					diagnostic + ", recovery");
		}
	}

	@Test
	void everyArrayCodecFuzzesNegativeHugeTruncatedExactAndOneOverPrefixes() {
		var random = new Random(PREFIX_SEED);
		for (int caseIndex = 0; caseIndex < PREFIX_CASES; caseIndex++) {
			ArrayCase arrayCase = ARRAYS.get(random.nextInt(ARRAYS.size()));
			String diagnostic = diagnostic(PREFIX_SEED, caseIndex, arrayCase.name(), arrayCase.elementBytes());
			int negative = switch (random.nextInt(4)) {
				case 0 -> -1;
				case 1 -> Integer.MIN_VALUE;
				default -> -1 - random.nextInt(Integer.MAX_VALUE);
			};
			byte[] negativeWire = intBytes(negative);
			assertMalformedReadAndSkip(arrayCase.codec(), negativeWire, GENEROUS, diagnostic + ", negative");

			int enormous = random.nextBoolean() ? Integer.MAX_VALUE : 1_000_000 + random.nextInt(1_000_000);
			byte[] enormousWire = intBytes(enormous);
			assertMalformedReadAndSkip(arrayCase.codec(), enormousWire, GENEROUS, diagnostic + ", enormous");

			int elements = 1 + random.nextInt(32);
			byte[] exactWire = new byte[Integer.BYTES + elements * arrayCase.elementBytes()];
			putInt(exactWire, 0, elements);
			DecodeLimits exact = new DecodeLimits(elements, Integer.MAX_VALUE,
					elements, Long.MAX_VALUE, 8);
			assertDoesNotThrowSkip(arrayCase.codec(), exactWire, exact, diagnostic + ", exact");

			DecodeLimits oneShort = new DecodeLimits(elements - 1, Integer.MAX_VALUE,
					elements, Long.MAX_VALUE, 8);
			BufDataInput read = BufDataInput.create(Buf.wrap(exactWire), oneShort);
			assertThrows(DecodeLimitExceededException.class,
					() -> arrayCase.codec().read(read), diagnostic + ", per-array");
			assertEquals(0, read.decodeBudget().structuralDepth(), diagnostic);
			BufDataInput skip = BufDataInput.create(Buf.wrap(exactWire), oneShort);
			assertThrows(DecodeLimitExceededException.class,
					() -> arrayCase.codec().skip(skip), diagnostic + ", per-array-skip");
			assertEquals(0, skip.decodeBudget().structuralDepth(), diagnostic);

			if (exactWire.length > Integer.BYTES) {
				byte[] shortBody = Arrays.copyOf(exactWire, exactWire.length - 1);
				assertMalformedReadAndSkip(arrayCase.codec(), shortBody, exact,
						diagnostic + ", short-body");
			}
		}
	}

	@Test
	void everyStructuralCodecRejectsDepthZeroWithoutLeakingDepthAndThenRecovers() {
		var random = new Random(PREFIX_SEED ^ Long.MIN_VALUE);
		List<CodecCase> structural = CODECS.stream()
				.filter(value -> value.name().startsWith("array-") || value.name().startsWith("nullable-"))
				.toList();
		for (int caseIndex = 0; caseIndex < PREFIX_CASES; caseIndex++) {
			CodecCase codecCase = structural.get(random.nextInt(structural.size()));
			Object value = codecCase.values().create(random);
			byte[] payload = serialize(codecCase.codec(), value);
			DecodeLimits depthZero = new DecodeLimits(512, 8_192, 16_384, 262_144, 0);
			String diagnostic = diagnostic(PREFIX_SEED, caseIndex, codecCase.name(), payload.length);

			BufDataInput read = BufDataInput.create(Buf.wrap(payload), depthZero);
			assertThrows(DecodeLimitExceededException.class,
					() -> codecCase.codec().read(read), diagnostic);
			assertEquals(0, read.position(), diagnostic);
			assertEquals(0, read.decodeBudget().structuralDepth(), diagnostic);
			BufDataInput skip = BufDataInput.create(Buf.wrap(payload), depthZero);
			assertThrows(DecodeLimitExceededException.class,
					() -> codecCase.codec().skip(skip), diagnostic);
			assertEquals(0, skip.position(), diagnostic);
			assertEquals(0, skip.decodeBudget().structuralDepth(), diagnostic);

			assertValueEquals(value, codecCase.codec().newReader(GENEROUS).read(Buf.wrap(payload)),
					diagnostic + ", recovery");
		}
	}

	@Test
	void int52ConversionAndWireHelpersFuzzTheCompleteCanonicalDomain() {
		var random = new Random(INT52_SEED);
		for (int caseIndex = 0; caseIndex < INT52_CASES; caseIndex++) {
			long value = random.nextLong() & Int52.MAX_VALUE_L;
			Int52 number = Int52.fromLong(value);
			byte[] bytes = Int52Serializer.toByteArray(value);
			String diagnostic = diagnostic(INT52_SEED, caseIndex, "int52", (int) value);
			assertEquals(7, bytes.length, diagnostic);
			assertEquals(0, bytes[0] & 0xF0, diagnostic);
			assertEquals(value, Int52Serializer.fromByteArray(bytes), diagnostic);
			assertEquals(value, Int52.fromByteArray(bytes).longValue(), diagnostic);
			assertEquals(number, Int52.fromBytes(bytes[0], bytes[1], bytes[2], bytes[3],
					bytes[4], bytes[5], bytes[6]), diagnostic);
			assertEquals(value, Int52.fromByteArrayL(bytes), diagnostic);
			assertEquals(value, Int52.fromBytesL(bytes[0], bytes[1], bytes[2], bytes[3],
					bytes[4], bytes[5], bytes[6]), diagnostic);
			assertEquals(Long.toString(value), number.toString(), diagnostic);
			assertEquals(Long.hashCode(value), number.hashCode(), diagnostic);
			assertEquals(value, number.longValue(), diagnostic);
			assertEquals((int) value, number.intValue(), diagnostic);

			byte[] payload = serialize(codec(Int52Serializer.INSTANCE), number);
			assertArrayEquals(bytes, payload, diagnostic);
			assertEquals(number, Int52Serializer.INSTANCE.newReader(GENEROUS).read(Buf.wrap(payload)), diagnostic);
		}

		for (long invalid : new long[] {-1, Long.MIN_VALUE, Int52.MAX_VALUE_L + 1, Long.MAX_VALUE}) {
			assertThrows(IllegalArgumentException.class, () -> Int52.fromLong(invalid));
			assertThrows(IllegalArgumentException.class, () -> Int52.checkValidity(invalid));
		}
		for (int length : new int[] {0, 1, 6, 8, 9, 64}) {
			assertThrows(IllegalArgumentException.class,
					() -> Int52Serializer.fromByteArray(new byte[length]));
		}
		assertSame(Int52.ZERO, Int52.fromLong(0));
		assertSame(Int52.ONE, Int52.fromLong(1));
		assertSame(Int52.TWO, Int52.fromLong(2));
		assertSame(Int52.TEN, Int52.fromLong(10));
	}

	@Test
	void shortPrefixedStringAndBinarySerializersFuzzTheUnsignedBoundaryAndRegistrySurface()
			throws IllegalAccessException {
		for (int length : new int[] {0, 1, 255, 256, 32_767, 32_768, 65_535}) {
			byte[] bytes = new byte[length];
			Arrays.fill(bytes, (byte) 'a');
			BinaryString binary = new BinaryString(bytes);
			BufDataOutput output = BufDataOutput.create();
			BinaryStringSerializer.writeShort(output, binary);
			assertEquals(length + Short.BYTES, output.position());
			assertEquals(binary, BinaryStringSerializer.readShort(
					BufDataInput.create(output.asList(), DecodeLimits.unlimited())));
		}
		BinaryString tooLarge = new BinaryString(new byte[65_536]);
		assertThrows(ValueTooLargeException.class,
				() -> BinaryStringSerializer.validateShort(tooLarge));
		assertThrows(ValueTooLargeException.class,
				() -> BinaryStringSerializer.writeShort(BufDataOutput.create(), tooLarge));
		String tooLong = "a".repeat(65_536);
		assertThrows(ValueTooLargeException.class,
				() -> NullableStringSerializer.INSTANCE.serialize(
						BufDataOutput.create(), NullableString.of(tooLong)));
		assertThrows(ValueTooLargeException.class,
				() -> new ArrayStringSerializer().serialize(
						BufDataOutput.create(), new String[] {"ok", tooLong}));

		List<Field> registry = Arrays.stream(Serializers.class.getFields())
				.filter(field -> Modifier.isStatic(field.getModifiers()))
				.filter(field -> DataCodec.class.isAssignableFrom(field.getType()))
				.toList();
		assertEquals(CODECS.size(), registry.size());
		for (Field field : registry) assertNotNull(field.get(null), field.getName());
	}

	private static void assertMalformedReadAndSkip(DataCodec<Object> codec, byte[] wire,
			DecodeLimits limits, String diagnostic) {
		BufDataInput read = BufDataInput.create(Buf.wrap(wire), limits);
		assertThrows(MalformedDataException.class, () -> codec.read(read), diagnostic + ", read");
		assertEquals(0, read.decodeBudget().structuralDepth(), diagnostic);
		BufDataInput skip = BufDataInput.create(Buf.wrap(wire), limits);
		assertThrows(MalformedDataException.class, () -> codec.skip(skip), diagnostic + ", skip");
		assertEquals(0, skip.decodeBudget().structuralDepth(), diagnostic);
	}

	private static void assertDoesNotThrowSkip(DataCodec<Object> codec, byte[] wire,
			DecodeLimits limits, String diagnostic) {
		BufDataInput skip = BufDataInput.create(Buf.wrap(wire), limits);
		codec.skip(skip);
		assertEquals(wire.length, skip.position(), diagnostic);
		assertEquals(0, skip.decodeBudget().structuralDepth(), diagnostic);
	}

	private static byte[] serialize(DataCodec<Object> codec, Object value) {
		BufDataOutput output = BufDataOutput.create();
		codec.serialize(output, value);
		return output.asList().asArray().clone();
	}

	@SuppressWarnings("unchecked")
	private static DataCodec<Object> codec(DataCodec<?> codec) {
		return (DataCodec<Object>) codec;
	}

	private static String randomString(Random random) {
		int codePoints = random.nextInt(65);
		StringBuilder result = new StringBuilder(codePoints);
		for (int i = 0; i < codePoints; i++) {
			int value = switch (random.nextInt(8)) {
				case 0 -> 0;
				case 1 -> random.nextInt(0x80);
				case 2 -> 0x80 + random.nextInt(0x780);
				case 3 -> 0x800 + random.nextInt(0xD800 - 0x800);
				case 4 -> 0xE000 + random.nextInt(0x10000 - 0xE000);
				default -> 0x10000 + random.nextInt(0x10FFFF - 0x10000 + 1);
			};
			result.appendCodePoint(value);
		}
		return result.toString();
	}

	private static byte[] randomBytes(Random random, int exclusiveMaximumLength) {
		byte[] result = new byte[random.nextInt(exclusiveMaximumLength)];
		random.nextBytes(result);
		return result;
	}

	private static Int52 randomInt52(Random random) {
		return Int52.fromLong(random.nextLong() & Int52.MAX_VALUE_L);
	}

	private static String[] randomStrings(Random random) {
		String[] result = new String[random.nextInt(17)];
		for (int i = 0; i < result.length; i++) result[i] = randomString(random);
		return result;
	}

	private static BinaryString[] randomBinaryStrings(Random random) {
		BinaryString[] result = new BinaryString[random.nextInt(17)];
		for (int i = 0; i < result.length; i++) result[i] = new BinaryString(randomBytes(random, 129));
		return result;
	}

	private static Int52[] randomInt52s(Random random) {
		Int52[] result = new Int52[random.nextInt(33)];
		for (int i = 0; i < result.length; i++) result[i] = randomInt52(random);
		return result;
	}

	private static boolean[] randomBooleans(Random random) {
		boolean[] result = new boolean[random.nextInt(65)];
		for (int i = 0; i < result.length; i++) result[i] = random.nextBoolean();
		return result;
	}

	private static short[] randomShorts(Random random) {
		short[] result = new short[random.nextInt(65)];
		for (int i = 0; i < result.length; i++) result[i] = (short) random.nextInt();
		return result;
	}

	private static char[] randomChars(Random random) {
		char[] result = new char[random.nextInt(65)];
		for (int i = 0; i < result.length; i++) result[i] = (char) random.nextInt();
		return result;
	}

	private static int[] randomInts(Random random) {
		int[] result = new int[random.nextInt(65)];
		for (int i = 0; i < result.length; i++) result[i] = random.nextInt();
		return result;
	}

	private static long[] randomLongs(Random random) {
		long[] result = new long[random.nextInt(65)];
		for (int i = 0; i < result.length; i++) result[i] = random.nextLong();
		return result;
	}

	private static float[] randomFloats(Random random) {
		float[] result = new float[random.nextInt(65)];
		for (int i = 0; i < result.length; i++) result[i] = Float.intBitsToFloat(random.nextInt());
		return result;
	}

	private static double[] randomDoubles(Random random) {
		double[] result = new double[random.nextInt(65)];
		for (int i = 0; i < result.length; i++) result[i] = Double.longBitsToDouble(random.nextLong());
		return result;
	}

	private static void assertValueEquals(Object expected, Object actual, String diagnostic) {
		if (expected instanceof float[] expectedFloats && actual instanceof float[] actualFloats) {
			assertEquals(expectedFloats.length, actualFloats.length, diagnostic);
			for (int i = 0; i < expectedFloats.length; i++) {
				assertEquals(Float.floatToIntBits(expectedFloats[i]), Float.floatToIntBits(actualFloats[i]),
						diagnostic + ", i=" + i);
			}
		} else if (expected instanceof double[] expectedDoubles && actual instanceof double[] actualDoubles) {
			assertEquals(expectedDoubles.length, actualDoubles.length, diagnostic);
			for (int i = 0; i < expectedDoubles.length; i++) {
				assertEquals(Double.doubleToLongBits(expectedDoubles[i]), Double.doubleToLongBits(actualDoubles[i]),
						diagnostic + ", i=" + i);
			}
		} else {
			assertTrue(Objects.deepEquals(expected, actual),
					() -> diagnostic + ", expected=" + describe(expected) + ", actual=" + describe(actual));
		}
	}

	private static String describe(Object value) {
		if (value == null || !value.getClass().isArray()) return String.valueOf(value);
		if (value instanceof Object[] values) return Arrays.deepToString(values);
		if (value instanceof byte[] values) return Arrays.toString(values);
		if (value instanceof boolean[] values) return Arrays.toString(values);
		if (value instanceof short[] values) return Arrays.toString(values);
		if (value instanceof char[] values) return Arrays.toString(values);
		if (value instanceof int[] values) return Arrays.toString(values);
		if (value instanceof long[] values) return Arrays.toString(values);
		if (value instanceof float[] values) return Arrays.toString(values);
		return Arrays.toString((double[]) value);
	}

	private static Buf forcedFallback(Buf delegate) {
		return (Buf) Proxy.newProxyInstance(NativeSerializerDeepFuzzTest.class.getClassLoader(),
				new Class<?>[] {Buf.class}, (proxy, method, arguments) -> switch (method.getName()) {
					case "getBackingByteArrayStrict", "asMemorySegmentStrict", "asArrayStrict",
							"asUnboundedArrayStrict" -> null;
					default -> {
						try {
							yield method.invoke(delegate, arguments);
						} catch (InvocationTargetException failure) {
							throw failure.getCause();
						}
					}
				});
	}

	private static byte[] intBytes(int value) {
		byte[] result = new byte[Integer.BYTES];
		putInt(result, 0, value);
		return result;
	}

	private static void putInt(byte[] bytes, int offset, int value) {
		bytes[offset] = (byte) (value >>> 24);
		bytes[offset + 1] = (byte) (value >>> 16);
		bytes[offset + 2] = (byte) (value >>> 8);
		bytes[offset + 3] = (byte) value;
	}

	private static String diagnostic(long seed, int caseIndex, String codec, int detail) {
		return "seed=" + seed + ", case=" + caseIndex + ", codec=" + codec + ", detail=" + detail;
	}

	@FunctionalInterface
	private interface ValueFactory {
		Object create(Random random);
	}

	private record CodecCase(String name, DataCodec<Object> codec, ValueFactory values) {}
	private record ArrayCase(String name, DataCodec<Object> codec, int elementBytes) {}
	private record Storage(String name, Buf source, int offset) {}
}
