package it.cavallium.datagen.fuzz;

import java.util.Random;
import org.junit.jupiter.api.Assumptions;

/**
 * Tier, seed and case-count gate shared by every fuzz test in this repository.
 *
 * <p>The class is driven by exactly three system-property knobs, read live on every call so that a
 * test can set and clear them without restarting the JVM:</p>
 * <ul>
 *     <li>{@code -Ddatagen.fuzz.tier} — one of {@code quick|default|deep|nightly}, case-insensitive.
 *     When unset the tier is {@link FuzzTiers.Tier#DEEP} if {@code -Ddatagen.fuzz.seed} is present
 *     (an explicitly reproduced run is treated as a deep campaign) and {@link FuzzTiers.Tier#DEFAULT}
 *     otherwise, which is what plain {@code mvn test} runs. An unparseable value fails fast with
 *     {@link IllegalArgumentException} rather than silently running the wrong budget.</li>
 *     <li>{@code -Ddatagen.fuzz.seed} — the base seed for {@link #seed()}. When unset, a constant
 *     captured once per JVM is used, so every test in one run shares a single seed family that
 *     {@link #describe()} can print and {@code FuzzReplay} can record. The effective seed is never
 *     negative.</li>
 *     <li>{@code -Ddatagen.fuzz.cases} — a global case-count override. When set it replaces the
 *     per-tier count of <em>every</em> call site, which makes a single case reproducible across the
 *     whole suite. The value is used literally, so {@code 0} means "run no cases at all".</li>
 * </ul>
 *
 * <p>Tier meaning: {@code quick} is a smoke pass (&lt; 10 s per module), {@code default} is the
 * reactor budget of {@code mvn test}, {@code deep} is the opt-in campaign tier and {@code nightly}
 * is reserved for exhaustive, MB-scale or bytecode-shape checks.</p>
 *
 * <p>Modelled as a zero-constant enum because this module's main sources are a named module while
 * test sources are not: it keeps the utility non-instantiable and non-extendable without needing a
 * package that the module system has to know about.</p>
 */
public enum FuzzTiers {
	; // utility type, no instances

	/** Selects the tier: {@code quick|default|deep|nightly}. */
	private static final String TIER_PROPERTY = "datagen.fuzz.tier";

	/** Fixes the base seed of the run. */
	private static final String SEED_PROPERTY = "datagen.fuzz.seed";

	/** Overrides every computed case count with one literal value. */
	private static final String CASES_PROPERTY = "datagen.fuzz.cases";

	/**
	 * Seed captured once per JVM and masked non-negative; used when {@code -Ddatagen.fuzz.seed} is
	 * unset so that all callsites of one run share a reproducible seed family.
	 */
	private static final long JVM_CONSTANT = new Random().nextLong() & Long.MAX_VALUE;

	/**
	 * Ordered fuzz budgets. The declaration order <em>is</em> the ordering used by
	 * {@link #requireMinimumTier(Tier)}: {@code ordinal()} grows with the work allowed.
	 */
	public enum Tier {
		/** Smoke: milliseconds, always safe in a hot edit loop. */
		QUICK,
		/** What {@code mvn test} runs. */
		DEFAULT,
		/** Campaign tier: {@code -Ddatagen.fuzz.tier=deep}, minutes to hours. */
		DEEP,
		/** Exhaustive, MB-scale or bytecode-shape checks. */
		NIGHTLY
	}

	/**
	 * Reads {@code -Ddatagen.fuzz.tier} case-insensitively.
	 *
	 * @return the requested tier; {@link Tier#DEEP} when the tier knob is unset but a seed is given,
	 * {@link Tier#DEFAULT} when neither is given
	 * @throws IllegalArgumentException when the knob holds a value that is not a tier name
	 */
	public static Tier tier() {
		String raw = property(TIER_PROPERTY);
		if (raw == null) {
			return property(SEED_PROPERTY) == null ? Tier.DEFAULT : Tier.DEEP;
		}
		for (Tier tier : Tier.values()) {
			if (tier.name().equalsIgnoreCase(raw)) {
				return tier;
			}
		}
		throw new IllegalArgumentException(
				"Invalid " + TIER_PROPERTY + "='" + raw + "': expected quick|default|deep|nightly");
	}

	/**
	 * The base seed of this run: {@code -Ddatagen.fuzz.seed} when set, otherwise {@link #JVM_CONSTANT}.
	 *
	 * @return a non-negative seed; an explicitly negative seed is masked to its non-negative form
	 * @throws IllegalArgumentException when the seed knob holds something that is not a long
	 */
	public static long seed() {
		String raw = property(SEED_PROPERTY);
		if (raw == null) {
			return JVM_CONSTANT;
		}
		try {
			return Long.parseLong(raw) & Long.MAX_VALUE;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid " + SEED_PROPERTY + "='" + raw + "': expected a long", e);
		}
	}

