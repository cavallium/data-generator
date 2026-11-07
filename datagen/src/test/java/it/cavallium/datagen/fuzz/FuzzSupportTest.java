package it.cavallium.datagen.fuzz;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.datagen.fuzz.FuzzTiers.Tier;
import it.cavallium.datagen.nativedata.Int52;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * Contract tests for the shared fuzz support classes: the tier/seed/case knobs of {@link FuzzTiers},
 * the hostile value pool of {@link FuzzValues} and the seed plumbing of {@link FuzzReplay}.
 *
 * <p>Every test that touches a knob restores the previous value in a {@code finally} block: JUnit runs
 * the whole suite in one JVM, so a leaked {@code datagen.fuzz.tier} would silently change the budget of
 * every test that runs after this one.</p>
 */
class FuzzSupportTest {

	private static final String TIER_PROPERTY = "datagen.fuzz.tier";
	private static final String SEED_PROPERTY = "datagen.fuzz.seed";
	private static final String CASES_PROPERTY = "datagen.fuzz.cases";

	private static final int[] NEAR_SHORT_BOUNDARY = {65534, 65535, 65536, 65537};

	@Test
	void tierIsDefaultWhenNoKnobIsSet() {
		Knobs knobs = Knobs.clear();
		try {
			assertEquals(Tier.DEFAULT, FuzzTiers.tier());
		} finally {
			knobs.restore();
		}
	}

	@Test
	void tierParsesCaseInsensitively() {
		Knobs knobs = Knobs.clear();
		try {
			for (Tier expected : Tier.values()) {
				System.setProperty(TIER_PROPERTY, expected.name().toLowerCase(Locale.ROOT));
				assertEquals(expected, FuzzTiers.tier(), "lower case");
				System.setProperty(TIER_PROPERTY, expected.name());
				assertEquals(expected, FuzzTiers.tier(), "upper case");
				System.setProperty(TIER_PROPERTY, mixedCase(expected.name()));
				assertEquals(expected, FuzzTiers.tier(), "mixed case");
			}
		} finally {
			knobs.restore();
		}
	}

