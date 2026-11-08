package it.cavallium.datagen.plugin.fuzz.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.plugin.ClassConfiguration;
import it.cavallium.datagen.plugin.DataModel;
import it.cavallium.datagen.plugin.NewTypeConfiguration;
import it.cavallium.datagen.plugin.ReadTransformConfiguration;
import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;
import it.cavallium.datagen.plugin.TestSimpleIntToLongUpgrader;
import it.cavallium.datagen.plugin.TestSimpleLongInitializer;
import it.cavallium.datagen.plugin.TortureTransforms;
import it.cavallium.datagen.plugin.VersionConfiguration;
import it.cavallium.datagen.plugin.VersionTransformation;
import it.cavallium.datagen.plugin.fuzz.FuzzInstanceLocations;
import it.cavallium.datagen.plugin.fuzz.support.SchemaGenProfile.Feature;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Pins the contract of {@link SchemaGen}: a generated schema is always valid, always the same for the same seed, and
 * always covers every {@link Feature}.
 *
 * <p>These are the generator's own acceptance tests; the fuzz tests that consume {@link SchemaGen} (emission,
 * compilation, round-trip) live next to them and reuse this generator.</p>
 */
class SchemaGenTest {

	/**
	 * Every generated schema builds. Case count follows the shared tier knob, and the profile list walks the whole
	 * design space: the two extremes, every tier, and one profile per forced feature.
	 */
	@Test
	void validSchemasAlwaysBuild() {
		int cases = SchemaGenProfile.cases("schema-gen.valid", 40, 500, 2000, 6000);
		var profiles = profileSpread();
		var coverage = new SchemaGen.Coverage();
		int index = 0;
		for (int round = 0; round < cases; round++) {
			for (SchemaGenProfile profile : profiles) {
				// A per-case seed: the same seed with a different profile is a different schema, which is what
				// makes the spread cheap to explore without repeating shapes.
				var config = SchemaGen.generate(new Random(0x5EEDL * (round + 1) + index++), profile, coverage);
				int caseIndex = index;
				assertDoesNotThrow(() -> config.buildDataModel(false), () -> "Case " + caseIndex + " of profile "
						+ profile + " was rejected:\n" + SchemaYamlEmitter.emit(config));
			}
		}
		Set<Feature> missing = coverage.missing();
		assertTrue(missing.isEmpty(), () -> "Profiles " + profiles.size() + " x " + cases
				+ " cases did not cover every feature: " + coverage.summary());
	}

	/** The same seed and profile produce a byte-identical schema, and a different seed usually does not. */
	@Test
	void determinism() {
		for (SchemaGenProfile profile : profileSpread()) {
			long seed = 1234567890L;
			String first = SchemaYamlEmitter.emit(SchemaGen.generate(new Random(seed), profile));
			String second = SchemaYamlEmitter.emit(SchemaGen.generate(new Random(seed), profile));
			assertEquals(first, second, "Same seed and profile produced different YAML for " + profile);
			String otherSeed = SchemaYamlEmitter.emit(SchemaGen.generate(new Random(seed + 1), profile));
			assertNotEquals(first, otherSeed, () -> "Seed 1234567890 and 1234567891 produced the same YAML for "
					+ profile + " (a fixed shape means the seed is not driving the generation)");
		}
	}

	/** Repeated runs in the same JVM are identical, i.e. generation touches no shared or hash-ordered state. */
	@Test
	void repeatedGenerationIsStableAcrossCalls() {
		var profile = SchemaGenProfile.maximal();
		List<String> emitted = new ArrayList<>();
		for (int attempt = 0; attempt < 3; attempt++) {
			emitted.add(SchemaYamlEmitter.emit(SchemaGen.generate(new Random(987654321L), profile)));
		}
		assertEquals(1, new LinkedHashSet<>(emitted).size(), "Generation is not reproducible within one JVM");
	}

	/**
	 * Two invariants the model enforces by throwing away or rejecting, and that the generator must respect up
	 * front: a nullable array is not expressible, and a type name may never be a native name.
	 */
	@Test
	void noNullableArraysAndNoNativeNameClashes() {
		var profiles = profileSpread();
		int checked = 0;
		for (int seed = 0; seed < 60; seed++) {
			for (SchemaGenProfile profile : profiles) {
				var config = SchemaGen.generate(new Random(seed * 1_000_003L + profile.hashCode()), profile);
				checked++;
				for (var entry : nullableTypeSites(config)) {
					String type = entry.getValue();
					assertTrue(!(type.startsWith("-") && type.endsWith("[]")),
							() -> "Nullable array at " + entry.getKey() + ": " + type);
					assertTrue(!type.isEmpty() && !type.equals("-") && !type.equals("[]"),
							() -> "Empty or half-typed field type at " + entry.getKey() + ": \"" + type + "\"");
				}
				for (String name : declaredTypeNames(config)) {
					assertTrue(!SchemaGen.NATIVE_TYPES.contains(name),
							() -> "Record " + name + " collides with a native type name");
					assertTrue(name.startsWith("G"), () -> "Unexpected type name: " + name);
				}
			}
		}
		assertTrue(checked > 100, "Expected the spread to check many schemas, checked " + checked);
	}

