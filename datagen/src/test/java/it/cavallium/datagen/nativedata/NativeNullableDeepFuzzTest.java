package it.cavallium.datagen.nativedata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.datagen.DataContext;
import it.cavallium.datagen.DataUpgrader;
import it.cavallium.datagen.DataUpgraderSimple;
import it.cavallium.datagen.NativeNullable;
import it.cavallium.datagen.TypedNullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Fuzzes all nullable value wrappers plus their generic combinators and upgrade helpers. */
class NativeNullableDeepFuzzTest {

	private static final long NULLABLE_SEED = 0x43B7_0D9E_2A61_C85FL;
	private static final long BLANK_SEED = 0x716C_EA25_8D40_39BFL;
	private static final long UPGRADE_SEED = 0x19F3_5BCA_704E_D268L;
	private static final int NULLABLE_CASES = 80_000;
	private static final int BLANK_CASES = 30_000;
	private static final int UPGRADE_CASES = 40_000;

	private static final List<Family> FAMILIES = List.of(
			new Family("boolean", Nullableboolean::empty,
					random -> Nullableboolean.of(random.nextBoolean()), random -> random.nextBoolean()),
			new Family("byte", Nullablebyte::empty,
					random -> Nullablebyte.of((byte) random.nextInt()), random -> (byte) random.nextInt()),
			new Family("short", Nullableshort::empty,
					random -> Nullableshort.of((short) random.nextInt()), random -> (short) random.nextInt()),
			new Family("char", Nullablechar::empty,
					random -> Nullablechar.of((char) random.nextInt()), random -> (char) random.nextInt()),
			new Family("int", Nullableint::empty,
					random -> Nullableint.of(random.nextInt()), Random::nextInt),
			new Family("long", Nullablelong::empty,
					random -> Nullablelong.of(random.nextLong()), Random::nextLong),
			new Family("float", Nullablefloat::empty,
					random -> Nullablefloat.of(Float.intBitsToFloat(random.nextInt())),
					random -> Float.intBitsToFloat(random.nextInt())),
			new Family("double", Nullabledouble::empty,
					random -> Nullabledouble.of(Double.longBitsToDouble(random.nextLong())),
					random -> Double.longBitsToDouble(random.nextLong())),
			new Family("string", NullableString::empty,
					random -> NullableString.of(randomString(random)), NativeNullableDeepFuzzTest::randomString),
			new Family("binary", NullableBinaryString::empty,
					random -> NullableBinaryString.of(new BinaryString(randomBytes(random))),
					random -> new BinaryString(randomBytes(random))),
			new Family("int52", NullableInt52::empty,
					random -> NullableInt52.of(randomInt52(random)), NativeNullableDeepFuzzTest::randomInt52));

