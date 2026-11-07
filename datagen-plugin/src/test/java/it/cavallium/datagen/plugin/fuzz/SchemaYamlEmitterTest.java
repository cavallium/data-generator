package it.cavallium.datagen.plugin.fuzz;

import it.cavallium.datagen.plugin.ClassConfiguration;
import it.cavallium.datagen.plugin.CustomTypesConfiguration;
import it.cavallium.datagen.plugin.DetailsConfiguration;
import it.cavallium.datagen.plugin.InterfaceDataConfiguration;
import it.cavallium.datagen.plugin.MoveDataConfiguration;
import it.cavallium.datagen.plugin.NewDataConfiguration;
import it.cavallium.datagen.plugin.NewSubtypeConfiguration;
import it.cavallium.datagen.plugin.NewTypeConfiguration;
import it.cavallium.datagen.plugin.ProjectionConfiguration;
import it.cavallium.datagen.plugin.ReadTransformConfiguration;
import it.cavallium.datagen.plugin.RemoveDataConfiguration;
import it.cavallium.datagen.plugin.SourcesGenerator;
import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;
import it.cavallium.datagen.plugin.TransformationConfiguration;
import it.cavallium.datagen.plugin.UpgradeDataConfiguration;
import it.cavallium.datagen.plugin.VersionConfiguration;
import it.cavallium.datagen.plugin.VersionTransformation;
import it.cavallium.datagen.plugin.fuzz.support.SchemaCanonicalizer;
import it.cavallium.datagen.plugin.fuzz.support.SchemaYamlEmitter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-Jupiter contract tests for the deterministic schema YAML emitter (fuzz package 2a).
 */
class SchemaYamlEmitterTest {

	// ------------------------------------------------------------------------------------------------
	// 1. round-trip: canonical(load(emit(cfg))) == canonical(cfg)
	// ------------------------------------------------------------------------------------------------

	@Test
	void roundTripsEverySchema() {
		for (SourcesGeneratorConfiguration schema : allSchemas()) {
			String yaml = SchemaYamlEmitter.emit(schema);
			SourcesGeneratorConfiguration reloaded = SchemaYamlEmitter.load(yaml);
			assertEquals(SchemaCanonicalizer.canonical(schema),
					SchemaCanonicalizer.canonical(reloaded),
					"canonical drift for schema:\n" + yaml);
		}
	}

	@Test
	void doubleEmissionIsByteIdentical() {
		for (SourcesGeneratorConfiguration schema : allSchemas()) {
			assertEquals(SchemaYamlEmitter.emit(schema), SchemaYamlEmitter.emit(schema));
		}
	}

	// ------------------------------------------------------------------------------------------------
	// 2. ordering: incidental map order must not change bytes, `data` order is semantic
	// ------------------------------------------------------------------------------------------------

	@Test
	void hashAndLinkedHashMapBackedMapsEmitIdenticalBytes() {
		SourcesGeneratorConfiguration linked = unionWithInterfaceGetters();
		SourcesGeneratorConfiguration hashed = unionWithInterfaceGetters();

		// Re-insert every non-`data` map in reverse order, using HashMap instead of LinkedHashMap.
		hashed.baseTypesData = reversedHashMap(linked.baseTypesData);
		hashed.superTypesData = reversedHashMap(linked.superTypesData);
		hashed.interfacesData = reversedHashMap(linked.interfacesData);
		hashed.versions = reversedHashMap(linked.versions);

		assertEquals(SchemaYamlEmitter.emit(linked), SchemaYamlEmitter.emit(hashed));
		assertEquals(SchemaCanonicalizer.canonical(linked), SchemaCanonicalizer.canonical(hashed));
	}

	@Test
	void dataFieldOrderIsSemanticAndPreserved() {
		SourcesGeneratorConfiguration first = record("Alpha", "alphaId", "int", "alphaName", "String");
		SourcesGeneratorConfiguration second = record("Alpha", "alphaName", "String", "alphaId", "int");

		String firstYaml = SchemaYamlEmitter.emit(first);
		String secondYaml = SchemaYamlEmitter.emit(second);
		assertFalse(firstYaml.equals(secondYaml), "declared data order must be visible in the YAML");
		assertTrue(firstYaml.indexOf("alphaId") < firstYaml.indexOf("alphaName"), firstYaml);
		assertTrue(secondYaml.indexOf("alphaName") < secondYaml.indexOf("alphaId"), secondYaml);

		assertEquals(List.of("alphaId", "alphaName"),
				new ArrayList<>(SchemaYamlEmitter.load(firstYaml).baseTypesData.get("Alpha").data.keySet()));
		assertEquals(List.of("alphaName", "alphaId"),
				new ArrayList<>(SchemaYamlEmitter.load(secondYaml).baseTypesData.get("Alpha").data.keySet()));

		assertFalse(SchemaCanonicalizer.canonical(first).equals(SchemaCanonicalizer.canonical(second)));
	}