	/**
	 * Every {@code upgradeData} must read a field the PREVIOUS version really declares as {@code int}: the shared
	 * test upgraders are {@code int -> long}, and the generated upgrade casts the raw old value to the previous
	 * shape's type. A schema whose previous shape says {@code String} compiles fine and then throws
	 * {@code ClassCastException} at runtime, which is exactly the harness defect this test now pins.
	 */
	@Test
	void upgradesAlwaysStartFromAnIntField() {
		int checked = 0;
		for (int seed = 0; seed < 60; seed++) {
			for (SchemaGenProfile profile : profileSpread()) {
				var config = SchemaGen.generate(new Random(seed * 1_000_003L + profile.hashCode()), profile);
				checked += assertUpgradeSourcesAreInt(config);
			}
		}
		assertTrue(checked > 20, "Expected to check many upgrades, checked " + checked);
	}

	/**
	 * A {@code readTransform} is the fused read's fast path, while the codec-read +
	 * {@code upgradeDataToLatestVersion} oracle path calls the transformation's plain initializer/upgrader
	 * ({@code GenUpgraderBaseX} resolves that location even when a readTransform exists). The pair therefore has to
	 * compute the SAME value: {@code constant(7)} on a field whose initializer returns {@code 123} compiles, builds
	 * and then makes the two read paths disagree, which is the defect
	 * {@code GeneratedOracles.upgradeEquivalence} reports. This test pins the sanctioned pairs as a table and checks
	 * the two values against each other.
	 */
	@Test
	void readTransformsAgreeWithTheirPlainLocation() {
		int checked = 0;
		var locations = new java.util.HashSet<String>();
		for (int seed = 0; seed < 60; seed++) {
			for (SchemaGenProfile profile : profileSpread()) {
				checked += assertReadTransformPairsAreValueConserving(
						SchemaGen.generate(new Random(seed * 1_000_003L + profile.hashCode()), profile), locations);
			}
			checked += assertReadTransformPairsAreValueConserving(
					SchemaGen.generate(new Random(seed * 100_003L),
							SchemaGenProfile.productionLike(new Random(seed * 7919L), 60)), locations);
		}
		assertTrue(checked > 20, "Expected to check many readTransform pairs, checked " + checked);
		// Both sanctioned locations must actually appear, or the table would silently stop covering the
		// upgrade-side fused read — the exact pair the value-consistency oracle exists for.
		assertTrue(locations.contains(SchemaGen.LONG_INITIALIZER) && locations.contains(SchemaGen.INT_TO_LONG_UPGRADER),
				"The spread paired readTransforms only with " + locations);
	}

	/**
	 * {@code commonData}/{@code commonGetters} of a union interface are abstract methods of that interface, so every
	 * alternative must carry those fields as real data — in production, {@code ChatEntityId}'s {@code tableId} is a
	 * field of {@code SuperCollectionId}, {@code BasicGroupId}, {@code UserId} and {@code DeprecatedId}. Replayed
	 * against every version, this also pins that no transformation ever moves or removes such a field and that a
	 * {@code newSubtype} never joins a union whose interface declares them.
	 */
	@Test
	void interfaceCommonFieldsAreDataOfEveryAlternative() {
		int checked = 0;
		for (int seed = 0; seed < 60; seed++) {
			for (SchemaGenProfile profile : profileSpread()) {
				checked += assertInterfaceCommonFields(
						SchemaGen.generate(new Random(seed * 1_000_003L + profile.hashCode()), profile));
			}
			checked += assertInterfaceCommonFields(SchemaGen.generate(new Random(seed * 100_003L),
					SchemaGenProfile.productionLike(new Random(seed * 7919L), 60)));
		}
		assertTrue(checked > 20, "Expected to check many common-field alternatives, checked " + checked);
	}