	@Test
	void everyNullableFamilyFuzzesPresenceFallbackMappingFilteringStreamingCloneAndEquality() throws Exception {
		var random = new Random(NULLABLE_SEED);
		for (int caseIndex = 0; caseIndex < NULLABLE_CASES; caseIndex++) {
			Family family = FAMILIES.get(random.nextInt(FAMILIES.size()));
			NativeNullable<Object> empty = cast(family.empty().get());
			NativeNullable<Object> present = cast(family.present().create(random));
			Object presentValue = present.getNullable();
			Object defaultValue = family.defaults().create(random);
			String diagnostic = diagnostic(NULLABLE_SEED, caseIndex, family.name());

			assertTrue(empty.isEmpty(), diagnostic);
			assertFalse(empty.isPresent(), diagnostic);
			assertNull(empty.getNullable(), diagnostic);
			assertSame(defaultValue, empty.getNullable(defaultValue), diagnostic);
			assertSame(defaultValue, empty.orElse(defaultValue), diagnostic);
			assertSame(present, empty.or(present), diagnostic);
			assertEquals(Optional.empty(), empty.toOptional(), diagnostic);
			assertEquals(0, empty.stream().count(), diagnostic);

			assertFalse(present.isEmpty(), diagnostic);
			assertTrue(present.isPresent(), diagnostic);
			assertSame(presentValue, present.getNullable(), diagnostic);
			assertSame(presentValue, present.getNullable(defaultValue), diagnostic);
			assertSame(presentValue, present.orElse(defaultValue), diagnostic);
			assertSame(present, present.or(empty), diagnostic);
			assertEquals(Optional.of(presentValue), present.toOptional(), diagnostic);
			assertEquals(List.of(presentValue), present.stream().toList(), diagnostic);

			AtomicInteger mapperCalls = new AtomicInteger();
			Optional<String> emptyMap = empty.map(value -> {
				mapperCalls.incrementAndGet();
				return value.toString();
			});
			assertEquals(Optional.empty(), emptyMap, diagnostic);
			assertEquals(0, mapperCalls.get(), diagnostic);
			Optional<String> presentMap = present.map(value -> {
				mapperCalls.incrementAndGet();
				return value.toString();
			});
			assertEquals(Optional.of(presentValue.toString()), presentMap, diagnostic);
			assertEquals(1, mapperCalls.get(), diagnostic);

			String constructedEmpty = empty.map(Object::toString,
					value -> value == null ? "empty" : "value:" + value);
			String constructedPresent = present.map(Object::toString,
					value -> value == null ? "empty" : "value:" + value);
			assertEquals("empty", constructedEmpty, diagnostic);
			assertEquals("value:" + presentValue, constructedPresent, diagnostic);

			NullableString emptyMapped = empty.mapNullable(
					value -> NullableString.of(value.toString()), NullableString::empty);
			NullableString presentMapped = present.mapNullable(
					value -> NullableString.of(value.toString()), NullableString::empty);
			assertTrue(emptyMapped.isEmpty(), diagnostic);
			assertEquals(presentValue.toString(), presentMapped.get(), diagnostic);

			assertEquals(Optional.empty(), empty.filter(value -> true), diagnostic);
			assertEquals(Optional.of(presentValue), present.filter(value -> true), diagnostic);
			assertEquals(Optional.empty(), present.filter(value -> false), diagnostic);
			assertThrows(NullPointerException.class, () -> present.map(value -> null), diagnostic);

			Object emptyClone = cloneValue(empty);
			Object presentClone = cloneValue(present);
			assertEquals(empty, emptyClone, diagnostic);
			assertEquals(present, presentClone, diagnostic);
			assertEquals(empty.hashCode(), emptyClone.hashCode(), diagnostic);
			assertEquals(present.hashCode(), presentClone.hashCode(), diagnostic);
			assertEquals("null", empty.toString(), diagnostic);
			assertEquals(present.toString(), presentClone.toString(), diagnostic);

			assertGetContract(empty, null, diagnostic);
			assertGetContract(present, presentValue, diagnostic);
		}
	}

	@Test
	void stringAndBinaryBlankSemanticsFuzzWhitespaceEmptyContentAndFallbackIdentity() {
		var random = new Random(BLANK_SEED);
		for (int caseIndex = 0; caseIndex < BLANK_CASES; caseIndex++) {
			String content = switch (random.nextInt(5)) {
				case 0 -> "";
				case 1 -> " \t\r\n".repeat(random.nextInt(8));
				default -> randomString(random);
			};
			NullableString value = NullableString.of(content);
			NullableString fallback = NullableString.of("fallback-" + caseIndex);
			boolean blank = content.isBlank();
			String diagnostic = diagnostic(BLANK_SEED, caseIndex, "string");
			assertEquals(blank, value.isBlank(), diagnostic);
			assertEquals(!blank, value.isContentful(), diagnostic);
			assertSame(blank ? fallback : value, value.orIfBlank(fallback), diagnostic);
			assertSame(fallback, NullableString.empty().orIfBlank(fallback), diagnostic);
			assertEquals(blank, NullableString.ofNullableBlank(content).isEmpty(), diagnostic);

			byte[] bytes = randomBytes(random);
			NullableBinaryString binary = NullableBinaryString.of(new BinaryString(bytes));
			NullableBinaryString binaryFallback = NullableBinaryString.of(
					new BinaryString(new byte[] {1, 2, 3}));
			assertEquals(bytes.length == 0, binary.isBlank(), diagnostic);
			assertEquals(bytes.length != 0, binary.isContentful(), diagnostic);
			assertSame(bytes.length == 0 ? binaryFallback : binary,
					binary.orIfBlank(binaryFallback), diagnostic);
			assertSame(binaryFallback,
					NullableBinaryString.empty().orIfBlank(binaryFallback), diagnostic);
		}

		assertTrue(NullableString.ofNullable(null).isEmpty());
		assertTrue(NullableString.ofNullableBlank(null).isEmpty());
		assertTrue(NullableBinaryString.ofNullable(null).isEmpty());
		assertTrue(NullableString.empty() instanceof TypedNullable<?>);
		assertTrue(NullableString.empty() instanceof INullable);
		assertThrows(NullPointerException.class, () -> NullableString.of(null));
		assertThrows(NullPointerException.class, () -> NullableBinaryString.of(null));
	}

