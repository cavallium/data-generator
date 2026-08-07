package it.cavallium.datagen.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourcesGeneratorSchemaDeepFuzzTest {

	private static final long SCHEMA_SEED = 0x31A7_6C9E_42D0_58BFL;
	private static final long EVOLUTION_SEED = 0x6D02_B8F1_95AC_347EL;
	private static final long INVALID_SEED = 0x17C4_EA39_80B6_25DFL;
	private static final int VALID_SCHEMA_CASES = 64;
	private static final int EVOLUTION_CASES = 48;
	private static final int INVALID_SCHEMA_CASES = 1_024;
	private static final List<String> SCALAR_TYPES = List.of(
			"boolean", "byte", "short", "char", "int", "long", "float", "double", "String", "Int52");
	private static final List<String> ARRAY_TYPES = List.of(
			"boolean[]", "byte[]", "short[]", "char[]", "int[]", "long[]", "float[]", "double[]",
			"String[]", "Int52[]");

	@Test
	void randomizedValidSchemasGenerateByteForByteDeterministicallyAndRepairTampering(@TempDir Path temp)
			throws Exception {
		var random = new Random(SCHEMA_SEED);
		for (int caseIndex = 0; caseIndex < VALID_SCHEMA_CASES; caseIndex++) {
			SchemaCase schemaCase = randomSchema(random, caseIndex);
			String basePackage = "org.example.schemafuzz.c" + caseIndex;
			Path first = temp.resolve("case-" + caseIndex + "-first");
			Path second = temp.resolve("case-" + caseIndex + "-second");
			boolean generateOldSerializers = random.nextBoolean();
			boolean binaryStrings = random.nextBoolean();
			boolean vectorKernels = random.nextBoolean();

			SourcesGenerator generator = load(schemaCase.yaml());
			generator.generateSources(basePackage, first, false, generateOldSerializers,
					binaryStrings, vectorKernels);
			generator.generateSources(basePackage, second, false, generateOldSerializers,
					binaryStrings, vectorKernels);
			Map<String, String> expected = snapshot(first);
			String diagnostic = diagnostic(SCHEMA_SEED, caseIndex, schemaCase.yaml());
			assertEquals(expected, snapshot(second), diagnostic);
			assertTrue(expected.keySet().stream().anyMatch(name -> name.endsWith("/.datagen-manifest-v1")),
					diagnostic);
			assertTrue(expected.keySet().stream().anyMatch(name -> name.endsWith("/current/data/"
						+ schemaCase.lastType() + ".java")), diagnostic);
			assertTrue(expected.keySet().stream().anyMatch(name -> name.endsWith("/projections/"
						+ schemaCase.projectionName() + "Projection.java")), diagnostic);

			generator.generateSources(basePackage, first, false, generateOldSerializers,
					binaryStrings, vectorKernels);
			assertEquals(expected, snapshot(first), diagnostic + ", cache-hit regeneration");

			if (caseIndex % 8 == 0) {
				Path victim;
				try (var paths = Files.walk(first)) {
					victim = paths.filter(path -> path.toString().endsWith(".java"))
							.sorted().findFirst().orElseThrow();
				}
				Files.writeString(victim, "package broken;\n", StandardCharsets.UTF_8);
				generator.generateSources(basePackage, first, false, generateOldSerializers,
						binaryStrings, vectorKernels);
				assertEquals(expected, snapshot(first), diagnostic + ", repaired=" + victim);
			}
		}
	}

	@Test
	void randomizedEvolutionChainsRemainDeterministicAcrossMovesAddsRemovalsAndProjections(
			@TempDir Path temp) throws Exception {
		var random = new Random(EVOLUTION_SEED);
		for (int caseIndex = 0; caseIndex < EVOLUTION_CASES; caseIndex++) {
			EvolutionCase evolution = randomEvolution(random, caseIndex);
			String basePackage = "org.example.evolutionfuzz.c" + caseIndex;
			Path first = temp.resolve("evolution-" + caseIndex + "-first");
			Path second = temp.resolve("evolution-" + caseIndex + "-second");
			boolean binaryStrings = random.nextBoolean();
			generate(evolution.yaml(), basePackage, first, true, binaryStrings, false);
			generate(evolution.yaml(), basePackage, second, true, binaryStrings, false);
			String diagnostic = diagnostic(EVOLUTION_SEED, caseIndex, evolution.yaml());
			assertEquals(snapshot(first), snapshot(second), diagnostic);

			Path currentRecord = first.resolve(basePackage.replace('.', '/'))
					.resolve("current/data/RecordValue.java");
			String generated = Files.readString(currentRecord);
			for (String activeField : evolution.activeFields()) {
				assertTrue(generated.contains(activeField), diagnostic + ", missing=" + activeField);
			}
			for (String retiredField : evolution.retiredFields()) {
				assertFalse(generated.contains(" " + retiredField + ";"),
						diagnostic + ", retired=" + retiredField);
			}
		}
	}

	@Test
	void invalidSchemaMutationsFailDeterministicallyWithoutPublishingAManifest(@TempDir Path temp) {
		var random = new Random(INVALID_SEED);
		for (int caseIndex = 0; caseIndex < INVALID_SCHEMA_CASES; caseIndex++) {
			int fuzzCase = caseIndex;
			String invalid = invalidSchema(random, caseIndex);
			String basePackage = "org.example.invalidfuzz.c" + caseIndex;
			Path first = temp.resolve("invalid-" + caseIndex + "-first");
			Path second = temp.resolve("invalid-" + caseIndex + "-second");
			RuntimeException firstFailure = assertThrows(RuntimeException.class,
					() -> generate(invalid, basePackage, first, false, false, false),
					() -> diagnostic(INVALID_SEED, fuzzCase, invalid));
			RuntimeException secondFailure = assertThrows(RuntimeException.class,
					() -> generate(invalid, basePackage, second, false, false, false),
					() -> diagnostic(INVALID_SEED, fuzzCase, invalid));
			assertEquals(firstFailure.getClass(), secondFailure.getClass(),
					() -> diagnostic(INVALID_SEED, fuzzCase, invalid));
			assertEquals(firstFailure.getMessage(), secondFailure.getMessage(),
					() -> diagnostic(INVALID_SEED, fuzzCase, invalid));
			assertFalse(hasManifest(first), () -> "partial manifest: " + first);
			assertFalse(hasManifest(second), () -> "partial manifest: " + second);
		}
	}

	private static SchemaCase randomSchema(Random random, int caseIndex) {
		int typeCount = 1 + random.nextInt(9);
		var fieldsByType = new ArrayList<LinkedHashMap<String, String>>();
		StringBuilder yaml = new StringBuilder("currentVersion: v")
				.append(1 + random.nextInt(6)).append("\nbaseTypesData:\n");
		int currentVersion = Integer.parseInt(yaml.substring("currentVersion: v".length(), yaml.indexOf("\n")));
		for (int typeIndex = 0; typeIndex < typeCount; typeIndex++) {
			int fieldCount = 1 + random.nextInt(12);
			var fields = new LinkedHashMap<String, String>();
			yaml.append("  T").append(typeIndex).append(":\n    data:\n");
			for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
				String field = "f" + fieldIndex;
				String type = randomFieldType(random, typeIndex);
				fields.put(field, type);
				yaml.append("      ").append(field).append(": ").append(type).append('\n');
			}
			fieldsByType.add(fields);
		}
		if (typeCount >= 2 && random.nextBoolean()) {
			int alternatives = 2 + random.nextInt(Math.min(typeCount, 6) - 1);
			yaml.append("superTypesData:\n  Choice").append(caseIndex).append(":\n");
			var chosen = new LinkedHashSet<Integer>();
			while (chosen.size() < alternatives) chosen.add(random.nextInt(typeCount));
			for (int alternative : chosen) yaml.append("    - T").append(alternative).append('\n');
		}
		String projectionName = "Digest" + caseIndex;
		LinkedHashMap<String, String> lastFields = fieldsByType.getLast();
		yaml.append("projectionsData:\n  ").append(projectionName)
				.append(":\n    sourceType: T").append(typeCount - 1).append("\n    fields:\n");
		int selected = 1 + random.nextInt(Math.min(6, lastFields.size()));
		var projectionFields = new ArrayList<>(lastFields.keySet());
		Collections.shuffle(projectionFields, random);
		for (int selectedIndex = 0; selectedIndex < selected; selectedIndex++) {
			String field = projectionFields.get(selectedIndex);
			yaml.append("      p").append(selectedIndex).append(": ").append(field).append('\n');
		}
		yaml.append("versions:\n");
		for (int version = 1; version <= currentVersion; version++) {
			yaml.append("  v").append(version).append(":\n");
			if (version > 1) yaml.append("    previousVersion: v").append(version - 1).append('\n');
		}
		return new SchemaCase(yaml.toString(), "T" + (typeCount - 1), projectionName);
	}

	private static String randomFieldType(Random random, int priorTypes) {
		int category = random.nextInt(priorTypes == 0 ? 4 : 7);
		return switch (category) {
			case 0 -> SCALAR_TYPES.get(random.nextInt(SCALAR_TYPES.size()));
			case 1 -> ARRAY_TYPES.get(random.nextInt(ARRAY_TYPES.size()));
			case 2 -> "-" + SCALAR_TYPES.get(random.nextInt(SCALAR_TYPES.size()));
			case 3 -> SCALAR_TYPES.get(random.nextInt(SCALAR_TYPES.size()));
			case 4 -> "T" + random.nextInt(priorTypes);
			case 5 -> "-T" + random.nextInt(priorTypes);
			case 6 -> "T" + random.nextInt(priorTypes) + "[]";
			default -> throw new AssertionError(category);
		};
	}

	private static EvolutionCase randomEvolution(Random random, int caseIndex) {
		int initialCount = 3 + random.nextInt(6);
		int versionCount = 2 + random.nextInt(7);
		var originalFields = new ArrayList<String>();
		var active = new LinkedHashSet<String>();
		var retired = new LinkedHashSet<String>();
		int nextField = 0;
		for (; nextField < initialCount; nextField++) {
			String field = "f" + nextField;
			originalFields.add(field);
			active.add(field);
		}

		StringBuilder yaml = new StringBuilder("currentVersion: v").append(versionCount)
				.append("\nbaseTypesData:\n  RecordValue:\n    data:\n");
		for (String field : originalFields) yaml.append("      ").append(field).append(": long\n");
		yaml.append("versions:\n  v1:\n");
		for (int version = 2; version <= versionCount; version++) {
			yaml.append("  v").append(version).append(":\n    previousVersion: v")
					.append(version - 1).append("\n    transformations:\n");
			int actions = 1 + random.nextInt(3);
			for (int action = 0; action < actions; action++) {
				int kind = random.nextInt(3);
				if (kind == 0) {
					String from = pick(random, active);
					String to = "f" + nextField++;
					yaml.append("      - moveData: { transformClass: RecordValue, from: ")
							.append(from).append(", to: ").append(to).append(" }\n");
					active.remove(from);
					active.add(to);
					retired.add(from);
				} else if (kind == 1 || active.size() == 1) {
					String added = "f" + nextField++;
					yaml.append("      - newData:\n          transformClass: RecordValue\n")
							.append("          to: ").append(added)
							.append("\n          type: long\n")
							.append("          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer\n");
					active.add(added);
				} else {
					String removed = pick(random, active);
					yaml.append("      - removeData: { transformClass: RecordValue, from: ")
							.append(removed).append(" }\n");
					active.remove(removed);
					retired.add(removed);
				}
			}
		}
		yaml.append("projectionsData:\n  CurrentDigest").append(caseIndex)
				.append(":\n    sourceType: RecordValue\n    fields:\n");
		int selected = 0;
		for (String field : active) {
			if (selected == 3) break;
			yaml.append("      p").append(selected++).append(": ").append(field).append('\n');
		}
		return new EvolutionCase(yaml.toString(), List.copyOf(active), List.copyOf(retired));
	}

	private static String invalidSchema(Random random, int caseIndex) {
		String suffix = caseIndex + "x" + Integer.toUnsignedString(random.nextInt(), 36);
		return switch (caseIndex % 32) {
			case 0 -> """
					currentVersion: v1
					baseTypesData:
					  A%s: { data: {} }
					superTypesData:
					  Choice%s: [A%s, A%s]
					versions:
					  v1:
					""".formatted(suffix, suffix, suffix, suffix);
			case 1 -> """
					currentVersion: v1
					baseTypesData:
					  A%s: { data: {} }
					superTypesData:
					  Choice%s: [A%s, Missing%s]
					versions:
					  v1:
					""".formatted(suffix, suffix, suffix, suffix);
			case 2 -> minimalInvalidFieldSchema("MissingType" + suffix);
			case 3 -> """
					currentVersion: v1
					baseTypesData:
					  Root%s:
					    data: { value: long }
					projectionsData:
					  Bad%s:
					    sourceType: Root%s
					    fields: { selected: missing.path }
					versions:
					  v1:
					""".formatted(suffix, suffix, suffix);
			case 4 -> """
					currentVersion: v1
					baseTypesData:
					  Root%s: { data: { value: long } }
					projectionsData:
					  Empty%s: { sourceType: Root%s, fields: {} }
					versions:
					  v1:
					""".formatted(suffix, suffix, suffix);
			case 5 -> """
					currentVersion: missing%s
					baseTypesData:
					  Root%s: { data: { value: long } }
					versions:
					  v1:
					""".formatted(suffix, suffix);
			case 6 -> """
					currentVersion: v2
					baseTypesData:
					  Root%s: { data: { value: long } }
					versions:
					  v1:
					  v2: { previousVersion: missing%s }
					""".formatted(suffix, suffix);
			case 7 -> """
					currentVersion: v1
					baseTypesData:
					  Invalid-Type%s: { data: { value: long } }
					versions:
					  v1:
					""".formatted(suffix);
			case 8 -> """
					currentVersion: v1
					baseTypesData:
					  A%s: { data: {} }
					superTypesData:
					  Empty%s: []
					versions:
					  v1:
					""".formatted(suffix, suffix);
			case 9 -> """
					currentVersion: v1
					baseTypesData:
					  A%s: { data: {} }
					superTypesData:
					  Blank%s: [A%s, '']
					versions:
					  v1:
					""".formatted(suffix, suffix, suffix);
			case 10 -> tooWideUnionSchema(suffix);
			case 11 -> """
					currentVersion: v1
					baseTypesData:
					  int: { data: { value: long } }
					versions:
					  v1:
					""";
			case 12 -> """
					currentVersion: v1
					baseTypesData:
					  Same%s: { data: {} }
					superTypesData:
					  Same%s: [Same%s]
					versions:
					  v1:
					""".formatted(suffix, suffix, suffix);
			case 13 -> """
					currentVersion: v1
					customTypesData:
					  Broken%s:
					    codec: org.example.Codec
					baseTypesData:
					  Root%s: { data: { value: Broken%s } }
					versions:
					  v1:
					""".formatted(suffix, suffix, suffix);
			case 14 -> """
					currentVersion: v1
					customTypesData:
					  Broken%s:
					    javaClass: java.lang.Integer
					baseTypesData:
					  Root%s: { data: { value: Broken%s } }
					versions:
					  v1:
					""".formatted(suffix, suffix, suffix);
			case 15 -> """
					currentVersion: v1
					customTypesData:
					  Broken%s:
					    javaClass: java.lang.Integer
					    codec: org.example.Codec
					    fixedSize: -1
					baseTypesData:
					  Root%s: { data: { value: Broken%s } }
					versions:
					  v1:
					""".formatted(suffix, suffix, suffix);
			case 16 -> """
					currentVersion: v1
					baseTypesData:
					  Root%s: { data: { value: long } }
					versions:
					  v1:
					    transformations:
					      - removeData: { transformClass: Root%s, from: value }
					""".formatted(suffix, suffix);
			case 17 -> """
					currentVersion: v3
					baseTypesData:
					  Root%s: { data: {} }
					versions:
					  v1:
					  v2: { previousVersion: v1 }
					  v3: { previousVersion: v1 }
					""".formatted(suffix);
			case 18 -> """
					currentVersion: v2
					baseTypesData:
					  Root%s: { data: {} }
					versions:
					  v1:
					  v2:
					""".formatted(suffix);
			case 19 -> """
					currentVersion: v1
					baseTypesData:
					  Root%s: { data: {} }
					versions:
					  v1:
					    typeVersions: { Root%s: 0 }
					""".formatted(suffix, suffix);
			case 20 -> """
					currentVersion: v1
					baseTypesData:
					  Root%s:
					    stringRepresenter: missing
					    data: { value: long }
					versions:
					  v1:
					""".formatted(suffix);
			case 21 -> """
					currentVersion: v2
					baseTypesData:
					  Root%s:
					    stringRepresenter: text
					    data: { text: String }
					versions:
					  v1:
					  v2:
					    previousVersion: v1
					    transformations:
					      - removeData: { transformClass: Root%s, from: text }
					""".formatted(suffix, suffix);
			case 22 -> transformationSchema(suffix,
					"moveData: { transformClass: Root%s, from: first, to: second }".formatted(suffix));
			case 23 -> transformationSchema(suffix, """
					newData:
					  transformClass: Root%s
					  to: second
					  type: long
					  initializer: org.example.Initializer
					""".formatted(suffix).stripTrailing());
			case 24 -> transformationSchema(suffix,
					"removeData: { transformClass: Root%s, from: missing }".formatted(suffix));
			case 25 -> transformationSchema(suffix, """
					upgradeData:
					  transformClass: Root%s
					  from: missing
					  type: long
					  upgrader: org.example.Upgrader
					""".formatted(suffix).stripTrailing());
			case 26 -> transformationSchema(suffix,
					"removeData: { transformClass: Missing%s, from: first }".formatted(suffix));
			case 27 -> """
					currentVersion: v1
					baseTypesData:
					  Root%s: { data: { values: '-int[]' } }
					versions:
					  v1:
					""".formatted(suffix);
			case 28 -> """
					currentVersion: v2
					baseTypesData:
					  Root%s: { data: { value: int } }
					versions:
					  v1:
					  v2:
					    previousVersion: v1
					    transformations:
					      - upgradeData:
					          transformClass: Root%s
					          from: value
					          type: long
					          upgrader: org.example.Upgrader
					          readTransform:
					            identity: { source: value }
					            constant: { value: 1 }
					""".formatted(suffix, suffix);
			case 29 -> """
					currentVersion: v2
					baseTypesData:
					  Root%s: { data: { value: int } }
					versions:
					  v1:
					  v2:
					    previousVersion: v1
					    transformations:
					      - upgradeData:
					          transformClass: Root%s
					          from: value
					          type: long
					          upgrader: org.example.Upgrader
					          readTransform:
					            invokeStatic:
					              method: org.example.Transforms.convert
					              arguments:
					                - type: Missing%s
					                  constant: { value: 1 }
					""".formatted(suffix, suffix, suffix);
			case 30 -> """
					currentVersion: v1
					baseTypesData:
					  Root%s:
					    data:
					      items: int[]
					      itemsSize: int
					versions:
					  v1:
					""".formatted(suffix);
			case 31 -> """
					currentVersion: v1
					baseTypesData:
					  Root%s: { data: { value: long } }
					projectionsData:
					  Projection%s:
					    sourceType: Root%s
					    fields: { bad-name: value }
					versions:
					  v1:
					""".formatted(suffix, suffix, suffix);
			default -> throw new AssertionError();
		};
	}

	private static String transformationSchema(String suffix, String transformation) {
		String indented = transformation.lines().map(line -> "          " + line)
				.collect(java.util.stream.Collectors.joining("\n"));
		return """
				currentVersion: v2
				baseTypesData:
				  Root%s:
				    data: { first: int, second: long }
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - %s
				""".formatted(suffix, indented.stripLeading());
	}

	private static String tooWideUnionSchema(String suffix) {
		StringBuilder yaml = new StringBuilder("currentVersion: v1\nbaseTypesData:\n");
		for (int index = 0; index < 257; index++) {
			yaml.append("  T").append(suffix).append('_').append(index).append(": { data: {} }\n");
		}
		yaml.append("superTypesData:\n  Wide").append(suffix).append(":\n");
		for (int index = 0; index < 257; index++) {
			yaml.append("    - T").append(suffix).append('_').append(index).append('\n');
		}
		return yaml.append("versions:\n  v1:\n").toString();
	}

	private static String minimalInvalidFieldSchema(String type) {
		return "currentVersion: v1\nbaseTypesData:\n  Root:\n    data:\n      value: " + type
				+ "\nversions:\n  v1:\n";
	}

	private static String pick(Random random, LinkedHashSet<String> values) {
		return values.stream().skip(random.nextInt(values.size())).findFirst().orElseThrow();
	}

	private static SourcesGenerator load(String yaml) throws Exception {
		return SourcesGenerator.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
	}

	private static void generate(String yaml,
			String basePackage,
			Path out,
			boolean generateOldSerializers,
			boolean binaryStrings,
			boolean vectorKernels) throws Exception {
		load(yaml).generateSources(basePackage, out, false, generateOldSerializers,
				binaryStrings, vectorKernels);
	}

	private static Map<String, String> snapshot(Path root) throws Exception {
		var result = new LinkedHashMap<String, String>();
		try (var paths = Files.walk(root)) {
			for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
				result.put(root.relativize(path).toString(), HexFormat.of().formatHex(Files.readAllBytes(path)));
			}
		}
		return result;
	}

	private static boolean hasManifest(Path root) {
		if (Files.notExists(root)) return false;
		try (var paths = Files.walk(root)) {
			return paths.anyMatch(path -> path.getFileName().toString().equals(".datagen-manifest-v1"));
		} catch (Exception failure) {
			throw new AssertionError(failure);
		}
	}

	private static String diagnostic(long seed, int caseIndex, String yaml) {
		return "seed=" + seed + ", case=" + caseIndex + "\n" + yaml;
	}

	private record SchemaCase(String yaml, String lastType, String projectionName) {}

	private record EvolutionCase(String yaml, List<String> activeFields, List<String> retiredFields) {}
}