	/** The pinned native-type list is the model's list; if {@code DataModel} changes, this test says so. */
	@Test
	void nativeNamesMatchTheModel() throws Exception {
		Field field = DataModel.class.getDeclaredField("NATIVE_TYPES");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<String> modelNativeTypes = (List<String>) field.get(null);
		assertEquals(new TreeSet<>(modelNativeTypes), new TreeSet<>(SchemaGen.NATIVE_TYPES),
				"SchemaGen.NATIVE_TYPES no longer matches DataModel.NATIVE_TYPES");
	}

	/** Forced features really are present: {@code withForced} is a guarantee, not a preference. */
	@Test
	void forcedFeaturesAreAlwaysEmitted() {
		for (Feature feature : Feature.values()) {
			var profile = SchemaGenProfile.forTier(SchemaGenProfile.Tier.DEFAULT).withForced(feature);
			Set<Feature> missingOnSomeSeed = new TreeSet<>(Set.of());
			for (int seed = 0; seed < 25; seed++) {
				var coverage = new SchemaGen.Coverage();
				var config = SchemaGen.generate(new Random(seed * 7919L), profile, coverage);
				assertDoesNotThrow(() -> config.buildDataModel(false),
						() -> "Forced " + feature + " produced an invalid schema");
				if (!coverage.seen().contains(feature)) {
					missingOnSomeSeed.add(feature);
				}
			}
			assertTrue(missingOnSomeSeed.isEmpty(), () -> "Feature " + feature
					+ " was forced but absent on at least one of 25 seeds: " + missingOnSomeSeed);
		}
	}

	/**
	 * The field transformations must be replayable against the evolving shape: a move reads a field the previous
	 * shape declares and writes a name it does not yet have, a remove reads an existing field, a new field never
	 * shadows an existing one, an explicit index (the {@code INDEX_INSERTION} feature) is within bounds for the
	 * shape at that point, and the {@code stringRepresenter} field — which every version must keep — is never
	 * moved or removed. Without these invariants the model rejects the schema (or the generated upgrade code
	 * misbehaves), so a violation here is a generator defect, not a flaky input.
	 */
	@Test
	void fieldOrderRenamesAndRemovesStayConsistent() {
		int checked = 0;
		for (int seed = 0; seed < 60; seed++) {
			for (SchemaGenProfile profile : profileSpread()) {
				checked += assertFieldTransformationsAreConsistent(
						SchemaGen.generate(new Random(seed * 1_000_003L + profile.hashCode()), profile));
			}
			// the production-like profile is denser in transformations, so it gets its own replay budget
			var productionProfile = SchemaGenProfile.productionLike(new Random(seed * 7919L), 60);
			checked += assertFieldTransformationsAreConsistent(
					SchemaGen.generate(new Random(seed * 100_003L), productionProfile));
		}
		assertTrue(checked > 100, "Expected to check many field transformations, checked " + checked);
	}

	/**
	 * {@link SchemaGenProfile#productionLike} must really be production-shaped, not just bigger: a long version
	 * chain, several transformations in every later version, all six transformation kinds, unions, and wide
	 * records — and the result must still build under both strictness modes.
	 */
	@Test
	void productionLikeSchemasAreDenseAndAlwaysValid() {
		Set<String> allKinds = Set.of("moveData", "removeData", "newData", "upgradeData", "newType", "newSubtype");
		for (int seed = 0; seed < 4; seed++) {
			final int s = seed;
			var profile = SchemaGenProfile.productionLike(new Random(seed * 7919L), 60);
			var config = SchemaGen.generate(new Random(seed * 1_000_003L), profile);
			assertDoesNotThrow(() -> config.buildDataModel(false),
					() -> "productionLike seed " + s + " was rejected:\n" + SchemaYamlEmitter.emit(config));
			assertDoesNotThrow(() -> config.buildDataModel(true),
					() -> "productionLike seed " + s + " was rejected in strict mode");
			Set<String> kinds = new TreeSet<>();
			int laterVersions = 0;
			int widestRecord = 0;
			if (config.baseTypesData != null) {
				for (var type : config.baseTypesData.values()) {
					widestRecord = Math.max(widestRecord, type.data.size());
				}
			}
			for (var versionEntry : config.versions.entrySet()) {
				VersionConfiguration version = versionEntry.getValue();
				if (version == null || version.transformations == null) {
					continue;
				}
				if (version.previousVersion != null) {
					laterVersions++;
					assertTrue(version.transformations.size() >= profile.minTransformationsPerVersion(),
							"productionLike seed " + seed + " version " + versionEntry.getKey() + " has only "
									+ version.transformations.size() + " transformations");
				}
				for (VersionTransformation transformation : version.transformations) {
					if (transformation == null) {
						continue;
					}
					if (transformation.moveData != null) {
						kinds.add("moveData");
					}
					if (transformation.removeData != null) {
						kinds.add("removeData");
					}
					if (transformation.newData != null) {
						kinds.add("newData");
					}
					if (transformation.upgradeData != null) {
						kinds.add("upgradeData");
					}
					if (transformation.newType != null) {
						kinds.add("newType");
						widestRecord = Math.max(widestRecord, transformation.newType.data.size());
					}
					if (transformation.newSubtype != null) {
						kinds.add("newSubtype");
					}
				}
			}
			assertEquals(allKinds, kinds, "productionLike seed " + seed + " did not emit every transformation kind");
			assertTrue(laterVersions >= profile.minVersions() - 1,
					"productionLike seed " + seed + " built only " + laterVersions + " later versions");
			assertTrue(config.superTypesData != null && config.superTypesData.size() >= profile.minUnions(),
					"productionLike seed " + seed + " built fewer unions than minUnions=" + profile.minUnions());
			assertTrue(widestRecord >= 10,
					"productionLike seed " + seed + " never exceeded " + widestRecord + " fields per record");
		}
	}