	@Test
	void projectionFieldOrderIsPreserved() {
		SourcesGeneratorConfiguration schema = projectionEntry();
		String yaml = SchemaYamlEmitter.emit(schema);
		LinkedHashMap<String, String> fields = SchemaYamlEmitter.load(yaml).projectionsData.get("AlphaP").fields;
		assertEquals(List.of("fullName", "nameFirst"), new ArrayList<>(fields.keySet()), yaml);
	}

	// ------------------------------------------------------------------------------------------------
	// 3. null / empty maps and lists are omitted, and still round-trip
	// ------------------------------------------------------------------------------------------------

	@Test
	void emptyAndNullKeysAreOmitted() {
		SourcesGeneratorConfiguration schema = emptyCollectionsAndNulls();
		String yaml = SchemaYamlEmitter.emit(schema);
		for (String key : List.of("interfacesData", "superTypesData", "customTypesData", "projectionsData",
				"details", "transformations", "changelog", "stringRepresenter")) {
			assertFalse(yaml.contains(key + ":"), key + " must be omitted:\n" + yaml);
		}
		assertFalse(yaml.contains("null"), "no key may be emitted as null:\n" + yaml);
		assertEquals(SchemaCanonicalizer.canonical(schema), SchemaCanonicalizer.canonical(SchemaYamlEmitter.load(yaml)));
		// buildDataModel coerces the omitted top-level maps to empty ones, exactly like production
		assertNotNull(SchemaYamlEmitter.load(yaml).buildDataModel(false));
	}

	@Test
	void presentButEmptyDataIsKeptBecauseProductionReadsIt() {
		// A declared, empty `data` map is NOT the same as an absent key in production: ParsedClass keeps
		// `data` null when the key is missing (ParsedClass.java:32) and DataModel.java:876 dereferences it.
		String declared = SchemaYamlEmitter.emit(emptyCollectionsAndNulls());
		assertTrue(declared.contains("data: {}"), declared);
		ClassConfiguration reloaded = SchemaYamlEmitter.load(declared).baseTypesData.get("Empty");
		assertNotNull(reloaded.data, "an empty data map must survive as an empty map");
		assertTrue(reloaded.data.isEmpty());

		String absent = SchemaYamlEmitter.emit(withNullDataSchema());
		assertFalse(absent.contains("data:"), absent);
		org.junit.jupiter.api.Assertions.assertSame(null,
				SchemaYamlEmitter.load(absent).baseTypesData.get("Empty").data);
		assertFalse(declared.equals(absent));
	}

	private static SourcesGeneratorConfiguration withNullDataSchema() {
		SourcesGeneratorConfiguration schema = emptyCollectionsAndNulls();
		schema.baseTypesData.get("Empty").data = null;
		return schema;
	}

	@Test
	void nullVersionEntrySurvivesAsEmptyNode() {
		SourcesGeneratorConfiguration schema = minimal();
		String yaml = SchemaYamlEmitter.emit(schema);
		assertTrue(yaml.contains("v1: {}"), yaml);
		SourcesGeneratorConfiguration loaded = SchemaYamlEmitter.load(yaml);
		assertTrue(loaded.versions.containsKey("v1"), yaml);
		assertEquals(List.of("v1"), new ArrayList<>(loaded.versions.keySet()));
	}

	// ------------------------------------------------------------------------------------------------
	// 4. nested single-op transformation shape
	// ------------------------------------------------------------------------------------------------