	@Test
	void primitiveFactoriesFuzzBoxedAndNumberConversionsCachesAndDefaults() {
		var random = new Random(NULLABLE_SEED ^ Long.MIN_VALUE);
		for (int caseIndex = 0; caseIndex < NULLABLE_CASES; caseIndex++) {
			byte byteValue = (byte) random.nextInt();
			short shortValue = (short) random.nextInt();
			int intValue = random.nextInt();
			long longValue = random.nextLong();
			float floatValue = Float.intBitsToFloat(random.nextInt());
			double doubleValue = Double.longBitsToDouble(random.nextLong());
			long int52Value = random.nextLong() & Int52.MAX_VALUE_L;
			String diagnostic = diagnostic(NULLABLE_SEED, caseIndex, "factories");

			assertSame(Nullablebyte.of(byteValue), Nullablebyte.of(byteValue), diagnostic);
			assertSame(Nullablebyte.of(byteValue), Nullablebyte.ofNullable(byteValue), diagnostic);
			assertEquals(byteValue, Nullablebyte.ofNullableNumber(byteValue).get(), diagnostic);
			assertEquals(shortValue, Nullableshort.ofNullableNumber(shortValue).get(), diagnostic);
			assertEquals(intValue, Nullableint.ofNullableNumber(intValue).get(), diagnostic);
			assertEquals(longValue, Nullablelong.ofNullableNumber(longValue).get(), diagnostic);
			assertEquals(Float.floatToIntBits(floatValue),
					Float.floatToIntBits(Nullablefloat.ofNullableNumber(floatValue).get()), diagnostic);
			assertEquals(Double.doubleToLongBits(doubleValue),
					Double.doubleToLongBits(Nullabledouble.ofNullableNumber(doubleValue).get()), diagnostic);
			assertEquals(int52Value, NullableInt52.ofNullableNumber(int52Value).get().longValue(), diagnostic);

			assertSame(Nullableboolean.of(true), Nullableboolean.ofNullable(true), diagnostic);
			assertSame(Nullableboolean.of(false), Nullableboolean.ofNullable(false), diagnostic);
			assertSame(Nullableboolean.empty(), Nullableboolean.ofNullable(null), diagnostic);
			assertSame(Nullablebyte.empty(), Nullablebyte.ofNullableNumber(null), diagnostic);
			assertSame(Nullableshort.empty(), Nullableshort.ofNullableNumber(null), diagnostic);
			assertSame(Nullableint.empty(), Nullableint.ofNullableNumber(null), diagnostic);
			assertSame(Nullablelong.empty(), Nullablelong.ofNullableNumber(null), diagnostic);
			assertSame(Nullablefloat.empty(), Nullablefloat.ofNullableNumber(null), diagnostic);
			assertSame(Nullabledouble.empty(), Nullabledouble.ofNullableNumber(null), diagnostic);
			assertSame(NullableInt52.empty(), NullableInt52.ofNullableNumber(null), diagnostic);
		}
	}

	@Test
	void upgradeUtilitiesFuzzArrayRuntimeTypesOrderContextEmptyReuseAndNullableShortCircuiting() {
		var random = new Random(UPGRADE_SEED);
		for (int caseIndex = 0; caseIndex < UPGRADE_CASES; caseIndex++) {
			Integer[] source = new Integer[random.nextInt(65)];
			for (int i = 0; i < source.length; i++) source[i] = random.nextInt();
			TestContext context = new TestContext(random.nextLong());
			AtomicInteger calls = new AtomicInteger();
			DataUpgrader<TestContext, Integer, String> contextual = (seenContext, value) -> {
				assertSame(context, seenContext);
				calls.incrementAndGet();
				return seenContext.salt() + ":" + value;
			};
			String diagnostic = diagnostic(UPGRADE_SEED, caseIndex, "upgrade-array");

			String[] upgraded = UpgradeUtil.upgradeArray(context, source, String[].class, contextual);
			assertEquals(String[].class, upgraded.getClass(), diagnostic);
			assertEquals(source.length, upgraded.length, diagnostic);
			assertEquals(source.length, calls.get(), diagnostic);
			for (int i = 0; i < source.length; i++) {
				assertEquals(context.salt() + ":" + source[i], upgraded[i], diagnostic + ", i=" + i);
			}

			String[] emptyTarget = new String[0];
			calls.set(0);
			String[] withEmptyTarget = UpgradeUtil.upgradeArray(
					context, source, String[].class, emptyTarget, contextual);
			if (source.length == 0) {
				assertSame(emptyTarget, withEmptyTarget, diagnostic);
				assertEquals(0, calls.get(), diagnostic);
			} else {
				assertNotSame(emptyTarget, withEmptyTarget, diagnostic);
				assertEquals(source.length, calls.get(), diagnostic);
			}

			AtomicInteger simpleCalls = new AtomicInteger();
			DataUpgraderSimple<Integer, Long> simple = new DataUpgraderSimple<>() {
				@Override public Long upgrade(Integer value) {
					simpleCalls.incrementAndGet();
					return value.longValue();
				}
			};
			Long[] longs = UpgradeUtil.upgradeArray(source, Long[].class, simple);
			assertEquals(source.length, simpleCalls.get(), diagnostic);
			for (int i = 0; i < source.length; i++) assertEquals(source[i].longValue(), longs[i], diagnostic);

			simpleCalls.set(0);
			assertNull(UpgradeUtil.upgradeNullable(null, simple), diagnostic);
			assertEquals(0, simpleCalls.get(), diagnostic);
			if (source.length != 0) {
				assertEquals(source[0].longValue(), UpgradeUtil.upgradeNullable(source[0], simple), diagnostic);
				assertEquals(1, simpleCalls.get(), diagnostic);
			}
		}
	}