	// ------------------------------------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------------------------------------

	/**
	 * Replays the version chain the way {@code DataModel} does and asserts the target invariants of every
	 * {@code moveData}, {@code removeData} and {@code newData} against the shape as it stood at that version.
	 *
	 * @return the number of field transformations checked
	 */
	private static int assertFieldTransformationsAreConsistent(SourcesGeneratorConfiguration config) {
		var shapes = new java.util.LinkedHashMap<String, Map<String, String>>();
		var representers = new java.util.HashMap<String, String>();
		if (config.baseTypesData != null) {
			config.baseTypesData.forEach((name, type) -> {
				shapes.put(name, new java.util.LinkedHashMap<>(type.data));
				if (type.stringRepresenter != null) {
					representers.put(name, type.stringRepresenter);
				}
			});
		}
		int checked = 0;
		if (config.versions == null) {
			return checked;
		}
		for (var versionEntry : config.versions.entrySet()) {
			VersionConfiguration version = versionEntry.getValue();
			if (version == null || version.transformations == null) {
				continue;
			}
			for (VersionTransformation transformation : version.transformations) {
				if (transformation == null) {
					continue;
				}
				if (transformation.newType != null) {
					shapes.put(transformation.newType.name, new java.util.LinkedHashMap<>(transformation.newType.data));
					if (transformation.newType.stringRepresenter != null) {
						representers.put(transformation.newType.name, transformation.newType.stringRepresenter);
					}
				}
				var fields = transformation.newData != null ? shapes.get(transformation.newData.transformClass)
						: transformation.moveData != null ? shapes.get(transformation.moveData.transformClass)
								: transformation.removeData != null
										? shapes.get(transformation.removeData.transformClass)
										: transformation.upgradeData != null
												? shapes.get(transformation.upgradeData.transformClass)
												: null;
				if (fields == null) {
					continue;
				}
				if (transformation.newData != null) {
					var newData = transformation.newData;
					assertTrue(!fields.containsKey(newData.to), () -> "Version " + versionEntry.getKey() + " adds "
							+ newData.transformClass + "." + newData.to + " but the shape already has it:\n"
							+ SchemaYamlEmitter.emit(config));
					assertIndexInRange(newData.index, fields.size(), versionEntry.getKey(), newData.transformClass,
							config);
					fields.put(newData.to, newData.type);
					checked++;
				} else if (transformation.moveData != null) {
					var moveData = transformation.moveData;
					assertTrue(fields.containsKey(moveData.from), () -> "Version " + versionEntry.getKey()
							+ " moves " + moveData.transformClass + "." + moveData.from + " which the shape lacks:\n"
							+ SchemaYamlEmitter.emit(config));
					assertTrue(!fields.containsKey(moveData.to), () -> "Version " + versionEntry.getKey() + " renames "
							+ moveData.transformClass + "." + moveData.from + " to the occupied name " + moveData.to
							+ ":\n" + SchemaYamlEmitter.emit(config));
					assertTrue(!moveData.from.equals(representers.get(moveData.transformClass)),
							() -> "Version " + versionEntry.getKey() + " moves the stringRepresenter field of "
									+ moveData.transformClass + ":\n" + SchemaYamlEmitter.emit(config));
					// the index is applied after the field was conceptually removed
					assertIndexInRange(moveData.index, Math.max(0, fields.size() - 1), versionEntry.getKey(),
							moveData.transformClass, config);
					String movedType = fields.remove(moveData.from);
					fields.put(moveData.to, movedType);
					checked++;
				} else if (transformation.removeData != null) {
					var removeData = transformation.removeData;
					assertTrue(fields.containsKey(removeData.from), () -> "Version " + versionEntry.getKey()
							+ " removes " + removeData.transformClass + "." + removeData.from
							+ " which the shape lacks:\n" + SchemaYamlEmitter.emit(config));
					assertTrue(!removeData.from.equals(representers.get(removeData.transformClass)),
							() -> "Version " + versionEntry.getKey() + " removes the stringRepresenter field of "
									+ removeData.transformClass + ":\n" + SchemaYamlEmitter.emit(config));
					fields.remove(removeData.from);
					checked++;
				} else if (transformation.upgradeData != null) {
					// the int-source invariant is pinned by upgradesAlwaysStartFromAnIntField; here the replay only
					// has to keep the shape current
					fields.put(transformation.upgradeData.from, transformation.upgradeData.type);
				}
			}
		}
		return checked;
	}

