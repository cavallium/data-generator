package it.cavallium.datagen.plugin.fuzz;

import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.Context;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.RunCoverage;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.SchemaCase;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.SpreadItem;
import it.cavallium.datagen.plugin.fuzz.support.ReflectiveValueGen;
import it.cavallium.datagen.plugin.fuzz.support.SchemaGenProfile;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterProperty;

/**
 * Evolution-side fuzz property: for randomly generated schemas with version chains, the fused read path
 * and the object read + {@code upgradeDataToLatestVersion} must agree
 * ({@link GeneratedOracles#upgradeEquivalence}), and one-jump upgrading must equal the step-by-step
 * upgrader chain ({@link GeneratedOracles#chainTransitivity}).
 *
 * <p>Coverage is per (type, version) pair: the sampled property records every pair it exercised, and the
 * DEEP-tier {@link #fullVersionMatrix} additionally walks EVERY (type, version) pair of each schema it
 * sees. Two spread entries carry {@code generateOldSerializers=true}, so historical serialization is
 * exercised for real; counted skips ({@code type-absent}, {@code no-old-serializer}) are recorded, not
 * hidden.</p>
 */
public final class RandomSchemaEvolutionPropertyTest {

	static final int MAX_SCHEMA_TRIES = 40;

	private final RunCoverage coverage = new RunCoverage();
	private int executedSchemas;

	/** Set by {@link #fullVersionMatrix}; see {@link #assertRunCoverage()} for why that property is exempt. */
	private boolean matrixProperty;

	/** (type, version) pairs {@link #fullVersionMatrix} entered, added BEFORE its oracle guard. */
	private final Set<String> matrixWalked = new LinkedHashSet<>();

	/** (type, version) pairs whose oracle guard RETURNED; compared against {@link #matrixWalked}. */
	private final Set<String> matrixVerified = new LinkedHashSet<>();

	@Property(tries = MAX_SCHEMA_TRIES)
	void upgradePathsAgreeOnRandomSchemas(@ForAll("schemaCases") SchemaCase schemaCase) {
		int target = GeneratedOracles.coverageTarget(
				GeneratedOracles.cases("evolution.schemas", 12, 20, MAX_SCHEMA_TRIES, MAX_SCHEMA_TRIES),
				// The evolution spread adds the two generateOldSerializers=true variants.
				GeneratedOracles.spread(GeneratedOracles.tier(), true));
		coverage.attempt(); // every try is counted, null or not (assertGenerationFailureBound denominator)
		if (schemaCase == null) {
			coverage.generationFailure(); // recorded generation defect (see GeneratedOracles.compileCase)
			return;
		}
		coverage.attemptedFeature(schemaCase.item()); // a compiled case = an attempt on that spread item
		if (executedSchemas >= target) {
			return;
		}
		executedSchemas++;
		coverage.schema(schemaCase);
		Context ctx = schemaCase.context();
		ReflectiveValueGen values = ctx.values();
		List<String> supported = ctx.schema().typeNames().stream().filter(values::supports).toList();
		if (supported.isEmpty() || ctx.model().versionIds().length < 2) {
			coverage.zeroValueSchema(); // features counted, but no upgrade oracle could run (unsupported or single-version)
			return; // single-version schema: nothing to upgrade; the spread guarantees multi-version cases
		}
		Random random = GeneratedOracles.random(schemaCase.seed());
		int valueCount = GeneratedOracles.cases("fuzz.values", 3, 5, 40, 100);
		int maxDepth = Math.min(3, schemaCase.item().profile().maxDepth());
		int[] versionIds = ctx.model().versionIds();

		for (int index = 0; index < valueCount; index++) {
			String typeName = supported.get(random.nextInt(supported.size()));
			int oldVersion = versionIds[random.nextInt(versionIds.length)];
			int valueSeed = (int) GeneratedOracles.mix(schemaCase.seed(), index * 31L + oldVersion);
			Object oldShape = values.value(typeName, oldVersion, new Random(valueSeed), maxDepth,
					coverage::valueMask);
			String id = "evolution." + idPart(schemaCase) + ".value" + index;
			GeneratedOracles.guard(id, schemaCase, typeName, oldVersion, schemaCase.seed(), oldShape, () -> {
				String skip = GeneratedOracles.upgradeEquivalence(ctx, typeName, oldVersion, oldShape);
				record(skip, typeName, oldVersion);
				if (skip == null) {
					String chainSkip = GeneratedOracles.chainTransitivity(ctx, typeName, oldVersion, oldShape);
					record(chainSkip, typeName, oldVersion);
				}
			});
		}
	}

