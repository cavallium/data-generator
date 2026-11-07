package it.cavallium.datagen.plugin.fuzz;

import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.Context;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.RunCoverage;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.SchemaCase;
import it.cavallium.datagen.plugin.fuzz.support.ReflectiveValueGen;
import it.cavallium.datagen.plugin.fuzz.support.SchemaGenProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterProperty;

/**
 * Corruption-side fuzz property: mutated payloads must either decode or fail with one of the three
 * accepted decode-time exceptions, IDENTICALLY across all five storages, with balanced cursor accounting
 * ({@link GeneratedOracles#corruptAcceptOrStableFail}); and every projection of a schema must agree with
 * the full read, with its {@code Sink}, and with its bound {@code Reader}
 * ({@link GeneratedOracles#projectionParity}).
 */
public final class RandomSchemaCorruptionPropertyTest {

	static final int MAX_SCHEMA_TRIES = 40;

	private final RunCoverage coverage = new RunCoverage();
	private int executedSchemas;
	private int projectionsChecked;
	private final List<String> mutationNotes = new ArrayList<>();

	@Property(tries = MAX_SCHEMA_TRIES)
	void corruptedPayloadsAndProjectionsStayConsistent(@ForAll("schemaCases") SchemaCase schemaCase) {
		int target = GeneratedOracles.coverageTarget(
				GeneratedOracles.cases("corruption.schemas", 10, 16, MAX_SCHEMA_TRIES, MAX_SCHEMA_TRIES),
				GeneratedOracles.spread(GeneratedOracles.tier(), false));
		coverage.attempt(); // every try is counted, null or not (assertGenerationFailureBound denominator)
		if (schemaCase == null) {
			coverage.generationFailure(); // recorded generation defect (see GeneratedOracles.compileCase)
			return; // counted, not hidden
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
		if (supported.isEmpty()) {
			coverage.zeroValueSchema(); // features counted, but no value oracle could run on it
			return;
		}
		Random random = GeneratedOracles.random(schemaCase.seed());
		int valueCount = GeneratedOracles.cases("fuzz.values", 3, 5, 40, 100);
		int mutationBudget = GeneratedOracles.cases("corruption.mutations", 6, 12, 24, 64);
		int maxDepth = Math.min(3, schemaCase.item().profile().maxDepth());
		int current = ctx.currentVersion();

		// A projection source, if this schema has one, gets checked in every value iteration.
		List<String> projectionSources = ctx.schema().config().projectionsData == null
				? List.of()
				: ctx.schema().config().projectionsData.values().stream()
						.map(projection -> projection.sourceType)
						.filter(supported::contains)
						.distinct()
						.toList();

		for (int index = 0; index < valueCount; index++) {
			String typeName = supported.get(random.nextInt(supported.size()));
			int valueSeed = (int) GeneratedOracles.mix(schemaCase.seed(), index * 17L);
			Object value = values.value(typeName, current, new Random(valueSeed), maxDepth,
					coverage::valueMask);
			String id = "corruption." + idPart(schemaCase) + ".value" + index;
			GeneratedOracles.guard(id, schemaCase, typeName, current, schemaCase.seed(), value, () ->
					GeneratedOracles.corruptAcceptOrStableFail(ctx, typeName, current, value,
							new Random(valueSeed ^ 0xC0FFEE), mutationBudget));
			coverage.typeVersion(typeName, current);
		}

		if (!projectionSources.isEmpty()) {
			String projectionSource = projectionSources.get(random.nextInt(projectionSources.size()));
			int valueSeed = (int) GeneratedOracles.mix(schemaCase.seed(), 0x9E37L);
			Object value = values.value(projectionSource, current, new Random(valueSeed), maxDepth,
					coverage::valueMask);
			String id = "projections." + idPart(schemaCase);
			GeneratedOracles.guard(id, schemaCase, projectionSource, current, schemaCase.seed(), value, () ->
					projectionsChecked += GeneratedOracles.projectionParity(ctx, projectionSource, value));
			coverage.typeVersion(projectionSource, current);
		}
	}

	@Provide
	Arbitrary<SchemaCase> schemaCases() {
		return GeneratedOracles.schemaCases(GeneratedOracles.spread(GeneratedOracles.tier(), false));
	}

	@net.jqwik.api.lifecycle.BeforeProperty
	void resetGenerationDefects() {
		GeneratedOracles.resetGenerationDefects(); // defect set is scoped to ONE property run
	}

	@AfterProperty
	void assertRunCoverage() {
		if (GeneratedOracles.tier().ordinal() >= SchemaGenProfile.Tier.DEFAULT.ordinal()) {
			// Full-feature coverage is the obligation of a run that attempted a full spread cycle (the
			// rotating selection covers every item once attempts >= spread size); a below-spread-size run
			// (early stop or budget) owes its ATTEMPTED features instead, and says so via featureCoverage.
			if (coverage.attempts() >= GeneratedOracles.spread(GeneratedOracles.tier(), false).size()) {
				coverage.assertAllFeaturesCovered();
			} else {
				coverage.assertAttemptedFeaturesCovered();
			}
			coverage.assertGenerationFailureBound();
		}
		System.out.println("[corruption-fuzz] " + GeneratedOracles.describe()
				+ " jqwikSeed=" + GeneratedOracles.jqwikSeed()
				+ " schemas=" + coverage.schemas() + " values=" + coverage.values()
				+ " attempts=" + coverage.attempts() + " generationFailures=" + coverage.generationFailures()
				+ " zeroValueSchemas=" + coverage.zeroValueSchemas()
				+ " generationDefects=" + GeneratedOracles.generationDefectFeatures()
				+ " skips=" + coverage.skipsDescription()
				+ " projectionsChecked=" + projectionsChecked
				+ " notes=" + mutationNotes.size()
				+ " featureCoverage=" + coverage.featureCoverageDescription());
	}

	private static String idPart(SchemaCase schemaCase) {
		return schemaCase.item().id().replace(':', '_');
	}
}