	/**
	 * A deterministically derived, never shared random source for one callsite.
	 *
	 * @param salt distinguishes callsites that share a seed; {@code null} behaves like the empty salt
	 * @return a fresh {@link Random} per invocation, so callers can advance it without affecting
	 * anybody else, while the first values of two instances built from the same seed and salt match
	 */
	public static Random random(String salt) {
		return new Random(mix(seed(), salt));
	}

	/**
	 * Per-tier case budget, with {@code -Ddatagen.fuzz.cases} as a global override.
	 *
	 * @param id identifies the callsite (kept for diagnostics and future per-callite overrides)
	 * @param quick cases at {@link Tier#QUICK}
	 * @param base cases at {@link Tier#DEFAULT}
	 * @param deep cases at {@link Tier#DEEP}
	 * @param nightly cases at {@link Tier#NIGHTLY}
	 * @return the override when set, otherwise the entry for the current tier, never below one
	 * ({@code atLeastOne} floors only the TIER-COMPUTED path: the literal {@code -Ddatagen.fuzz.cases}
	 * value is returned as written, so {@code 0} or a negative override is honored verbatim)
	 */
	public static int cases(String id, int quick, int base, int deep, int nightly) {
		Integer override = casesOverride();
		if (override != null) {
			return override;
		}
		return atLeastOne(switch (tier()) {
			case QUICK -> quick;
			case DEFAULT -> base;
			case DEEP -> deep;
			case NIGHTLY -> nightly;
		});
	}

	/**
	 * Per-tier case budget derived from one base count, with {@code -Ddatagen.fuzz.cases} as a global
	 * override.
	 *
	 * @param id identifies the callsite (kept for diagnostics and future per-callite overrides)
	 * @param base the {@link Tier#DEFAULT} budget
	 * @return {@code base} scaled by {@link #multiplierFor(Tier)} ({@code 0.1 / 1 / 10 / 50}), never
	 * below one, or the override when that knob is set
	 */
	public static int cases(String id, int base) {
		Integer override = casesOverride();
		if (override != null) {
			return override;
		}
		double scaled = (double) base * multiplierFor(tier());
		if (!(scaled < (double) Integer.MAX_VALUE)) {
			return Integer.MAX_VALUE;
		}
		return atLeastOne((int) Math.round(scaled));
	}

	/**
	 * Engine-agnostic tier gate: a lower tier <em>aborts</em> the test instead of failing it.
	 *
	 * @param required the minimum tier this test needs to be worth running
	 */
	public static void requireMinimumTier(Tier required) {
		Assumptions.assumeTrue(tier().compareTo(required) >= 0,
				() -> "requires -" + TIER_PROPERTY + ">=" + required + " (current=" + tier() + ")");
	}

	/**
	 * Human-readable gate prefixed to fuzz failure messages, so a red run states its own budget.
	 *
	 * @return something like {@code tier=DEEP seed=1234 cases-scale=10}, where {@code cases-scale} is
	 * the multiplier {@link #cases(String, int)} applies to a callsite's base count
	 */
	public static String describe() {
		Tier tier = tier();
		return "tier=" + tier + " seed=" + seed() + " cases-scale=" + formatScale(multiplierFor(tier));
	}

	/**
	 * Growth factor applied to a callsite's base case count.
	 *
	 * @param tier the tier to scale for
	 * @return {@code 0.1} for {@link Tier#QUICK} (floored at one case), {@code 1} for
	 * {@link Tier#DEFAULT}, {@code 10} for {@link Tier#DEEP}, {@code 50} for {@link Tier#NIGHTLY}
	 */
	private static double multiplierFor(Tier tier) {
		return switch (tier) {
			case QUICK -> 0.1d;
			case DEFAULT -> 1d;
			case DEEP -> 10d;
			case NIGHTLY -> 50d;
		};
	}

	/**
	 * Mixes the run seed with a callsite salt. Pure: the same {@code (seed, salt)} pair always yields
	 * the same value, which is what makes {@link #random(String)} replayable.
	 */
	private static long mix(long seed, String salt) {
		long saltMix = (salt == null ? 0L : (long) salt.hashCode()) * 0x9E3779B97F4A7C15L;
		return Long.rotateLeft(seed, 17) ^ saltMix;
	}

	/** @return the literal {@code -Ddatagen.fuzz.cases} value, or {@code null} when the knob is unset */
	private static Integer casesOverride() {
		String raw = property(CASES_PROPERTY);
		if (raw == null) {
			return null;
		}
		try {
			return Integer.valueOf(raw);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid " + CASES_PROPERTY + "='" + raw + "': expected an int", e);
		}
	}

	/** @return the trimmed property value, or {@code null} when unset or blank */
	private static String property(String key) {
		String raw = System.getProperty(key);
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static int atLeastOne(int cases) {
		return Math.max(1, cases);
	}

	/** Prints integral scales without a trailing {@code .0}, so the gate stays readable. */
	private static String formatScale(double scale) {
		if (scale == Math.rint(scale) && Math.abs(scale) <= (double) Long.MAX_VALUE) {
			return Long.toString((long) scale);
		}
		return Double.toString(scale);
	}
}
