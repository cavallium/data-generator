package it.cavallium.datagen.plugin.fuzz.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.datagen.plugin.ClassConfiguration;
import it.cavallium.datagen.plugin.DataModel;
import it.cavallium.datagen.plugin.NewTypeConfiguration;
import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;
import it.cavallium.datagen.plugin.VersionConfiguration;
import it.cavallium.datagen.plugin.VersionTransformation;
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

	// ------------------------------------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------------------------------------

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