	private static void assertIndexInRange(Integer index, int size, Object version, String transformClass,
			SourcesGeneratorConfiguration config) {
		if (index == null) {
			return;
		}
		assertTrue(index >= 0 && index <= size, () -> "Version " + version + " indexes into " + transformClass
				+ " at " + index + " which is out of bounds for a shape of " + size + " fields:\n"
				+ SchemaYamlEmitter.emit(config));
	}

	/**
	 * Replays the version chain the way {@code DataModel} does and asserts the invariant of every
	 * {@code upgradeData}: the field it reads exists in the previous version's shape and is declared {@code int}.
	 *
	 * @return the number of upgrades checked
	 */
	private static int assertUpgradeSourcesAreInt(SourcesGeneratorConfiguration config) {
		var shapes = new java.util.LinkedHashMap<String, Map<String, String>>();
		if (config.baseTypesData != null) {
			config.baseTypesData.forEach((name, type) -> shapes.put(name, new java.util.LinkedHashMap<>(type.data)));
		}
		int upgrades = 0;
		if (config.versions == null) {
			return upgrades;
		}
		for (var versionEntry : config.versions.entrySet()) {
			VersionConfiguration version = versionEntry.getValue();
			if (version == null || version.previousVersion == null || version.transformations == null) {
				continue;
			}
			for (VersionTransformation transformation : version.transformations) {
				if (transformation == null) {
					continue;
				}
				if (transformation.newType != null) {
					shapes.put(transformation.newType.name,
							new java.util.LinkedHashMap<>(transformation.newType.data));
				}
				var fields = transformation.newData != null ? shapes.get(transformation.newData.transformClass)
						: transformation.moveData != null ? shapes.get(transformation.moveData.transformClass)
								: transformation.removeData != null
										? shapes.get(transformation.removeData.transformClass)
										: transformation.upgradeData != null
												? shapes.get(transformation.upgradeData.transformClass)
												: null;
				if (fields == null) {
					continue;
				}
				if (transformation.newData != null) {
					fields.put(transformation.newData.to, transformation.newData.type);
				} else if (transformation.moveData != null) {
					String movedType = fields.remove(transformation.moveData.from);
					fields.put(transformation.moveData.to, movedType);
				} else if (transformation.removeData != null) {
					fields.remove(transformation.removeData.from);
				} else {
					var upgradeData = transformation.upgradeData;
					String previous = fields.get(upgradeData.from);
					assertEquals("int", previous, () -> "Version " + versionEntry.getKey() + " upgrades "
							+ upgradeData.transformClass + "." + upgradeData.from + " from " + previous
							+ ", but the test upgraders read an int:\n" + SchemaYamlEmitter.emit(config));
					fields.put(upgradeData.from, upgradeData.type);
					upgrades++;
				}
			}
		}
		return upgrades;
	}

	/**
	 * The (plain location → {@code readTransform} shape) pairs the generator may emit, as canonical shape keys.
	 * Every listed shape computes exactly what the location computes; an unlisted pairing fails the test.
	 */
	private static final Map<String, Set<String>> SANCTIONED_READ_TRANSFORM_PAIRS = Map.of(
			// TestSimpleLongInitializer.initialize(context) == 123L.
			SchemaGen.LONG_INITIALIZER, Set.of("constant(123)", "widen(constant(-877))"),
			// TestSimpleIntToLongUpgrader.upgrade(context, old) == old + 1000.
			SchemaGen.INT_TO_LONG_UPGRADER, Set.of("widen(identity(value))",
					"widenWithAnchor(identity(value), constant(-1000))"),
			// The instance-form locations are never paired with a readTransform — the generator's branches are
			// mutually exclusive, so a pairing here would be a new shape nothing has validated.
			SchemaGen.INSTANCE_INITIALIZER, Set.of(),
			SchemaGen.INSTANCE_UPGRADER, Set.of());