	@Test
	void invalidTierThrowsWithTheOffendingValue() {
		Knobs knobs = Knobs.clear();
		try {
			System.setProperty(TIER_PROPERTY, "sideways");
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, FuzzTiers::tier);
			assertTrue(e.getMessage().contains("sideways"), e.getMessage());
		} finally {
			knobs.restore();
		}
	}

	@Test
	void tierIsDeepWhenOnlyASeedIsGiven() {
		Knobs knobs = Knobs.clear();
		try {
			System.setProperty(SEED_PROPERTY, "777");
			assertEquals(Tier.DEEP, FuzzTiers.tier());
			System.setProperty(TIER_PROPERTY, "quick");
			assertEquals(Tier.QUICK, FuzzTiers.tier(), "an explicit tier wins over the seed default");
		} finally {
			knobs.restore();
		}
	}

	@Test
	void seedFallsBackToOneNonNegativeJvmConstant() {
		Knobs knobs = Knobs.clear();
		try {
			long first = FuzzTiers.seed();
			assertTrue(first >= 0, "seed must never be negative: " + first);
			assertEquals(first, FuzzTiers.seed(), "the per-JVM seed must be stable inside one run");
			System.setProperty(SEED_PROPERTY, "1234567890123");
			assertEquals(1234567890123L, FuzzTiers.seed());
			System.setProperty(SEED_PROPERTY, "-5");
			assertTrue(FuzzTiers.seed() >= 0, "an explicit negative seed is masked non-negative");
			System.setProperty(SEED_PROPERTY, "nonsense");
			assertThrows(IllegalArgumentException.class, FuzzTiers::seed);
		} finally {
			knobs.restore();
		}
	}

	@Test
	void casesScalesWithTheTier() {
		Knobs knobs = Knobs.clear();
		try {
			Set<Integer> seen = new HashSet<>();
			System.setProperty(TIER_PROPERTY, "quick");
			int quick = FuzzTiers.cases("scale-check", 1000);
			System.setProperty(TIER_PROPERTY, "default");
			int base = FuzzTiers.cases("scale-check", 1000);
			System.setProperty(TIER_PROPERTY, "deep");
			int deep = FuzzTiers.cases("scale-check", 1000);
			System.setProperty(TIER_PROPERTY, "nightly");
			int nightly = FuzzTiers.cases("scale-check", 1000);
			assertEquals(100, quick, "quick = 0.1 * base");
			assertEquals(1000, base, "default = base");
			assertEquals(10000, deep, "deep = 10 * base");
			assertEquals(50000, nightly, "nightly = 50 * base");
			seen.add(quick);
			seen.add(base);
			seen.add(deep);
			seen.add(nightly);
			assertEquals(4, seen.size(), "every tier must pick a different budget: " + seen);
		} finally {
			knobs.restore();
		}
	}

	@Test
	void casesNeverDropsBelowOne() {
		Knobs knobs = Knobs.clear();
		try {
			for (Tier tier : Tier.values()) {
				System.setProperty(TIER_PROPERTY, tier.name().toLowerCase(Locale.ROOT));
				assertTrue(FuzzTiers.cases("floor-check", 0) >= 1, tier + " base=0");
				assertTrue(FuzzTiers.cases("floor-check", 1) >= 1, tier + " base=1");
				assertTrue(FuzzTiers.cases("floor-check", 1, 0, 1, 10) >= 1, tier + " explicit zero");
			}
			System.setProperty(TIER_PROPERTY, "quick");
			assertEquals(1, FuzzTiers.cases("floor-check", 1), "0.1 rounds up to the single smoke case");
		} finally {
			knobs.restore();
		}
	}

	@Test
	void casesOverrideReplacesEveryTierAndBothOverloads() {
		Knobs knobs = Knobs.clear();
		try {
			System.setProperty(CASES_PROPERTY, "7");
			for (Tier tier : Tier.values()) {
				System.setProperty(TIER_PROPERTY, tier.name().toLowerCase(Locale.ROOT));
				assertEquals(7, FuzzTiers.cases("override-check", 1000), tier.name());
				assertEquals(7, FuzzTiers.cases("override-check", 1, 2, 3, 4), tier.name());
			}
		} finally {
			knobs.restore();
		}
	}

	@Test
	void casesOverrideZeroOrNegativeIsHonoredAsWritten() {
		Knobs knobs = Knobs.clear();
		try {
			// atLeastOne floors only the TIER-COMPUTED path; the literal override is returned verbatim,
			// so 0 and negatives are legal and win on BOTH overloads.
			System.setProperty(TIER_PROPERTY, "default");
			System.setProperty(CASES_PROPERTY, "0");
			assertEquals(0, FuzzTiers.cases("zero-check", 1000), "literal 0, one-base overload");
			assertEquals(0, FuzzTiers.cases("zero-check", 1, 2, 3, 4), "literal 0, per-tier overload");
			System.setProperty(CASES_PROPERTY, "-3");
			assertEquals(-3, FuzzTiers.cases("negative-check", 1000), "literal -3, one-base overload");
			assertEquals(-3, FuzzTiers.cases("negative-check", 1, 2, 3, 4), "literal -3, per-tier overload");
		} finally {
			knobs.restore();
		}
	}

	@Test
	void perTierCaseListIsPickedByTierInDeclarationOrder() {
		Knobs knobs = Knobs.clear();
		try {
			System.setProperty(TIER_PROPERTY, "quick");
			assertEquals(11, FuzzTiers.cases("pick-check", 11, 22, 33, 44));
			System.setProperty(TIER_PROPERTY, "default");
			assertEquals(22, FuzzTiers.cases("pick-check", 11, 22, 33, 44));
			System.setProperty(TIER_PROPERTY, "deep");
			assertEquals(33, FuzzTiers.cases("pick-check", 11, 22, 33, 44));
			System.setProperty(TIER_PROPERTY, "nightly");
			assertEquals(44, FuzzTiers.cases("pick-check", 11, 22, 33, 44));
		} finally {
			knobs.restore();
		}
	}

	@Test
	void randomIsReproducibleForSeedAndSalt() {
		Knobs knobs = Knobs.clear();
		try {
			System.setProperty(SEED_PROPERTY, "4242");
			long[] baseline = firstLongs(FuzzTiers.random("string-generator"), 10);
			assertArrayEquals(baseline, firstLongs(FuzzTiers.random("string-generator"), 10),
					"same seed and salt must draw the same values");
			assertFalse(Arrays.equals(firstLongs(FuzzTiers.random("other-salt"), 10), baseline),
					"different salts must not share a stream");
			System.setProperty(SEED_PROPERTY, "99");
			assertFalse(Arrays.equals(firstLongs(FuzzTiers.random("string-generator"), 10), baseline),
					"a different seed must draw a different stream");
		} finally {
			knobs.restore();
		}
	}

	@Test
	void randomInstancesAreIndependent() {
		Knobs knobs = Knobs.clear();
		try {
			System.setProperty(SEED_PROPERTY, "4242");
			Random first = FuzzTiers.random("independent");
			Random reference = FuzzTiers.random("independent");
			long[] expected = firstLongs(reference, 10);
			for (int i = 0; i < 50; i++) {
				first.nextLong();
			}
			assertArrayEquals(expected, firstLongs(FuzzTiers.random("independent"), 10),
					"advancing one source must not disturb another");
			assertFalse(Arrays.equals(expected, firstLongs(first, 10)),
					"the advanced source should have moved past its starting values");
		} finally {
			knobs.restore();
		}
	}

	@Test
	void requireMinimumTierAbortsInsteadOfFailing() {
		Knobs knobs = Knobs.clear();
		try {
			System.setProperty(TIER_PROPERTY, "quick");
			assertThrows(TestAbortedException.class, () -> FuzzTiers.requireMinimumTier(Tier.DEEP));
			assertDoesNotThrow(() -> FuzzTiers.requireMinimumTier(Tier.QUICK));
			System.setProperty(TIER_PROPERTY, "nightly");
			assertDoesNotThrow(() -> FuzzTiers.requireMinimumTier(Tier.NIGHTLY), "ordinal ordering");
		} finally {
			knobs.restore();
		}
	}

	@Test
	void describeStatesTheActiveBudget() {
		Knobs knobs = Knobs.clear();
		try {
			System.setProperty(TIER_PROPERTY, "deep");
			System.setProperty(SEED_PROPERTY, "1234");
			String described = FuzzTiers.describe();
			assertTrue(described.contains("tier=DEEP"), described);
			assertTrue(described.contains("seed=1234"), described);
			assertTrue(described.contains("cases-scale=10"), described);
			System.setProperty(TIER_PROPERTY, "quick");
			assertTrue(FuzzTiers.describe().contains("cases-scale=0.1"), FuzzTiers.describe());
		} finally {
			knobs.restore();
		}
	}

	@Test
	void stringsAreNeverNullSometimesEmptyAndAlwaysSurrogateSafe() {
		Random rnd = new Random(20261105L);
		boolean sawEmpty = false;
		boolean sawUnpairedSurrogate = false;
		boolean sawControl = false;
		boolean sawAstral = false;
		for (int i = 0; i < 500; i++) {
			String s = FuzzValues.string(rnd);
			assertNotNull(s, "string() must never return null");
			assertTrue(countCodePoints(s) <= 40, "too long: " + countCodePoints(s));
			sawEmpty |= s.isEmpty();
			sawUnpairedSurrogate |= hasUnpairedSurrogate(s);
			sawAstral |= s.codePoints().anyMatch(cp -> cp > 0xFFFF);
			sawControl |= s.codePoints().anyMatch(cp -> cp < 0x20);
			// an astral draw must be a complete pair: getBytes(UTF_16LE) would replace a lone surrogate,
			// so compare the pair count against the astral code-point count instead
			assertEquals(s.codePoints().filter(cp -> cp > 0xFFFF).count(), countValidSurrogatePairs(s),
					"astral code point without its own surrogate pair: " + s);
		}
		assertTrue(sawEmpty, "500 draws without a single empty string");
		assertTrue(sawUnpairedSurrogate, "500 draws without a single unpaired surrogate");
		assertTrue(sawAstral, "500 draws without a single astral code point");
		assertTrue(sawControl, "500 draws without a single control character");
	}

	@Test
	void nearShortBoundaryStringsLandOnTheDocumentedByteLengths() {
		Random rnd = new Random(65535L);
		Set<Integer> seen = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			String s = FuzzValues.stringNearShortBoundary(rnd);
			int bytes = s.getBytes(StandardCharsets.UTF_8).length;
			assertTrue(isNearShortBoundary(bytes),
					"draw " + i + " produced " + bytes + " bytes, expected one of "
							+ Arrays.toString(NEAR_SHORT_BOUNDARY));
			seen.add(bytes);
		}
		assertEquals(4, seen.size(), "all four boundary lengths should appear in 200 draws: " + seen);
	}

	@Test
	void int52ValuesStayInsideTheRealDomainAndReachItsEdges() {
		Random rnd = new Random(5252L);
		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < 2000; i++) {
			long value = FuzzValues.int52(rnd);
			assertDoesNotThrow(() -> Int52.checkValidity(value), "invalid Int52 drawn: " + value);
			assertDoesNotThrow(() -> Int52.fromLong(value));
			seen.add(value);
		}
		assertTrue(seen.contains(Int52.MIN_VALUE_L), "lower bound never drawn");
		assertTrue(seen.contains(Int52.MAX_VALUE_L), "upper bound never drawn");
		assertTrue(seen.contains(Int52.MIN_VALUE_L + 1), "lower neighbour never drawn");
		assertTrue(seen.contains(Int52.MAX_VALUE_L - 1), "upper neighbour never drawn");
		assertTrue(seen.size() > 500, "int52() is not varied enough: " + seen.size());
	}

	@Test
	void int52OutOfRangeValuesAreAlwaysRejectedInBothDirections() {
		Random rnd = new Random(999L);
		boolean sawBelow = false;
		boolean sawAbove = false;
		for (int i = 0; i < 500; i++) {
			long value = FuzzValues.int52OutOfRange(rnd);
			assertThrows(IllegalArgumentException.class, () -> Int52.checkValidity(value),
					"draw " + i + " = " + value + " should be rejected");
			sawBelow |= value < Int52.MIN_VALUE_L;
			sawAbove |= value > Int52.MAX_VALUE_L;
		}
		assertTrue(sawBelow, "no value below the domain in 500 draws");
		assertTrue(sawAbove, "no value above the domain in 500 draws");
	}

	@Test
	void bytesHonourTheRequestedLength() {
		Random rnd = new Random(1L);
		assertEquals(0, FuzzValues.bytes(rnd, 0).length);
		assertEquals(0, FuzzValues.bytes(rnd, -3).length);
		for (int size : FuzzValues.SIZE_LADDER) {
			assertEquals(size, FuzzValues.bytes(rnd, size).length, "size " + size);
		}
	}

	@Test
	void replaySeedsIsEmptyForAMissingCorpus() {
		assertArrayEquals(new long[0], FuzzReplay.replaySeeds("does-not-exist"));
		assertArrayEquals(new long[0], FuzzReplay.replaySeeds(""));
		assertArrayEquals(new long[0], FuzzReplay.replaySeeds(null));
	}

	@Test
	void theCheckedInNativeCodecCorpusParsesAndSkipsCommentsAndBlankLines() {
		// the corpus resource exists on the test classpath and carries a header comment, a comment line
		// and a trailing blank line: the parsed seeds are exactly the four numbers, in file order.
		assertArrayEquals(new long[] {1L, 7L, 42L, 12345L}, FuzzReplay.replaySeeds("native-codec"),
				"corpus fuzz/seeds/native-codec.txt must parse to exactly its seed lines");
	}

	@Test
	void aCorruptUnreadableCorpusIsLoudButNeverThrows() {
		ClassLoader unreadable = new ClassLoader(FuzzReplay.class.getClassLoader()) {
			@Override
			public InputStream getResourceAsStream(String name) {
				return new InputStream() {
					@Override
					public int read() throws java.io.IOException {
						throw new java.io.IOException("simulated read failure");
					}
				};
			}
		};
		assertArrayEquals(new long[0], assertDoesNotThrow(() -> FuzzReplay.replaySeeds("broken", unreadable)));
	}

	@Test
	void recordFailureAppendsWithoutThrowing() throws IOException {
		String id = "wp1-selftest-" + ProcessHandle.current().pid();
		Path file = Path.of("target", "fuzz-failures", id + ".txt");
		Files.deleteIfExists(file);
		try {
			FuzzReplay.recordFailure(id, 1234L, "first");
			FuzzReplay.recordFailure(id, 5678L, "second\nline");
			String content = Files.readString(file, StandardCharsets.UTF_8);
			assertTrue(content.contains("1234 # first"), content);
			assertTrue(content.contains("5678 # second line"), content);
		} finally {
			Files.deleteIfExists(file);
		}
	}

	/**
	 * Guards the assumption behind {@link FuzzTiers#requireMinimumTier(Tier)}: the tier ordering is the
	 * declaration order, and {@link FuzzTiers} itself stays a zero-constant utility enum.
	 */
	@Test
	void tierOrderingIsDeclarationOrder() {
		assertEquals(0, FuzzTiers.class.getEnumConstants().length, "FuzzTiers must stay a utility enum");
		assertEquals(0, Tier.QUICK.ordinal());
		assertEquals(1, Tier.DEFAULT.ordinal());
		assertEquals(2, Tier.DEEP.ordinal());
		assertEquals(3, Tier.NIGHTLY.ordinal());
		assertTrue(Tier.NIGHTLY.compareTo(Tier.QUICK) > 0);
		assertArrayEquals(new Tier[] {Tier.QUICK, Tier.DEFAULT, Tier.DEEP, Tier.NIGHTLY}, Tier.values());
	}

	private static long[] firstLongs(Random rnd, int count) {
		long[] values = new long[count];
		for (int i = 0; i < count; i++) {
			values[i] = rnd.nextLong();
		}
		return values;
	}

	private static int countCodePoints(String s) {
		return s.codePointCount(0, s.length());
	}

	/** Counts the well-formed high-low surrogate pairs a scan of {@code s} finds. */
	private static int countValidSurrogatePairs(String s) {
		int pairs = 0;
		for (int i = 0; i + 1 < s.length(); i++) {
			if (Character.isHighSurrogate(s.charAt(i)) && Character.isLowSurrogate(s.charAt(i + 1))) {
				pairs++;
				i++;
			}
		}
		return pairs;
	}

	private static boolean hasUnpairedSurrogate(String s) {		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isHighSurrogate(c)) {
				if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
					return true;
				}
				i++;
			} else if (Character.isLowSurrogate(c)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isNearShortBoundary(int bytes) {
		for (int candidate : NEAR_SHORT_BOUNDARY) {
			if (candidate == bytes) {
				return true;
			}
		}
		return false;
	}

	private static String mixedCase(String name) {
		StringBuilder sb = new StringBuilder(name);
		for (int i = 0; i < sb.length(); i++) {
			sb.setCharAt(i, i % 2 == 0 ? Character.toUpperCase(sb.charAt(i)) : Character.toLowerCase(sb.charAt(i)));
		}
		return sb.toString();
	}

	/** Snapshot of the three knobs so a test can leave the JVM as it found it. */
	private record Knobs(String tier, String seed, String cases) {

		static Knobs clear() {
			Knobs snapshot = new Knobs(System.getProperty(TIER_PROPERTY), System.getProperty(SEED_PROPERTY),
					System.getProperty(CASES_PROPERTY));
			System.clearProperty(TIER_PROPERTY);
			System.clearProperty(SEED_PROPERTY);
			System.clearProperty(CASES_PROPERTY);
			return snapshot;
		}

		void restore() {
			restore(TIER_PROPERTY, tier);
			restore(SEED_PROPERTY, seed);
			restore(CASES_PROPERTY, cases);
		}

		private static void restore(String key, String value) {
			if (value == null) {
				System.clearProperty(key);
			} else {
				System.setProperty(key, value);
			}
		}
	}
}