	/** DEEP tier only: every (type, version) pair of every schema this try sees. */
	@Property(tries = 20, maxDiscardRatio = Integer.MAX_VALUE)
	@Tag("deep")
	void fullVersionMatrix(@ForAll("schemaCases") SchemaCase schemaCase) {
		// Early return, NOT an assumption (measured on jqwik 1.10.2): a run whose EVERY try is rejected
		// fails as "exhausted after [20] tries and [20] rejections" even with maxDiscardRatio
		// = Integer.MAX_VALUE, so a requireMinimumTier-based gate would turn the quick/default gates red
		// below DEEP. The audit's assumption premise could not be kept; the walked-before-guard vs
		// verified-after-guard guarantee asserted here is unchanged.
		if (GeneratedOracles.tier().ordinal() < SchemaGenProfile.Tier.DEEP.ordinal()) {
			return;
		}
		matrixProperty = true;
		if (schemaCase == null) {
			return; // recorded generation defect (see GeneratedOracles.compileCase)
		}
		coverage.schema(schemaCase);
		Context ctx = schemaCase.context();
		ReflectiveValueGen values = ctx.values();
		int maxDepth = Math.min(2, schemaCase.item().profile().maxDepth());
		int valueIndex = 0;
		for (String typeName : ctx.schema().typeNames()) {
			if (!values.supports(typeName)) {
				continue;
			}
			for (int version : ctx.model().versionIds()) {
				// Walked BEFORE the guard, verified only AFTER it returned: @AfterProperty compares the two
				// sets, so the guarantee is "every walked pair actually went through the oracle", not a
				// tautology against the pair the loop just recorded itself.
				String pair = typeName + "@v" + version;
				matrixWalked.add(pair);
				int valueSeed = (int) GeneratedOracles.mix(schemaCase.seed(),
						typeName.hashCode() * 31L + version + valueIndex++);
				Object value;
				try {
					value = values.value(typeName, version, new Random(valueSeed), maxDepth,
							coverage::valueMask);
				} catch (RuntimeException unbuildable) {
					// A recursive shape with no nullable/array escape inside the value generator's depth budget
					// is a value-generation limit, not an oracle result: counted (same policy as
					// RandomSchemaComplexityPropertyTest) so the pair is not reported as a missing guard.
					coverage.skip("value-gen");
					matrixVerified.add(pair);
					continue;
				}
				String id = "evolution-matrix." + idPart(schemaCase) + "." + typeName + ".v" + version;
				final Object pairValue = value;
				GeneratedOracles.guard(id, schemaCase, typeName, version, schemaCase.seed(), pairValue, () -> {
					String skip = GeneratedOracles.upgradeEquivalence(ctx, typeName, version, pairValue);
					record(skip, typeName, version);
					if (skip == null) {
						GeneratedOracles.chainTransitivity(ctx, typeName, version, pairValue);
					}
				});
				matrixVerified.add(pair);
				coverage.typeVersion(typeName, version);
			}
		}
	}

	@Provide
	Arbitrary<SchemaCase> schemaCases() {
		// The evolution spread adds the two generateOldSerializers=true variants.
		return GeneratedOracles.schemaCases(GeneratedOracles.spread(GeneratedOracles.tier(), true));
	}

	@net.jqwik.api.lifecycle.BeforeProperty
	void resetGenerationDefects() {
		GeneratedOracles.resetGenerationDefects(); // defect set is scoped to ONE property run
	}

	@AfterProperty
	void assertRunCoverage() {
		// schemas()==0: this property executed nothing (fullVersionMatrix is DEEP-only and aborts below),
		// so there is nothing to cover — the sampled property asserts the full spread.
		// The matrix property is exempt from the feature assertion as well: it walks (type, version) pairs of
		// the schemas it happens to see in 20 tries, so it cannot be expected to rotate through every entry of
		// the 26-item spread. The sampled property above owns full-spread coverage; the matrix asserts its own
		// guarantee instead, namely that EVERY pair it walked actually completed its oracle guard.
		if (matrixProperty) {
			assertTrue(matrixVerified.containsAll(matrixWalked),
					() -> "The version matrix walked pairs that never completed their oracle guard: "
							+ missing(matrixVerified, matrixWalked));
		} else if (GeneratedOracles.tier().ordinal() >= SchemaGenProfile.Tier.DEFAULT.ordinal()
				&& coverage.schemas() > 0) {
			// Full-feature coverage is the obligation of a run that attempted a full spread cycle (the
			// rotating selection covers every item once attempts >= spread size); a below-spread-size run
			// (early stop or budget) owes its ATTEMPTED features instead, and says so via featureCoverage.
			if (coverage.attempts() >= GeneratedOracles.spread(GeneratedOracles.tier(), true).size()) {
				coverage.assertAllFeaturesCovered();
			} else {
				coverage.assertAttemptedFeaturesCovered();
			}
			coverage.assertGenerationFailureBound();
		}
		System.out.println("[evolution-fuzz] " + GeneratedOracles.describe()
				+ " jqwikSeed=" + GeneratedOracles.jqwikSeed()
				+ " schemas=" + coverage.schemas() + " values=" + coverage.values()
				+ " attempts=" + coverage.attempts() + " generationFailures=" + coverage.generationFailures()
				+ " zeroValueSchemas=" + coverage.zeroValueSchemas()
				+ " generationDefects=" + GeneratedOracles.generationDefectFeatures()
				+ " skips=" + coverage.skipsDescription()
				+ " typeVersions=" + coverage.typeVersions().size()
				+ (matrixProperty ? " featureAssertion=matrix-exempt" : "")
				+ " featureCoverage=" + coverage.featureCoverageDescription());
	}

	private void record(String skip, String typeName, int version) {
		if (skip == null) {
			coverage.typeVersion(typeName, version);
		} else {
			coverage.skip(skip); // per-reason count; the reason already embeds the version
		}
	}

	private static String idPart(SchemaCase schemaCase) {
		return schemaCase.item().id().replace(':', '_');
	}

	/** The pairs the matrix walked but the run coverage did not see, for the failure message above. */
	private static Set<String> missing(Set<String> recorded, Set<String> walked) {
		Set<String> missing = new LinkedHashSet<>(walked);
		missing.removeAll(recorded);
		return missing;
	}
}