	@Test
	void transformationsUseTheNestedSingleOpShape() {
		String yaml = SchemaYamlEmitter.emit(threeVersionChain());
		assertTrue(yaml.contains("- moveData: {transformClass: Alpha, from: one, to: two}"), yaml);
		assertTrue(yaml.contains("- removeData: {transformClass: Alpha, from: dropped}"), yaml);
		assertTrue(yaml.contains("constant: {value: 7}"), yaml);
		assertTrue(yaml.contains("identity: {source: value}"), yaml);
		assertTrue(yaml.contains("mapArray:"), yaml);
		assertTrue(yaml.contains("- newSubtype: {superType: Choice, subtype: Beta}"), yaml);
		for (String line : yaml.split("\n")) {
			String trimmed = line.strip();
			if (!trimmed.startsWith("- ") || trimmed.startsWith("- {")) {
				continue;
			}
			String body = trimmed.substring(2);
			long operations = java.util.stream.Stream
					.of("moveData", "removeData", "newData", "upgradeData", "newType", "newSubtype")
					.filter(op -> body.startsWith(op + ":"))
					.count();
			assertEquals(1, operations, "expected exactly one op key in " + trimmed);
		}
	}

	// ------------------------------------------------------------------------------------------------
	// 5. YAML special characters
	// ------------------------------------------------------------------------------------------------

	@Test
	void arbitraryConstantValuesSurviveQuoting() {
		String tricky = "- leading : colon # hash \"quote\" 'sq'";
		SourcesGeneratorConfiguration schema = record("Quoted", "code", "int");
		VersionConfiguration v2 = new VersionConfiguration();
		v2.previousVersion = "v1";
		NewDataConfiguration newData = newData("Quoted", "odd", "int", tricky);
		v2.transformations = new ArrayList<>(List.of(transformation(newData)));
		schema.versions.put("v2", v2);
		schema.currentVersion = "v2";

		String yaml = SchemaYamlEmitter.emit(schema);
		SourcesGeneratorConfiguration loaded = SchemaYamlEmitter.load(yaml);
		Object value = loaded.versions.get("v2").transformations.get(0).newData.readTransform.constant.value;
		assertEquals(tricky, value, yaml);
		assertEquals(SchemaCanonicalizer.canonical(schema), SchemaCanonicalizer.canonical(loaded));
	}

	@Test
	void identifierLikeScalarsStayPlainButAmbiguousOnesAreQuoted() {
		ClassConfiguration configuration = new ClassConfiguration();
		configuration.stringRepresenter = "payload.name";
		configuration.data = new LinkedHashMap<>();
		configuration.data.put("yes", "int");
		configuration.data.put("nine", "9");
		configuration.data.put("dotted", "it.cavallium.datagen.plugin.TestFixedIntCodec");

		SourcesGeneratorConfiguration schema = new SourcesGeneratorConfiguration();
		schema.currentVersion = "v1";
		schema.baseTypesData = new LinkedHashMap<>(Map.of("Tricky", configuration));
		schema.versions = new LinkedHashMap<>(Map.of("v1", new VersionConfiguration()));

		String yaml = SchemaYamlEmitter.emit(schema);
		assertTrue(yaml.contains("payload.name"), yaml);
		assertFalse(yaml.contains("it.cavallium.datagen.plugin.TestFixedIntCodec\""), yaml);
		assertTrue(yaml.contains("'yes'"), yaml);
		assertTrue(yaml.contains("'9'"), yaml);
		SourcesGeneratorConfiguration loaded = SchemaYamlEmitter.load(yaml);
		assertEquals(List.of("yes", "nine", "dotted"),
				new ArrayList<>(loaded.baseTypesData.get("Tricky").data.keySet()), yaml);
		assertEquals("9", loaded.baseTypesData.get("Tricky").data.get("nine"), yaml);
	}

	// ------------------------------------------------------------------------------------------------
	// 6. the real fixture: strongest evidence
	// ------------------------------------------------------------------------------------------------

	@Test
	void adversarialFixtureRoundTripsAndBuilds() {
		String fixture = resource("/adversarial-evolution.yaml");
		SourcesGeneratorConfiguration loaded = SchemaYamlEmitter.load(fixture);
		assertNotNull(loaded);
		assertNotNull(loaded.buildDataModel(false));

		String emitted = SchemaYamlEmitter.emit(loaded);
		SourcesGeneratorConfiguration reloaded = SchemaYamlEmitter.load(emitted);
		assertEquals(SchemaCanonicalizer.canonical(loaded), SchemaCanonicalizer.canonical(reloaded));
		assertNotNull(reloaded.buildDataModel(false));

		// The production loader sees the same wire contract before and after the round-trip.
		String beforeLedger = ledgerOf(fixture);
		String afterLedger = ledgerOf(emitted);
		assertEquals(beforeLedger, afterLedger);
	}

