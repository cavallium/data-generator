package it.cavallium.datagen.plugin.fuzz;

import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.Context;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.RunCoverage;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.SchemaCase;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.SpreadItem;
import it.cavallium.datagen.plugin.fuzz.support.ReflectiveValueGen;
import it.cavallium.datagen.plugin.fuzz.support.SchemaGenProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterProperty;

/**
 * Codec-side fuzz property: for randomly generated schemas, values must survive every read path
 * ({@link GeneratedOracles#roundTripAllPaths}), serialize byte-deterministically, keep {@code skip}
 * semantics, satisfy the generated value contract, respect decode limits, and every historical codec must
 * decode its own bytes.
 *
 * <p>One schema per try ({@link GeneratedOracles#schemaCases}, never shrunk); the schema-feature and
 * value-category coverage of the whole run is asserted in {@link #assertRunCoverage()}. The number of
 * executed schemas and of values per schema comes from the tier-bridged {@code cases(...)} knobs. Values
 * are checked strictly in order, so a failure always names the FIRST failing value index (manual value
 * shrinking; jqwik never sees the values).</p>
 */
public final class RandomSchemaCodecPropertyTest {

	/** Upper bound of executed schema cases (tiers below DEEP stop earlier via {@code cases(...)}). */
	static final int MAX_SCHEMA_TRIES = 80;

	private final RunCoverage coverage = new RunCoverage();
	private final List<String> toStringSamples = new ArrayList<>();
	private int executedSchemas;

	@Property(tries = MAX_SCHEMA_TRIES)
	void codecOraclesHoldOnRandomSchemas(@net.jqwik.api.ForAll("schemaCases") SchemaCase schemaCase) {
		int target = GeneratedOracles.coverageTarget(
				GeneratedOracles.cases("codec.schemas", 24, 32, 80, MAX_SCHEMA_TRIES),
				GeneratedOracles.spread(GeneratedOracles.tier(), false));
		coverage.attempt(); // every try is counted, null or not (assertGenerationFailureBound denominator)
		if (schemaCase == null) {
			coverage.generationFailure(); // recorded generation defect (see GeneratedOracles.compileCase)
			return; // counted, not hidden
		}
		if (executedSchemas >= target) {
			return; // tier wants fewer schemas than the annotation upper bound; extra tries pass through
		}
		executedSchemas++;
		coverage.schema(schemaCase);
		Context ctx = schemaCase.context();
		ReflectiveValueGen values = ctx.values();
		List<String> supported = ctx.schema().typeNames().stream().filter(values::supports).toList();
		if (supported.isEmpty()) {
			coverage.zeroValueSchema(); // features counted, but no value oracle could run on it
			return;
		}
		Random random = GeneratedOracles.random(schemaCase.seed());
		String typeName = supported.get(random.nextInt(supported.size()));
		int[] versionIds = ctx.model().versionIds();
		int valueCount = GeneratedOracles.cases("fuzz.values", 3, 5, 40, 100);
		int maxDepth = Math.min(3, schemaCase.item().profile().maxDepth());
		int current = ctx.currentVersion();

		for (int index = 0; index < valueCount; index++) {
			int valueSeed = (int) GeneratedOracles.mix(schemaCase.seed(), index);
			Object value = values.value(typeName, current, new Random(valueSeed), maxDepth,
					coverage::valueMask);
			String id = "codec." + idPart(schemaCase) + ".value" + index;
			GeneratedOracles.guard(id, schemaCase, typeName, current, schemaCase.seed(), value, () -> {
				GeneratedOracles.roundTripAllPaths(ctx, typeName, current, value,
						GeneratedOracles.VALUE_LIMITS);
				GeneratedOracles.byteDeterminism(ctx, current, typeName, value);
				GeneratedOracles.skipConsumesOne(ctx, current, typeName, value, GeneratedOracles.VALUE_LIMITS);
				toStringSamples.add(GeneratedOracles.valueContract(ctx, typeName, value));
				GeneratedOracles.decodeLimits(ctx, typeName, current, value);
			});
			coverage.typeVersion(typeName, current);

			// oracle 6: a randomly chosen historical version must self-decode its own value.
			int historical = versionIds[random.nextInt(versionIds.length)];
			if (historical != current) {
				Object oldShape = values.value(typeName, historical,
						new Random(valueSeed ^ 0x5E37L), maxDepth, coverage::valueMask);
				GeneratedOracles.guard(id + ".v" + historical, schemaCase, typeName, historical,
						schemaCase.seed(), oldShape, () -> {
							String skip = GeneratedOracles.historicalSelfDecodable(ctx, typeName, historical, oldShape);
							if (skip != null) {
								coverage.skip(skip);
								coverage.typeVersion(typeName + "#" + skip, historical);
							} else {
								coverage.typeVersion(typeName, historical);
							}
						});
			}
		}
	}

	@Provide
	Arbitrary<SchemaCase> schemaCases() {
		List<SpreadItem> items = GeneratedOracles.spread(GeneratedOracles.tier(), false);
		return GeneratedOracles.schemaCases(items);
	}

	@net.jqwik.api.lifecycle.BeforeProperty
	void resetGenerationDefects() {
		GeneratedOracles.resetGenerationDefects(); // defect set is scoped to ONE property run
	}

	@AfterProperty
	void assertRunCoverage() {
		if (GeneratedOracles.tier().ordinal() >= SchemaGenProfile.Tier.DEFAULT.ordinal()) {
			coverage.assertAllFeaturesCovered();
			coverage.assertAllValueCategories();
			coverage.assertGenerationFailureBound();
		}
		System.out.println("[codec-fuzz] " + GeneratedOracles.describe()
				+ " jqwikSeed=" + GeneratedOracles.jqwikSeed()
				+ " schemas=" + coverage.schemas() + " values=" + coverage.values()
				+ " attempts=" + coverage.attempts() + " generationFailures=" + coverage.generationFailures()
				+ " zeroValueSchemas=" + coverage.zeroValueSchemas()
				+ " generationDefects=" + GeneratedOracles.generationDefectFeatures()
				+ " skips=" + coverage.skipsDescription()
				+ " toStringCollisionRate=" + String.format(java.util.Locale.ROOT, "%.4f",
						GeneratedOracles.toStringCollisionRate(toStringSamples))
				+ " typeVersions=" + coverage.typeVersions().size());
	}

	private static String idPart(SchemaCase schemaCase) {
		return schemaCase.item().id().replace(':', '_');
	}
}