	/** The old value the upgrader-side pairs are evaluated at (any int would do; the shapes are linear). */
	private static final int SAMPLE_OLD_VALUE = 7;

	/**
	 * Asserts for every {@code newData}/{@code upgradeData} that carries both a {@code readTransform} and a plain
	 * location: the pairing is sanctioned, and the value the declarative transform computes equals the value the
	 * real initializer/upgrader computes.
	 *
	 * @param locations collects the plain locations that appeared with a readTransform
	 * @return the number of pairs checked
	 */
	private static int assertReadTransformPairsAreValueConserving(SourcesGeneratorConfiguration config,
			Set<String> locations) {
		int checked = 0;
		if (config.versions == null) {
			return checked;
		}
		for (var versionEntry : config.versions.entrySet()) {
			VersionConfiguration version = versionEntry.getValue();
			if (version == null || version.transformations == null) {
				continue;
			}
			for (VersionTransformation transformation : version.transformations) {
				if (transformation == null) {
					continue;
				}
				String location;
				ReadTransformConfiguration readTransform;
				String where;
				if (transformation.newData != null) {
					location = transformation.newData.initializer != null
							? transformation.newData.initializer
							: transformation.newData.initializerInstance;
					readTransform = transformation.newData.readTransform;
					where = "version " + versionEntry.getKey() + " newData " + transformation.newData.to;
				} else if (transformation.upgradeData != null) {
					location = transformation.upgradeData.upgrader != null
							? transformation.upgradeData.upgrader
							: transformation.upgradeData.upgraderInstance;
					readTransform = transformation.upgradeData.readTransform;
					where = "version " + versionEntry.getKey() + " upgradeData " + transformation.upgradeData.from;
				} else {
					continue;
				}
				if (readTransform == null) {
					continue;
				}
				assertNotNull(location, () -> where + " carries a readTransform but no plain location:\n"
						+ SchemaYamlEmitter.emit(config));
				locations.add(location);
				String shape = readTransformShape(readTransform);
				Set<String> sanctioned = SANCTIONED_READ_TRANSFORM_PAIRS.get(location);
				assertNotNull(sanctioned, () -> where + " pairs an unknown plain location " + location + ":\n"
						+ SchemaYamlEmitter.emit(config));
				assertTrue(sanctioned.contains(shape), () -> where + " pairs " + location + " with the unsanctioned "
						+ "readTransform shape " + shape + " (the fused read and the initializer/upgrader path would "
						+ "compute different values):\n" + SchemaYamlEmitter.emit(config));
				Long plainValue = plainLocationValue(location, SAMPLE_OLD_VALUE);
				Long fusedValue = readTransformValue(readTransform, SAMPLE_OLD_VALUE);
				assertNotNull(fusedValue, () -> where + " has a readTransform shape the test cannot evaluate: "
						+ shape);
				assertEquals(plainValue, fusedValue, () -> where + ": " + location + " computes " + plainValue
						+ " but its readTransform " + shape + " computes " + fusedValue + ":\n"
						+ SchemaYamlEmitter.emit(config));
				checked++;
			}
		}
		return checked;
	}

	/** Canonical, human-readable key of a readTransform shape: {@code method(arg, arg)} or {@code constant(v)}. */
	private static String readTransformShape(ReadTransformConfiguration transform) {
		if (transform.constant != null) {
			return "constant(" + transform.constant.value + ")";
		}
		if (transform.identity != null) {
			return "identity(" + transform.identity.source + ")";
		}
		if (transform.invokeStatic != null) {
			String method = transform.invokeStatic.method;
			String arguments = transform.invokeStatic.getArguments().stream()
					.map(SchemaGenTest::readTransformShape)
					.reduce((left, right) -> left + ", " + right)
					.orElse("");
			return method.substring(method.lastIndexOf('.') + 1) + "(" + arguments + ")";
		}
		return transform.kind().name().toLowerCase(java.util.Locale.ROOT);
	}