	// ------------------------------------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------------------------------------

	private static List<SourcesGeneratorConfiguration> allSchemas() {
		return List.of(
				minimal(),
				recordWithEveryScalarFieldType(),
				unionWithInterfaceGetters(),
				customTypeEntry(),
				projectionEntry(),
				threeVersionChain(),
				emptyCollectionsAndNulls());
	}

	private static String ledgerOf(String yaml) {
		try {
			return SourcesGenerator.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
					.wireSchemaLedger(false);
		} catch (IOException failure) {
			throw new UncheckedIOException(failure);
		}
	}

	private static <T> Map<String, T> reversedHashMap(Map<String, T> source) {
		List<String> keys = new ArrayList<>(source.keySet());
		java.util.Collections.reverse(keys);
		Map<String, T> out = new HashMap<>();
		for (String key : keys) {
			out.put(key, source.get(key));
		}
		return out;
	}

	// --- builders -----------------------------------------------------------------------------------

	private static SourcesGeneratorConfiguration minimal() {
		SourcesGeneratorConfiguration configuration = new SourcesGeneratorConfiguration();
		configuration.currentVersion = "v1";
		configuration.versions = new LinkedHashMap<>();
		configuration.versions.put("v1", null);
		return configuration;
	}

	private static SourcesGeneratorConfiguration record(String name, String... dataPairs) {
		ClassConfiguration classConfiguration = new ClassConfiguration();
		classConfiguration.data = new LinkedHashMap<>();
		for (int index = 0; index + 1 < dataPairs.length; index += 2) {
			classConfiguration.data.put(dataPairs[index], dataPairs[index + 1]);
		}
		SourcesGeneratorConfiguration configuration = new SourcesGeneratorConfiguration();
		configuration.currentVersion = "v1";
		configuration.baseTypesData = new LinkedHashMap<>();
		configuration.baseTypesData.put(name, classConfiguration);
		configuration.versions = new LinkedHashMap<>();
		configuration.versions.put("v1", new VersionConfiguration());
		return configuration;
	}

	private static SourcesGeneratorConfiguration recordWithEveryScalarFieldType() {
		ClassConfiguration classConfiguration = new ClassConfiguration();
		LinkedHashMap<String, String> data = new LinkedHashMap<>();
		data.put("a", "int");
		data.put("b", "-int");
		data.put("c", "String");
		data.put("d", "-String");
		data.put("e", "Int52");
		data.put("f", "-Int52");
		data.put("g", "long[]");
		data.put("h", "String[]");
		data.put("i", "boolean[]");
		classConfiguration.data = data;
		SourcesGeneratorConfiguration configuration = new SourcesGeneratorConfiguration();
		configuration.currentVersion = "v1";
		configuration.baseTypesData = new LinkedHashMap<>();
		configuration.baseTypesData.put("Scalars", classConfiguration);
		configuration.versions = new LinkedHashMap<>();
		configuration.versions.put("v1", new VersionConfiguration());
		return configuration;
	}

	private static SourcesGeneratorConfiguration unionWithInterfaceGetters() {
		SourcesGeneratorConfiguration configuration = new SourcesGeneratorConfiguration();
		configuration.currentVersion = "v1";

		InterfaceDataConfiguration iface = new InterfaceDataConfiguration();
		iface.extendInterfaces.add("java.io.Serializable");
		iface.commonGetters.put("count", "int");
		iface.commonData.put("flag", "boolean");

		ClassConfiguration alpha = classOf("count", "int", "flag", "boolean");
		ClassConfiguration beta = classOf("count", "int", "other", "String");
		ClassConfiguration gamma = classOf("count", "int");

		configuration.baseTypesData = new LinkedHashMap<>();
		configuration.baseTypesData.put("Alpha", alpha);
		configuration.baseTypesData.put("Beta", beta);
		configuration.baseTypesData.put("Gamma", gamma);
		configuration.superTypesData = new LinkedHashMap<>();
		configuration.superTypesData.put("Choice", List.of("Alpha", "Beta", "Gamma"));
		configuration.interfacesData = new LinkedHashMap<>();
		configuration.interfacesData.put("Choice", iface);
		configuration.versions = new LinkedHashMap<>();
		configuration.versions.put("v1", new VersionConfiguration());
		return configuration;
	}

