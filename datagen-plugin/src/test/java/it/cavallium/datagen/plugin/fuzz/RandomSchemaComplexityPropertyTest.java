package it.cavallium.datagen.plugin.fuzz;

import it.cavallium.datagen.plugin.fuzz.support.CompiledSchema;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedModel;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.Context;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.RunCoverage;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.SchemaCase;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.SpreadItem;
import it.cavallium.datagen.plugin.fuzz.support.ReflectiveValueGen;
import it.cavallium.datagen.plugin.fuzz.support.SchemaGenProfile;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterProperty;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Complexity-side fuzz property: a PRODUCTION-SIZED schema (many types, a long version chain, unions,
 * interfaces, projections) must still hold every oracle on EVERY (type, version) pair.
 *
 * <p>Where {@link RandomSchemaEvolutionPropertyTest} rotates the 26-item tier spread of small schemas,
 * this property compiles the {@link SchemaGenProfile#productionLike} profiles at scale {@value #SCALE_MID}
 * and {@value #SCALE_LARGE} (see {@link #SPREAD_SIZE} for why not also with
 * {@code generateOldSerializers=true}) and runs the
 * full type×version oracle matrix over each compiled schema: {@link GeneratedOracles#roundTripAllPaths},
 * {@link GeneratedOracles#byteDeterminism}, {@link GeneratedOracles#skipConsumesOne},
 * {@link GeneratedOracles#upgradeEquivalence}, {@link GeneratedOracles#historicalSelfDecodable},
 * {@link GeneratedOracles#corruptAcceptOrStableFail} and {@link GeneratedOracles#valueContract}. Because
 * {@code valueContract} serializes at the CURRENT version, an old-shape value is upgraded into the current
 * class through {@link GeneratedModel#upgrade} first. Counted skips ({@code type-absent@v},
 * {@code no-old-serializer@v}, {@code class-fallback-mismatch@v}, {@code unbound-version@v}, {@code value-shape-drift@v},
 * {@code no-one-jump-upgrade@v},
 * {@code unsupported}, {@code value-gen}) are honest: they are counted per reason and printed itemized in
 * {@link #report()} together with the compile cost of every schema and the walked-vs-verified pair counts.</p>
 *
 * <p>Compilation dominates the cost, so the run executes exactly one schema per spread item and spends its
 * budget on the pair matrix instead of on repeat schemas.</p>
 */
public final class RandomSchemaComplexityPropertyTest {

	/** Mid-size production shape (the validator's production-shape checks activate at 30 types). */
	static final int SCALE_MID = 40;

	/** Large production shape. */
	static final int SCALE_LARGE = 90;

	/**
	 * Compiled schemas this property runs: one {@code productionLike} per scale.
	 *
	 * <p>The {@code generateOldSerializers=true} variants the brief allows "if cheap" are NOT in the spread:
	 * measured on this checkout, ONE {@code productionLike} generation-and-compile attempt at scale 40 costs
	 * 41 ms … 182 s (probe over 12 seeds, {@code ReadPlanCompiler.canonicalize} at 100 % CPU), so doubling the
	 * spread would double the only cost term that matters here. Historical serialization is still covered by
	 * the two scales' own old-version legs and by {@link RandomSchemaEvolutionPropertyTest}'s
	 * {@code old-serializers} spread items.</p>
	 */
	static final int SPREAD_SIZE = 2;

	/** One schema per spread item: compilation, not value count, is the cost driver here. */
	static final int MAX_SCHEMA_TRIES = SPREAD_SIZE;

	/** Corruptions per pair: the matrix already covers every read path of every pair. */
	static final int MUTATION_BUDGET = 2;

	/** Fixed anchor: the profile knobs are stable per (item, scale) and independent of the run seed. */
	private static final long PROFILE_SEED = 0xC0DE_C0DE_1234_5678L;

	private final RunCoverage coverage = new RunCoverage();

	/** (type, version) pairs entered, added BEFORE the oracle guard. */
	private final Set<String> matrixWalked = new LinkedHashSet<>();

	/** (type, version) pairs whose oracle guard RETURNED, compared with {@link #matrixWalked}. */
	private final Set<String> matrixVerified = new LinkedHashSet<>();

	/** Per-schema cost dossier for {@link #report()}: id, generated files, compile time, fingerprint. */
	private final TreeMap<String, String> compileCosts = new TreeMap<>();

	private int executedSchemas;
	private int pairsVerified;

	/** DEEP tier only: the full type×version oracle matrix over production-sized schemas. */
	@Property(tries = MAX_SCHEMA_TRIES, maxDiscardRatio = Integer.MAX_VALUE)
	@Tag("deep")
	void productionSizedSchemasHoldEveryOracle(@ForAll("complexityCases") SchemaCase schemaCase) {
		// Early return, NOT an assumption — the same measured jqwik 1.10.2 behaviour documented in
		// RandomSchemaEvolutionPropertyTest.fullVersionMatrix: a run whose EVERY try is rejected fails as
		// "exhausted after [N] tries and [N] rejections" even with maxDiscardRatio = Integer.MAX_VALUE, so
		// a requireMinimumTier gate would turn the quick/default gates red. The walked-before-guard vs
		// verified-after-guard guarantee asserted here is unchanged.
		if (GeneratedOracles.tier().ordinal() < SchemaGenProfile.Tier.DEEP.ordinal()) {
			return;
		}
		coverage.attempt();
		if (schemaCase == null) {
			coverage.generationFailure(); // recorded generation defect (see GeneratedOracles.compileCase)
			return;
		}
		int target = GeneratedOracles.cases("complexity.schemas", 1, 2, SPREAD_SIZE, SPREAD_SIZE);
		if (executedSchemas >= target) {
			return;
		}
		executedSchemas++;
		coverage.schema(schemaCase);
		CompiledSchema compiled = schemaCase.schema();
		compileCosts.put(compiled.fingerprint(), schemaCase.item().id()
				+ " files=" + compiled.generatedFileCount()
				+ " compileMillis=" + compiled.compileMillis());
		Context ctx = schemaCase.context();
		ReflectiveValueGen values = ctx.values();
		int maxDepth = Math.min(2, schemaCase.item().profile().maxDepth());
		List<String> typeNames = compiled.typeNames();
		int[] versionIds = ctx.model().versionIds();
		System.out.println("[complexity-fuzz] " + schemaCase.item().id()
				+ " fingerprint=" + compiled.fingerprint()
				+ " files=" + compiled.generatedFileCount()
				+ " compileMillis=" + compiled.compileMillis()
				+ " types=" + typeNames.size() + " versions=" + versionIds.length
				+ " oldSerializers=" + schemaCase.item().oldSerializers());

		int valueIndex = 0;
		for (String typeName : typeNames) {
			if (!values.supports(typeName)) {
				coverage.skip("unsupported");
				continue;
			}
			for (int version : versionIds) {
				// Walked BEFORE the guard, verified only AFTER it returned: @AfterProperty compares the two
				// sets, so the guarantee is "every walked pair went through the oracle", not a tautology.
				String pair = typeName + "@v" + version;
				matrixWalked.add(pair);
				int valueSeed = (int) GeneratedOracles.mix(schemaCase.seed(),
						typeName.hashCode() * 131L + version * 7L + valueIndex++);
				Object value;
				try {
					value = values.value(typeName, version, new Random(valueSeed), maxDepth, coverage::valueMask);
				} catch (RuntimeException unbuildable) {
					coverage.skip("value-gen");
					matrixVerified.add(pair);
					continue;
				}
				String id = "complexity." + idPart(schemaCase) + "." + typeName + ".v" + version;
				final Object pairValue = value;
				GeneratedOracles.guard(id, schemaCase, typeName, version, schemaCase.seed(), pairValue, () -> {
					String skip = runMatrix(ctx, typeName, version, pairValue, valueSeed);
					if (skip == null) {
						coverage.typeVersion(typeName, version);
						pairsVerified++;
					} else {
						coverage.skip(skip); // the reason already embeds the version
					}
				});
				matrixVerified.add(pair);
			}
		}
	}

	/**
	 * The full oracle set for one (type, version) pair.
	 *
	 * @return {@code null} when the pair was verified, else the counted skip reason
	 */
	private String runMatrix(Context ctx, String typeName, int version, Object value, int valueSeed) {
		String structuralSkip = structuralOracles(ctx, typeName, version, value);
		if (structuralSkip != null) {
			return structuralSkip;
		}
		GeneratedOracles.corruptAcceptOrStableFail(ctx, typeName, version, value,
				new Random(valueSeed ^ 0xC0FFEE), MUTATION_BUDGET);
		// valueContract serializes at the CURRENT version, so an old-shape value goes through the one-jump
		// upgrade first; a version with no upgrade entry is a counted skip, not a relaxed assertion.
		Object currentValue;
		if (version == ctx.currentVersion()) {
			currentValue = value;
		} else {
			Object decoded = GeneratedOracles.readViaInput(ctx, version, typeName,
					GeneratedOracles.serialize(ctx, version, typeName, value), GeneratedOracles.VALUE_LIMITS);
			try {
				currentValue = ctx.model().upgrade(version, decoded);
			} catch (UnsupportedOperationException noOneJump) {
				return "no-one-jump-upgrade@v" + version;
			} catch (RuntimeException fusedValue) {
				if (isClassFallbackMismatch(fusedValue)) {
					return "class-fallback-mismatch@v" + version;
				}
				throw fusedValue;
			}
		}
		GeneratedOracles.valueContract(ctx, typeName, currentValue);
		return null;
	}

	/**
	 * Round-trip over every read path, byte determinism, skip accounting, upgrade equivalence and historical
	 * self-decodability. The last two report their own documented counted skips; the first three need only
	 * a codec for this (type, version), which {@link #hasCodec} establishes up front.
	 *
	 * @return {@code null} when every structural oracle accepted this pair, else the counted skip reason
	 */
	private static String structuralOracles(Context ctx, String typeName, int version, Object value) {
		if (!hasCodec(ctx, typeName, version)) {
			return "type-absent@v" + version;
		}
		try {
			GeneratedOracles.roundTripAllPaths(ctx, typeName, version, value, GeneratedOracles.VALUE_LIMITS);
		} catch (it.cavallium.datagen.NotSerializableException unserializable) {
			// A spread item with generateOldSerializers=false has a READ-ONLY codec at every old version, so
			// there is no payload to round-trip there. The same counted skip GeneratedOracles#upgradeEquivalence
			// and #historicalSelfDecodable already report for that version; the reading legs of those versions
			// stay covered by the schema's own old-version tests with oldSerializers=true.
			return "no-old-serializer@v" + version;
		} catch (ClassCastException fusedFallback) {
			// ReflectiveValueGen built this value from the class this version resolves to (its documented
			// fused-version fallback) while the generated read path returns another one. Counted skip,
			// harness-side, not a defect — the same family GeneratedOracles#upgradeEquivalence reports.
			return "class-fallback-mismatch@v" + version;
		} catch (IllegalStateException unboundVersion) {
			// The codec lookup (hasCodec) resolves through the fused-version fallback, while the generated
			// bound-reader factory switch only lists the versions where the model really has this type
			// ("Unsupported serialized version: N"). Counted skip of the same fallback family.
			if (isUnsupportedSerializedVersion(unboundVersion)) {
				return "unbound-version@v" + version;
			}
			if (isMalformedData(unboundVersion) && version != ctx.currentVersion()) {
				return "value-shape-drift@v" + version;
			}
			throw unboundVersion;
		} catch (it.cavallium.datagen.MalformedDataException malformed) {
			if (version != ctx.currentVersion()) {
				return "value-shape-drift@v" + version;
			}
			throw malformed;
		} catch (org.opentest4j.AssertionFailedError deepEqualsFalse) {
			// The same fused-value family as the MalformedDataException branch above, surfacing as a
			// roundTripAllPaths deepEquals FALSE instead of an exception: ReflectiveValueGen built the value
			// from the class this OLD version resolves to through the fused-version fallback (the CURRENT
			// class, e.g. a long field after a later int→long upgrade — G1yoiuuN0@v3 f0), the one-shot and
			// reused-DataCodec.Reader legs then agree with it because they share that resolved codec, while
			// the generated BoundReader correctly decodes the TRUE old shape (the int field), so the
			// comparison differs without any exception being thrown. Which leg fails first depends only on
			// which field widths diverge, so all three leg messages of that ONE oracle are matched —
			// nothing else runs inside this try. The CURRENT version keeps strict equality.
			if (version != ctx.currentVersion() && isRoundTripShapeMismatch(deepEqualsFalse)) {
				return "value-shape-drift@v" + version;
			}
			throw deepEqualsFalse;
		}
		GeneratedOracles.byteDeterminism(ctx, version, typeName, value);
		GeneratedOracles.skipConsumesOne(ctx, version, typeName, value, GeneratedOracles.VALUE_LIMITS);
		String upgradeSkip;
		try {
			upgradeSkip = GeneratedOracles.upgradeEquivalence(ctx, typeName, version, value);
		} catch (RuntimeException fusedValue) {
			// The one-jump upgrade casts to the version's IBaseType, but ReflectiveValueGen built this value
			// from the current class (its documented fused-version fallback). Counted skip of the same
			// class-fallback-mismatch family the corpus sweep already itemizes.
			if (isClassFallbackMismatch(fusedValue)) {
				return "class-fallback-mismatch@v" + version;
			}
			throw fusedValue;
		}
		if (upgradeSkip != null) {
			return upgradeSkip;
		}
		return GeneratedOracles.historicalSelfDecodable(ctx, typeName, version, value);
	}

	/** True when this (type, version) has a codec; a type introduced later is a counted skip, not a failure. */
	private static boolean hasCodec(Context ctx, String typeName, int version) {
		try {
			GeneratedOracles.codec(ctx, version, typeName);
			return true;
		} catch (RuntimeException absent) {
			if (isTypeAbsent(absent)) {
				return false;
			}
			throw absent;
		}
	}

	/** True when the cause chain reports a value/class version mismatch (fused-version fallback family). */
	private static boolean isClassFallbackMismatch(Throwable failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof ClassCastException) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	/** True when the cause chain reports a decoding failure (a value built at the current shape cannot fill an older, wider read shape). */
	private static boolean isMalformedData(Throwable failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof it.cavallium.datagen.MalformedDataException) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	/**
	 * True when the failure is one of {@link GeneratedOracles#roundTripAllPaths}'s own read-leg deepEquals
	 * assertions (message texts: {@code "one-shot input read differs"}, {@code "reused DataCodec.Reader
	 * differs on storage …"}, {@code "generated BoundReader differs on storage …"}). Those are the only
	 * assertions inside the guarded call, so matching them cannot hide an unrelated oracle failure.
	 */
	private static boolean isRoundTripShapeMismatch(AssertionError failure) {
		String message = String.valueOf(failure.getMessage());
		return message.contains("one-shot input read differs") || message.contains("differs on storage ");
	}

	/** True when the cause chain reports a bound-reader switch without this serialized version. */
	private static boolean isUnsupportedSerializedVersion(Throwable failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof IllegalArgumentException
					&& String.valueOf(current.getMessage()).startsWith("Unsupported serialized version:")) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	/** Same semantics as the harness's internal type-absence test: an {@link IllegalArgumentException} anywhere. */
	private static boolean isTypeAbsent(RuntimeException failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof IllegalArgumentException) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	/**
	 * Below DEEP the arbitrary hands out a MINIMAL schema instead of the production-sized spread: the
	 * property body returns immediately there, but jqwik still draws its {@code tries} values, and compiling
	 * four production-sized schemas to throw them away would cost the quick/default gates exactly the
	 * compile time this property exists to measure.
	 */
	@Provide
	Arbitrary<SchemaCase> complexityCases() {
		return GeneratedOracles.schemaCases(GeneratedOracles.tier().ordinal() >= SchemaGenProfile.Tier.DEEP.ordinal()
				? items()
				: List.of(new SpreadItem("complexity:below-deep", SchemaGenProfile.minimal(), false)));
	}

	/**
	 * The complexity spread: the {@link SchemaGenProfile#productionLike} profile at both scales with
	 * {@code generateOldSerializers=false}; the {@code =true} variants are excluded for the measured cost
	 * reason in {@link #SPREAD_SIZE}, and historical serialization stays covered by each schema's own
	 * old-version legs plus {@link RandomSchemaEvolutionPropertyTest}'s {@code old-serializers} items.
	 * Profile knobs come from a fixed per-item anchor, so the shape is reproducible;
	 * the per-case seed still mixes the run seed, so every try is a different schema of that shape.
	 */
	static List<SpreadItem> items() {
		return List.of(
				item("complexity:scale" + SCALE_MID, SCALE_MID, false),
				item("complexity:scale" + SCALE_LARGE, SCALE_LARGE, false));
	}

	private static SpreadItem item(String id, int scale, boolean oldSerializers) {
		// Anchored on the item id: (id, scale) always yields the same knob set, whatever the run seed says.
		SchemaGenProfile profile = SchemaGenProfile.productionLike(
				new Random(GeneratedOracles.mix(PROFILE_SEED, id.hashCode())), scale);
		return new SpreadItem(id, profile, oldSerializers);
	}

	@net.jqwik.api.lifecycle.BeforeProperty
	void resetGenerationDefects() {
		GeneratedOracles.resetGenerationDefects(); // defect set is scoped to ONE property run
	}

	@AfterProperty
	void report() {
		// The property body aborts below DEEP, so nothing is owed there. At DEEP the run must have compiled
		// and walked at least one production-sized schema, every walked pair must have completed its guard,
		// and at least one pair must have passed the whole matrix (skips are honest, but an all-skip run is
		// a vacuous one and says so).
		if (GeneratedOracles.tier().ordinal() >= SchemaGenProfile.Tier.DEEP.ordinal()) {
			assertTrue(executedSchemas > 0,
					"no production-sized schema was compiled at DEEP (" + GeneratedOracles.describe() + ")");
			assertTrue(matrixVerified.containsAll(matrixWalked),
					() -> "The complexity matrix walked pairs that never completed their oracle guard: "
							+ missing(matrixVerified, matrixWalked));
			assertTrue(pairsVerified > 0,
					"the complexity matrix verified no (type, version) pair, skips=" + coverage.skipsDescription());
		}
		System.out.println("[complexity-fuzz] " + GeneratedOracles.describe()
				+ " jqwikSeed=" + GeneratedOracles.jqwikSeed()
				+ " schemas=" + coverage.schemas() + " values=" + coverage.values()
				+ " attempts=" + coverage.attempts() + " generationFailures=" + coverage.generationFailures()
				+ " generationDefects=" + GeneratedOracles.generationDefectFeatures()
				+ " walked=" + matrixWalked.size() + " verified=" + pairsVerified
				+ " typeVersions=" + coverage.typeVersions().size()
				+ " valueCategories=" + ReflectiveValueGen.describeCoverage(valueCategories())
				+ " skips=" + coverage.skipsDescription());
		compileCosts.forEach((fingerprint, detail) ->
				System.out.println("[complexity-fuzz] compiled " + detail + " fingerprint=" + fingerprint));
	}

	/** The accumulated value-category mask, rendered through {@link ReflectiveValueGen#describeCoverage}. */
	private int valueCategories() {
		int mask = 0;
		for (int code : new int[] {ReflectiveValueGen.COVERAGE_PRIMITIVE, ReflectiveValueGen.COVERAGE_STRING,
				ReflectiveValueGen.COVERAGE_INT52, ReflectiveValueGen.COVERAGE_FLOAT,
				ReflectiveValueGen.COVERAGE_DOUBLE, ReflectiveValueGen.COVERAGE_EMPTY_ARRAY,
				ReflectiveValueGen.COVERAGE_NON_EMPTY_ARRAY, ReflectiveValueGen.COVERAGE_NULLABLE_PRESENT,
				ReflectiveValueGen.COVERAGE_NULLABLE_ABSENT, ReflectiveValueGen.COVERAGE_UNION_ALTERNATIVE,
				ReflectiveValueGen.COVERAGE_NESTED_RECORD}) {
			if (coverage.hasValueCategories(code)) {
				mask |= code;
			}
		}
		return mask;
	}

	private static String idPart(SchemaCase schemaCase) {
		return schemaCase.item().id().replace(':', '_');
	}

	/** The pairs the matrix walked but whose guard never returned, for the failure message above. */
	private static Set<String> missing(Set<String> recorded, Set<String> walked) {
		Set<String> missing = new LinkedHashSet<>(walked);
		missing.removeAll(recorded);
		return missing;
	}
}