	@Test
	void binaryStringAndInt52ValueSemanticsFuzzDeepEqualityHashTextAndOrdering() {
		var random = new Random(BLANK_SEED ^ Long.MIN_VALUE);
		for (int caseIndex = 0; caseIndex < BLANK_CASES; caseIndex++) {
			byte[] bytes = randomBytes(random);
			BinaryString first = new BinaryString(bytes.clone());
			BinaryString second = new BinaryString(bytes.clone());
			String diagnostic = diagnostic(BLANK_SEED, caseIndex, "values");
			assertEquals(first, second, diagnostic);
			assertEquals(first.hashCode(), second.hashCode(), diagnostic);
			assertEquals(bytes.length, first.sizeBytes(), diagnostic);
			assertEquals(new String(bytes, StandardCharsets.UTF_8), first.toString(), diagnostic);
			if (bytes.length != 0) {
				second.data()[0] ^= 1;
				assertNotEquals(first, second, diagnostic);
			}

			long leftValue = random.nextLong() & Int52.MAX_VALUE_L;
			long rightValue = random.nextLong() & Int52.MAX_VALUE_L;
			Int52 left = Int52.fromLong(leftValue);
			Int52 right = Int52.fromLong(rightValue);
			assertEquals(Integer.signum(Long.compare(leftValue, rightValue)),
					Integer.signum(left.compareTo(right)), diagnostic);
			assertEquals(leftValue == rightValue, left.equals(right), diagnostic);
			assertEquals((float) leftValue, left.floatValue(), diagnostic);
			assertEquals((double) leftValue, left.doubleValue(), diagnostic);
		}
	}

	@SuppressWarnings("unchecked")
	private static NativeNullable<Object> cast(NativeNullable<?> nullable) {
		return (NativeNullable<Object>) nullable;
	}

	private static Object cloneValue(NativeNullable<Object> nullable) throws Exception {
		Method clone = nullable.getClass().getMethod("clone");
		return clone.invoke(nullable);
	}

	private static void assertGetContract(NativeNullable<Object> nullable, Object expected, String diagnostic)
			throws Exception {
		Method get = nullable.getClass().getMethod("get");
		if (expected == null) {
			InvocationTargetException failure = assertThrows(InvocationTargetException.class,
					() -> get.invoke(nullable), diagnostic);
			assertTrue(failure.getCause() instanceof NullPointerException, diagnostic);
		} else {
			assertEquals(expected, get.invoke(nullable), diagnostic);
		}
	}

	private static byte[] randomBytes(Random random) {
		byte[] result = new byte[random.nextInt(129)];
		random.nextBytes(result);
		return result;
	}

	private static String randomString(Random random) {
		int length = random.nextInt(65);
		StringBuilder result = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			int codePoint = switch (random.nextInt(6)) {
				case 0 -> random.nextInt(0x80);
				case 1 -> 0x80 + random.nextInt(0x780);
				case 2 -> 0x800 + random.nextInt(0xD800 - 0x800);
				case 3 -> 0xE000 + random.nextInt(0x10000 - 0xE000);
				default -> 0x10000 + random.nextInt(0x10FFFF - 0x10000 + 1);
			};
			result.appendCodePoint(codePoint);
		}
		return result.toString();
	}

	private static Int52 randomInt52(Random random) {
		return Int52.fromLong(random.nextLong() & Int52.MAX_VALUE_L);
	}

	private static String diagnostic(long seed, int caseIndex, String family) {
		return "seed=" + seed + ", case=" + caseIndex + ", family=" + family;
	}

	@FunctionalInterface
	private interface NullableFactory {
		NativeNullable<?> create(Random random);
	}

	@FunctionalInterface
	private interface ValueFactory {
		Object create(Random random);
	}

	private record Family(String name, Supplier<NativeNullable<?>> empty,
			NullableFactory present, ValueFactory defaults) {}

	private record TestContext(long salt) implements DataContext {}
}