	/** Value the declarative transform computes for {@code oldValue}, or {@code null} when it cannot be evaluated. */
	private static Long readTransformValue(ReadTransformConfiguration transform, int oldValue) {
		if (transform.constant != null) {
			return ((Number) transform.constant.value).longValue();
		}
		if (transform.identity != null) {
			// Only `value` (the old field) is emitted here; it widens int -> long unchanged.
			return "value".equals(transform.identity.source) ? (long) oldValue : null;
		}
		if (transform.invokeStatic != null) {
			var arguments = transform.invokeStatic.getArguments();
			String method = transform.invokeStatic.method;
			if (!method.equals(SchemaGen.TORTURE_TRANSFORMS + ".widen")
					&& !method.equals(SchemaGen.TORTURE_TRANSFORMS + ".widenWithAnchor")) {
				return null;
			}
			Long first = readTransformValue(arguments.get(0), oldValue);
			if (first == null) {
				return null;
			}
			if (method.endsWith(".widen")) {
				return arguments.size() == 1 ? TortureTransforms.widen(Math.toIntExact(first)) : null;
			}
			Long anchor = arguments.size() == 2 ? readTransformValue(arguments.get(1), oldValue) : null;
			return anchor == null ? null : TortureTransforms.widenWithAnchor(Math.toIntExact(first), anchor);
		}
		return null;
	}

	/** Value the real initializer/upgrader at {@code location} computes; unknown locations fail the test. */
	private static Long plainLocationValue(String location, int oldValue) {
		if (location.equals(SchemaGen.LONG_INITIALIZER)) {
			return new TestSimpleLongInitializer().initialize(DataContextNone.INSTANCE);
		}
		if (location.equals(SchemaGen.INT_TO_LONG_UPGRADER)) {
			return new TestSimpleIntToLongUpgrader().upgrade(DataContextNone.INSTANCE, oldValue);
		}
		if (location.equals(SchemaGen.INSTANCE_INITIALIZER)) {
			return FuzzInstanceLocations.LONG.initialize(DataContextNone.INSTANCE);
		}
		if (location.equals(SchemaGen.INSTANCE_UPGRADER)) {
			return FuzzInstanceLocations.INT_TO_LONG.upgrade(DataContextNone.INSTANCE, oldValue);
		}
		throw new AssertionError("Unknown plain location " + location);
	}

	/**
	 * Replays the version chain and asserts, for every version, that each alternative of a union whose interface
	 * declares {@code commonData} or {@code commonGetters} (directly or through {@code extendInterfaces}) really
	 * declares those fields.
	 *
	 * @return the number of union-alternative pairs checked
	 */
	private static int assertInterfaceCommonFields(SourcesGeneratorConfiguration config) {
		if (config.interfacesData == null || config.interfacesData.isEmpty() || config.versions == null) {
			return 0;
		}
		var shapes = new java.util.LinkedHashMap<String, Map<String, String>>();
		if (config.baseTypesData != null) {
			config.baseTypesData.forEach((name, type) -> shapes.put(name, new java.util.LinkedHashMap<>(type.data)));
		}
		var alternatives = new java.util.LinkedHashMap<String, List<String>>();
		if (config.superTypesData != null) {
			config.superTypesData.forEach((name, names) -> alternatives.put(name, new ArrayList<>(names)));
		}
		int checked = 0;
		for (var versionEntry : config.versions.entrySet()) {
			VersionConfiguration version = versionEntry.getValue();
			if (version != null && version.transformations != null) {
				for (VersionTransformation transformation : version.transformations) {
					if (transformation == null) {
						continue;
					}
					if (transformation.newType != null) {
						shapes.put(transformation.newType.name,
								new java.util.LinkedHashMap<>(transformation.newType.data));
					}
					if (transformation.newData != null) {
						shapes.get(transformation.newData.transformClass).put(transformation.newData.to,
								transformation.newData.type);
					}
					if (transformation.moveData != null) {
						var fields = shapes.get(transformation.moveData.transformClass);
						fields.put(transformation.moveData.to, fields.remove(transformation.moveData.from));
					}
					if (transformation.removeData != null) {
						shapes.get(transformation.removeData.transformClass).remove(transformation.removeData.from);
					}
					if (transformation.upgradeData != null) {
						shapes.get(transformation.upgradeData.transformClass).put(transformation.upgradeData.from,
								transformation.upgradeData.type);
					}
					if (transformation.newSubtype != null) {
						alternatives.computeIfAbsent(transformation.newSubtype.superType,
								key -> new ArrayList<>()).add(transformation.newSubtype.subtype);
					}
				}
			}
			for (var entry : config.interfacesData.entrySet()) {
				for (String field : commonFieldsOf(entry.getKey(), config.interfacesData)) {
					for (String alternative : alternatives.getOrDefault(entry.getKey(), List.of())) {
						Map<String, String> fields = shapes.get(alternative);
						assertTrue(fields != null && fields.containsKey(field),
								() -> "Version " + versionEntry.getKey() + ": " + entry.getKey() + " declares " + field
										+ " as common interface data, but " + alternative + " does not have it:\n"
										+ SchemaYamlEmitter.emit(config));
						checked++;
					}
				}
			}
		}
		return checked;
	}