	private static ClassConfiguration classOf(String... dataPairs) {
		ClassConfiguration configuration = new ClassConfiguration();
		configuration.data = new LinkedHashMap<>();
		for (int index = 0; index + 1 < dataPairs.length; index += 2) {
			configuration.data.put(dataPairs[index], dataPairs[index + 1]);
		}
		return configuration;
	}

	private static SourcesGeneratorConfiguration customTypeEntry() {
		CustomTypesConfiguration custom = new CustomTypesConfiguration();
		custom.setJavaClass("java.lang.Integer");
		custom.codec = "it.cavallium.datagen.plugin.TestFixedIntCodec";
		custom.fixedSize = 4;

		SourcesGeneratorConfiguration configuration = record("UsesCustom", "value", "FixedInt");
		configuration.customTypesData = new LinkedHashMap<>();
		configuration.customTypesData.put("FixedInt", custom);
		return configuration;
	}

	private static SourcesGeneratorConfiguration projectionEntry() {
		ProjectionConfiguration projection = new ProjectionConfiguration();
		projection.sourceType = "Alpha";
		projection.fields = new LinkedHashMap<>();
		projection.fields.put("fullName", "name.full");
		projection.fields.put("nameFirst", "name.first");
		projection.consumeToEnd = false;

		ClassConfiguration source = classOf("name", "String", "age", "int");

		SourcesGeneratorConfiguration configuration = new SourcesGeneratorConfiguration();
		configuration.currentVersion = "v1";
		configuration.baseTypesData = new LinkedHashMap<>();
		configuration.baseTypesData.put("Alpha", source);
		configuration.projectionsData = new LinkedHashMap<>();
		configuration.projectionsData.put("AlphaP", projection);
		configuration.versions = new LinkedHashMap<>();
		configuration.versions.put("v1", new VersionConfiguration());
		return configuration;
	}

	private static SourcesGeneratorConfiguration threeVersionChain() {
		SourcesGeneratorConfiguration configuration = record("Alpha", "one", "int", "dropped", "String");
		configuration.baseTypesData.get("Alpha").stringRepresenter = "one";
		configuration.superTypesData = new LinkedHashMap<>();
		configuration.superTypesData.put("Choice", List.of("Alpha"));
		configuration.versions = new LinkedHashMap<>();
		configuration.versions.put("v1", null);

		VersionConfiguration v2 = new VersionConfiguration();
		v2.previousVersion = "v1";
		v2.details = details("changelog: v2 adds a computed field");
		List<VersionTransformation> v2transformations = new ArrayList<>();
		v2transformations.add(transformation(move("Alpha", "one", "two", null)));
		v2transformations.add(transformation(move("Alpha", "two", "three", 1)));
		v2transformations.add(transformation(remove("Alpha", "dropped")));
		NewDataConfiguration newData = newData("Alpha", "seven", "int", 7);
		newData.initializer = "it.cavallium.datagen.plugin.TestSimpleLongInitializer";
		newData.contextParameters = List.of("ctxA", "ctxB");
		v2transformations.add(transformation(newData));
		UpgradeDataConfiguration upgrade = new UpgradeDataConfiguration();
		upgrade.transformClass = "Alpha";
		upgrade.from = "three";
		upgrade.type = "long[]";
		upgrade.upgrader = "it.cavallium.datagen.plugin.TestSimpleIntToLongUpgrader";
		upgrade.contextParameters = List.of("ctxA");
		ReadTransformConfiguration mapArray = new ReadTransformConfiguration();
		mapArray.mapArray = new ReadTransformConfiguration.MapArray();
		mapArray.mapArray.source = new ReadTransformConfiguration();
		mapArray.mapArray.source.identity = new ReadTransformConfiguration.Identity();
		mapArray.mapArray.source.identity.source = "value";
		mapArray.mapArray.transform = new ReadTransformConfiguration();
		mapArray.mapArray.transform.invokeStatic = new ReadTransformConfiguration.InvokeStatic();
		mapArray.mapArray.transform.invokeStatic.method = "it.cavallium.datagen.plugin.TortureTransforms.widen";
		upgrade.readTransform = mapArray;
		v2transformations.add(transformation(upgrade));
		v2.transformations = v2transformations;
		configuration.versions.put("v2", v2);

		VersionConfiguration v3 = new VersionConfiguration();
		v3.previousVersion = "v2";
		NewTypeConfiguration newType = new NewTypeConfiguration();
		newType.name = "Beta";
		newType.stringRepresenter = "label";
		newType.data = new LinkedHashMap<>();
		newType.data.put("label", "String");
		NewSubtypeConfiguration newSubtype = new NewSubtypeConfiguration();
		newSubtype.superType = "Choice";
		newSubtype.subtype = "Beta";
		v3.transformations = new ArrayList<>(List.of(transformation(newType), transformation(newSubtype)));
		v3.typeVersions = new LinkedHashMap<>();
		v3.typeVersions.put("Alpha", 2);
		v3.typeVersions.put("Beta", 3);
		v3.dependentTypes = new LinkedHashMap<>();
		v3.dependentTypes.put("Alpha", List.of("Beta"));
		configuration.versions.put("v3", v3);
		configuration.currentVersion = "v3";

		configuration.baseTypesData.put("Beta", classOf("label", "String"));
		return configuration;
	}

