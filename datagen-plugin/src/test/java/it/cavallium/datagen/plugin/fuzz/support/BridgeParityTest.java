package it.cavallium.datagen.plugin.fuzz.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Pins the bridge's parity with {@code it.cavallium.datagen.fuzz.FuzzTiers} (that class is NOT on this
 * module's test classpath, so the fallback path below IS the live path; the line citations refer to
 * {@code datagen/src/test/java/it/cavallium/datagen/fuzz/FuzzTiers.java}).
 *
 * <p>Table-driven Jupiter, no fuzzing: every row is a documented FuzzTiers rule the fallback must keep
 * matching — tier rounding, saturation, property trimming, the literal {@code cases} override, seed
 * masking and the {@code describe()} format.</p>
 */
class BridgeParityTest {

	private static final String TIER = "datagen.fuzz.tier";
	private static final String SEED = "datagen.fuzz.seed";
	private static final String CASES = "datagen.fuzz.cases";

	/** Runs the body with the three fuzz knobs cleared, then restores them exactly as they were. */
	private static void withCleanKnobs(Runnable body) {
		String tier = System.getProperty(TIER);
		String seed = System.getProperty(SEED);
		String cases = System.getProperty(CASES);
		try {
			System.clearProperty(TIER);
			System.clearProperty(SEED);
			System.clearProperty(CASES);
			body.run();
		} finally {
			restore(TIER, tier);
			restore(SEED, seed);
			restore(CASES, cases);
		}
	}

	private static void restore(String key, String value) {
		if (value == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, value);
		}
	}

	@Test
	void quickRoundsInsteadOfFlooring() {
		// FuzzTiers.java:154-158: Math.round(base * 0.1), NOT floor. base=15 -> round(1.5)=2 (a floor
		// division would wrongly give 1); everything floors at one (FuzzTiers.java:230-232).
		assertEquals(2, SchemaGenProfile.Tier.QUICK.scaled(15), "QUICK must round, not floor");
		assertEquals(1, SchemaGenProfile.Tier.QUICK.scaled(4), "QUICK floors at one: round(0.4)=0 -> 1");
		assertEquals(10, SchemaGenProfile.Tier.DEFAULT.scaled(10));
		assertEquals(100, SchemaGenProfile.Tier.DEEP.scaled(10));
	}

	@Test
	void nightlySaturatesInsteadOfOverflowing() {
		// FuzzTiers.java:155-157: a scale at or above Integer.MAX_VALUE saturates AT Integer.MAX_VALUE
		// (the audit text said Long.MAX_VALUE; the source caps at Integer.MAX_VALUE — the source wins).
		assertEquals(Integer.MAX_VALUE, SchemaGenProfile.Tier.NIGHTLY.scaled(Integer.MAX_VALUE));
		assertEquals(Integer.MAX_VALUE, SchemaGenProfile.Tier.NIGHTLY.scaled(100_000_000));
	}

	@Test
	void blankTierBehavesAsUnset() {
		withCleanKnobs(() -> {
			// FuzzTiers.java:221-228 (trim-to-null) + 74-78 (unset rule): blank tier is unset; unset
			// without seed is DEFAULT, unset with seed is DEEP.
			System.setProperty(TIER, "  ");
			assertEquals(SchemaGenProfile.Tier.DEFAULT, SchemaGenProfile.currentTier());
			System.setProperty(SEED, "42");
			assertEquals(SchemaGenProfile.Tier.DEEP, SchemaGenProfile.currentTier());
			System.setProperty(TIER, " DeEp ");
			assertEquals(SchemaGenProfile.Tier.DEEP, SchemaGenProfile.currentTier()); // trimmed + ci
			System.clearProperty(TIER);
			System.clearProperty(SEED);
			assertEquals(SchemaGenProfile.Tier.DEFAULT, SchemaGenProfile.currentTier());
		});
	}

	@Test
	void literalCasesOverrideIsHonoredAsWritten() {
		withCleanKnobs(() -> {
			// FuzzTiers.java:207-218: the literal override is returned UNFLOORED (lines 150-153), so 0
			// and negatives are legal and win; blank means unset (FuzzTiers.java:221-228); non-int fails.
			System.setProperty(CASES, "0");
			assertEquals(0, SchemaGenProfile.cases("parity", 5));
			System.setProperty(CASES, "-3");
			assertEquals(-3, SchemaGenProfile.cases("parity", 5));
			System.setProperty(CASES, "   ");
			assertEquals(5, SchemaGenProfile.cases("parity", 5)); // blank = unset -> tier-scaled (DEFAULT)
			System.clearProperty(CASES);
			assertThrows(IllegalArgumentException.class, () -> {
				System.setProperty(CASES, "abc");
				SchemaGenProfile.cases("parity", 5);
			});
		});
	}

	@Test
	void negativeSeedMasksToNonNegative() {
		withCleanKnobs(() -> {
			// FuzzTiers.java:94-104: parse as long and mask with Long.MAX_VALUE (clear the sign bit),
			// so -5 replays as 0x7FFFFFFFFFFFFFFA, not as 5.
			System.setProperty(SEED, "-5");
			assertEquals(-5L & Long.MAX_VALUE, GeneratedOracles.seed());
			System.setProperty(SEED, "1234");
			assertEquals(1234L, GeneratedOracles.seed());
			assertThrows(IllegalArgumentException.class, () -> {
				System.setProperty(SEED, "not-a-long");
				GeneratedOracles.seed();
			});
		});
	}

	@Test
	void describeFormatMatchesFuzzTiers() {
		withCleanKnobs(() -> {
			// FuzzTiers.describe() (FuzzTiers.java:177-180): "tier=DEEP seed=1234 cases-scale=10" —
			// uppercase tier name, integral scale without a trailing .0 (FuzzTiers.java:235-239).
			System.setProperty(TIER, "deep");
			System.setProperty(SEED, "1234");
			assertEquals("tier=DEEP seed=1234 cases-scale=10", GeneratedOracles.describe());
		});
	}
}