	/** The common fields a union demands: its own {@code commonData}/{@code commonGetters} plus inherited ones. */
	private static Set<String> commonFieldsOf(String union,
			Map<String, it.cavallium.datagen.plugin.InterfaceDataConfiguration> interfaces) {
		Set<String> fields = new LinkedHashSet<>();
		Set<String> visited = new LinkedHashSet<>();
		var pending = new java.util.ArrayDeque<String>();
		pending.add(union);
		while (!pending.isEmpty()) {
			String current = pending.poll();
			if (!visited.add(current)) {
				continue;
			}
			var configured = interfaces.get(current);
			if (configured == null) {
				continue;
			}
			fields.addAll(configured.commonData.keySet());
			fields.addAll(configured.commonGetters.keySet());
			pending.addAll(configured.extendInterfaces);
		}
		return fields;
	}

	/** The two extremes, every tier, and one profile per feature. */
	private static List<SchemaGenProfile> profileSpread() {
		var profiles = new ArrayList<SchemaGenProfile>();
		profiles.add(SchemaGenProfile.minimal());
		profiles.add(SchemaGenProfile.maximal());
		for (SchemaGenProfile.Tier tier : SchemaGenProfile.Tier.values()) {
			profiles.add(SchemaGenProfile.forTier(tier));
		}
		for (Feature feature : Feature.values()) {
			profiles.add(SchemaGenProfile.forTier(SchemaGenProfile.Tier.DEFAULT).withForced(feature));
		}
		return profiles;
	}

	/** Every place a field type appears, keyed by a human-readable location for failure messages. */
	private static List<java.util.Map.Entry<String, String>> nullableTypeSites(SourcesGeneratorConfiguration config) {
		var sites = new ArrayList<java.util.Map.Entry<String, String>>();
		if (config.baseTypesData != null) {
			config.baseTypesData.forEach((typeName, type) -> sites.addAll(sitesOf(type, "base " + typeName)));
		}
		if (config.versions == null) {
			return sites;
		}
		for (var entry : config.versions.entrySet()) {
			VersionConfiguration version = entry.getValue();
			if (version == null || version.transformations == null) {
				continue;
			}
			for (int index = 0; index < version.transformations.size(); index++) {
				VersionTransformation transformation = version.transformations.get(index);
				if (transformation == null) {
					continue;
				}
				String where = "version " + entry.getKey() + " transformation " + index;
				if (transformation.newData != null) {
					sites.add(Map.entry(where + ".newData." + transformation.newData.to, transformation.newData.type));
				}
				if (transformation.upgradeData != null) {
					sites.add(Map.entry(where + ".upgradeData." + transformation.upgradeData.from,
							transformation.upgradeData.type));
				}
				NewTypeConfiguration newType = transformation.newType;
				if (newType != null) {
					sites.addAll(sitesOf(newType, where + ".newType " + newType.name));
				}
			}
		}
		return sites;
	}

	private static List<Map.Entry<String, String>> sitesOf(ClassConfiguration type, String where) {
		return sitesOf(type == null ? null : type.data, where);
	}

	private static List<Map.Entry<String, String>> sitesOf(NewTypeConfiguration type, String where) {
		return sitesOf(type == null ? null : type.data, where);
	}

	private static List<Map.Entry<String, String>> sitesOf(Map<String, String> data, String where) {
		var sites = new ArrayList<Map.Entry<String, String>>();
		if (data == null) {
			return sites;
		}
		data.forEach((field, type) -> sites.add(Map.entry(where + "." + field, type)));
		return sites;
	}

	/** Every name this schema declares: base records, union names, custom types and types introduced by versions. */
	private static Set<String> declaredTypeNames(SourcesGeneratorConfiguration config) {
		Set<String> names = new TreeSet<>();
		if (config.baseTypesData != null) {
			names.addAll(config.baseTypesData.keySet());
		}
		if (config.superTypesData != null) {
			names.addAll(config.superTypesData.keySet());
		}
		if (config.customTypesData != null) {
			names.addAll(config.customTypesData.keySet());
		}
		if (config.projectionsData != null) {
			names.addAll(config.projectionsData.keySet());
		}
		if (config.versions != null) {
			for (VersionConfiguration version : config.versions.values()) {
				if (version == null || version.transformations == null) {
					continue;
				}
				for (VersionTransformation transformation : version.transformations) {
					if (transformation != null && transformation.newType != null) {
						names.add(transformation.newType.name);
					}
				}
			}
		}
		names.forEach(name -> assertNotNull(name, "Declared type name must not be null"));
		return names;
	}
}