	private static SourcesGeneratorConfiguration emptyCollectionsAndNulls() {
		SourcesGeneratorConfiguration configuration = new SourcesGeneratorConfiguration();
		configuration.currentVersion = "v1";
		configuration.interfacesData = new LinkedHashMap<>();
		configuration.superTypesData = new LinkedHashMap<>();
		configuration.customTypesData = new LinkedHashMap<>();
		configuration.projectionsData = new LinkedHashMap<>();

		ClassConfiguration empty = new ClassConfiguration();
		empty.data = new LinkedHashMap<>();
		configuration.baseTypesData = new LinkedHashMap<>();
		configuration.baseTypesData.put("Empty", empty);

		VersionConfiguration version = new VersionConfiguration();
		version.details = new DetailsConfiguration();
		version.transformations = new ArrayList<>();
		configuration.versions = new LinkedHashMap<>();
		configuration.versions.put("v1", version);
		return configuration;
	}

	private static DetailsConfiguration details(String changelog) {
		DetailsConfiguration details = new DetailsConfiguration();
		details.changelog = changelog;
		return details;
	}

	private static MoveDataConfiguration move(String transformClass, String from, String to, Integer index) {
		MoveDataConfiguration move = new MoveDataConfiguration();
		move.transformClass = transformClass;
		move.from = from;
		move.to = to;
		move.index = index;
		return move;
	}

	private static RemoveDataConfiguration remove(String transformClass, String from) {
		RemoveDataConfiguration remove = new RemoveDataConfiguration();
		remove.transformClass = transformClass;
		remove.from = from;
		return remove;
	}

	private static NewDataConfiguration newData(String transformClass, String to, String type, Object constantValue) {
		NewDataConfiguration newData = new NewDataConfiguration();
		newData.transformClass = transformClass;
		newData.to = to;
		newData.type = type;
		ReadTransformConfiguration readTransform = new ReadTransformConfiguration();
		readTransform.constant = new ReadTransformConfiguration.Constant();
		readTransform.constant.value = constantValue;
		newData.readTransform = readTransform;
		return newData;
	}

	private static VersionTransformation transformation(TransformationConfiguration configuration) {
		VersionTransformation transformation = new VersionTransformation();
		if (configuration instanceof MoveDataConfiguration move) {
			transformation.moveData = move;
		} else if (configuration instanceof RemoveDataConfiguration remove) {
			transformation.removeData = remove;
		} else if (configuration instanceof NewDataConfiguration newData) {
			transformation.newData = newData;
		} else if (configuration instanceof UpgradeDataConfiguration upgrade) {
			transformation.upgradeData = upgrade;
		} else if (configuration instanceof NewTypeConfiguration newType) {
			transformation.newType = newType;
		} else if (configuration instanceof NewSubtypeConfiguration newSubtype) {
			transformation.newSubtype = newSubtype;
		} else {
			throw new IllegalArgumentException(configuration.getClass().toString());
		}
		return transformation;
	}

	private static String resource(String name) {
		try (InputStream input = SchemaYamlEmitterTest.class.getResourceAsStream(name)) {
			if (input == null) {
				throw new IllegalStateException("Missing test resource " + name);
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException failure) {
			throw new UncheckedIOException(failure);
		}
	}
}
