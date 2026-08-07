package it.cavallium.datagen.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Filesystem, manifest, option-matrix, and plugin-entry-point fuzzing for {@link SourcesGenerator}. */
class SourcesGeneratorFilesystemDeepFuzzTest {

	private static final long MANIFEST_SEED = 0x2E81_A4D9_70CB_563FL;
	private static final long STALE_SEED = 0x64F0_1B8C_D329_AE75L;
	private static final int MANIFEST_CASES = 128;
	private static final int STALE_CASES = 64;

	@Test
	void maximalSchemaOptionMatrixInvokesEveryGeneratorFamilyDeterministically(@TempDir Path temp)
			throws Exception {
		var fingerprints = new LinkedHashMap<String, String>();
		for (boolean oldSerializers : List.of(false, true)) {
			for (boolean binaryStrings : List.of(false, true)) {
				for (boolean vectorKernels : List.of(false, true)) {
					String key = "old=" + oldSerializers + ",binary=" + binaryStrings
							+ ",vector=" + vectorKernels;
					String basePackage = "org.example.matrix.o" + (oldSerializers ? 1 : 0)
							+ "b" + (binaryStrings ? 1 : 0) + "v" + (vectorKernels ? 1 : 0);
					Path first = temp.resolve("first-" + fingerprints.size());
					Path second = temp.resolve("second-" + fingerprints.size());
					Path yamlPath = temp.resolve("schema-" + fingerprints.size() + ".yaml");
					Files.writeString(yamlPath, maximalSchema(), StandardCharsets.UTF_8);

					SourcesGenerator.load(yamlPath).generateSources(basePackage, first, false,
							oldSerializers, binaryStrings, vectorKernels);
					load(maximalSchema()).generateSources(basePackage, second, false,
							oldSerializers, binaryStrings, vectorKernels);
					Map<String, byte[]> firstSnapshot = snapshot(first);
					Map<String, byte[]> secondSnapshot = snapshot(second);
					assertSnapshotsEqual(firstSnapshot, secondSnapshot, key);
					assertAllGeneratorFamiliesPresent(firstSnapshot, basePackage, key);
					String fingerprint = manifestFingerprint(first, basePackage);
					assertEquals(64, fingerprint.length(), key);
					assertTrue(fingerprints.values().stream().noneMatch(fingerprint::equals), key);
					fingerprints.put(key, fingerprint);

					Map<String, byte[]> beforeCacheHit = snapshot(first);
					load(maximalSchema()).generateSources(basePackage, first, false,
							oldSerializers, binaryStrings, vectorKernels);
					assertSnapshotsEqual(beforeCacheHit, snapshot(first), key + ", cache hit");
				}
			}
		}
		assertEquals(8, fingerprints.size());
	}

	@Test
	void everyFingerprintInputChangesTheManifestAndRepairsOutputs(@TempDir Path temp) throws Exception {
		String basePackage = "org.example.fingerprint";
		String schema = maximalSchema();
		Path out = temp.resolve("out");
		SourcesGenerator original = load(schema);
		original.generateSources(basePackage, out, false, false, false, false);
		String baseline = manifestFingerprint(out, basePackage);
		Path victim = out.resolve(basePackage.replace('.', '/')).resolve("current/data/Root.java");
		String expectedVictim = Files.readString(victim);

		List<GenerationInput> mutations = List.of(
				new GenerationInput(basePackage, schema + "\n# raw-byte fingerprint mutation\n", false, false, false),
				new GenerationInput(basePackage + ".other", schema, false, false, false),
				new GenerationInput(basePackage, schema, true, false, false),
				new GenerationInput(basePackage, schema, false, true, false),
				new GenerationInput(basePackage, schema, false, false, true));
		for (int index = 0; index < mutations.size(); index++) {
			GenerationInput mutation = mutations.get(index);
			Path mutationOut = temp.resolve("mutation-" + index);
			load(mutation.yaml()).generateSources(mutation.basePackage(), mutationOut, false,
					mutation.oldSerializers(), mutation.binaryStrings(), mutation.vectorKernels());
			assertNotEquals(baseline, manifestFingerprint(mutationOut, mutation.basePackage()),
					"mutation=" + index);
		}

		Files.writeString(victim, "package corrupted;\n", StandardCharsets.UTF_8);
		original.generateSources(basePackage, out, false, false, false, false);
		assertEquals(expectedVictim, Files.readString(victim));
		assertEquals(baseline, manifestFingerprint(out, basePackage));
	}

	@Test
	void malformedAndHostileManifestsNeverProduceCacheHitsOrDeleteManualFiles(@TempDir Path temp)
			throws Exception {
		var random = new Random(MANIFEST_SEED);
		String schema = maximalSchema();
		for (int caseIndex = 0; caseIndex < MANIFEST_CASES; caseIndex++) {
			String basePackage = "org.example.manifest.c" + caseIndex;
			Path out = temp.resolve("case-" + caseIndex);
			SourcesGenerator generator = load(schema);
			generator.generateSources(basePackage, out, false, false, false, false);
			Path packagePath = out.resolve(basePackage.replace('.', '/'));
			Path manifest = packagePath.resolve(".datagen-manifest-v1");
			List<String> validLines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
			Path victim;
			try (var paths = Files.walk(packagePath)) {
				victim = paths.filter(path -> path.toString().endsWith(".java")).sorted()
						.findFirst().orElseThrow();
			}
			String expectedVictim = Files.readString(victim);
			Files.writeString(victim, "package corrupted;\n", StandardCharsets.UTF_8);
			Path manual = packagePath.resolve("manual/KeepMe.java");
			Files.createDirectories(manual.getParent());
			Files.writeString(manual, "final class KeepMe {}\n", StandardCharsets.UTF_8);
			String malformed = malformedManifest(random, caseIndex, validLines, manual, out);
			Files.writeString(manifest, malformed, StandardCharsets.UTF_8);

			generator.generateSources(basePackage, out, false, false, false, false);
			assertEquals(expectedVictim, Files.readString(victim), "case=" + caseIndex);
			assertEquals("final class KeepMe {}\n", Files.readString(manual), "case=" + caseIndex);
			assertValidManifest(manifest, out, "case=" + caseIndex);
		}
	}

	@Test
	void syntacticallyValidPartialManifestsCannotForgeCacheHits(@TempDir Path temp) throws Exception {
		String schema = maximalSchema();
		for (int caseIndex = 0; caseIndex < 96; caseIndex++) {
			String basePackage = "org.example.partialmanifest.c" + caseIndex;
			Path out = temp.resolve("partial-" + caseIndex);
			SourcesGenerator generator = load(schema);
			generator.generateSources(basePackage, out, false, false, false, false);
			Path packagePath = out.resolve(basePackage.replace('.', '/'));
			Path manifest = packagePath.resolve(".datagen-manifest-v1");
			List<String> valid = Files.readAllLines(manifest, StandardCharsets.UTF_8);
			Path victim = packagePath.resolve("current/data/Root.java");
			String expectedVictim = Files.readString(victim);
			Files.writeString(victim, "package corrupted;\n", StandardCharsets.UTF_8);

			Path manual = packagePath.resolve("manual/Keep.java");
			Files.createDirectories(manual.getParent());
			Files.writeString(manual, "final class Keep {}\n", StandardCharsets.UTF_8);
			String generation = valid.get(1);
			String fingerprint = valid.get(2);
			String victimRelative = out.relativize(victim).toString();
			String unrelatedGenerated = valid.subList(4, valid.size()).stream()
					.filter(line -> !line.endsWith("\t" + victimRelative))
					.findFirst().orElseThrow();
			String manualEntry = sha256(Files.readAllBytes(manual)) + "\t" + out.relativize(manual);
			String forged = switch (caseIndex % 3) {
				case 0 -> "data-generator-manifest-v2\n" + generation + "\n" + fingerprint + "\nfiles:\n";
				case 1 -> "data-generator-manifest-v2\n" + generation + "\n" + fingerprint + "\nfiles:\n"
						+ unrelatedGenerated + "\n";
				case 2 -> "data-generator-manifest-v2\n" + generation + "\n" + fingerprint + "\nfiles:\n"
						+ manualEntry + "\n";
				default -> throw new AssertionError();
			};
			Files.writeString(manifest, forged, StandardCharsets.UTF_8);

			generator.generateSources(basePackage, out, false, false, false, false);
			assertEquals(expectedVictim, Files.readString(victim), "case=" + caseIndex);
			assertEquals("final class Keep {}\n", Files.readString(manual), "case=" + caseIndex);
			assertValidManifest(manifest, out, "case=" + caseIndex);
		}
	}

	@Test
	void staleManifestEntriesCannotEscapeOutputThroughSymbolicLinks(@TempDir Path temp) throws Exception {
		String basePackage = "org.example.symlinkmanifest";
		Path out = temp.resolve("out");
		SourcesGenerator generator = load(maximalSchema());
		generator.generateSources(basePackage, out, false, false, false, false);
		Path packagePath = out.resolve(basePackage.replace('.', '/'));
		Path manifest = packagePath.resolve(".datagen-manifest-v1");
		Path victim = packagePath.resolve("current/data/Root.java");
		String expectedVictim = Files.readString(victim);

		Path externalDirectory = temp.resolve("outside-output");
		Files.createDirectories(externalDirectory);
		Path externalFile = externalDirectory.resolve("Keep.java");
		Files.writeString(externalFile, "final class Keep {}\n", StandardCharsets.UTF_8);
		Path link = out.resolve("manifest-link");
		try {
			Files.createSymbolicLink(link, externalDirectory);
		} catch (UnsupportedOperationException | IOException unsupported) {
			return;
		}

		var forged = new ArrayList<>(Files.readAllLines(manifest, StandardCharsets.UTF_8));
		forged.add(sha256(Files.readAllBytes(externalFile)) + "\tmanifest-link/Keep.java");
		Files.write(manifest, forged, StandardCharsets.UTF_8);
		Files.writeString(victim, "package corrupted;\n", StandardCharsets.UTF_8);

		generator.generateSources(basePackage, out, false, false, false, false);
		assertTrue(Files.isRegularFile(externalFile), "stale cleanup followed a symlink outside the output root");
		assertEquals("final class Keep {}\n", Files.readString(externalFile));
		assertEquals(expectedVictim, Files.readString(victim));
		assertValidManifest(manifest, out, "symlink manifest");
	}

	@Test
	void staleGeneratedFilesAreRemovedAcrossRandomSchemaContractionsButManualNeighborsSurvive(
			@TempDir Path temp) throws Exception {
		var random = new Random(STALE_SEED);
		for (int caseIndex = 0; caseIndex < STALE_CASES; caseIndex++) {
			int initialTypes = 2 + random.nextInt(15);
			int retainedTypes = 1 + random.nextInt(initialTypes - 1);
			String basePackage = "org.example.stale.c" + caseIndex;
			Path out = temp.resolve("case-" + caseIndex);
			load(flatSchema(initialTypes)).generateSources(basePackage, out, false,
					random.nextBoolean(), random.nextBoolean(), random.nextBoolean());
			Path packagePath = out.resolve(basePackage.replace('.', '/'));
			Path manual = packagePath.resolve("current/data/ManualNeighbor.java");
			Files.createDirectories(manual.getParent());
			Files.writeString(manual, "package " + basePackage + ".current.data; class ManualNeighbor {}\n");

			load(flatSchema(retainedTypes)).generateSources(basePackage, out, false,
					random.nextBoolean(), random.nextBoolean(), random.nextBoolean());
			for (int type = 0; type < retainedTypes; type++) {
				assertTrue(Files.isRegularFile(packagePath.resolve("current/data/T" + type + ".java")),
						"case=" + caseIndex + ", retained=" + type);
			}
			for (int type = retainedTypes; type < initialTypes; type++) {
				assertFalse(Files.exists(packagePath.resolve("current/data/T" + type + ".java")),
						"case=" + caseIndex + ", stale=" + type);
				assertFalse(Files.exists(packagePath.resolve("current/serializers/T" + type + "Serializer.java")),
						"case=" + caseIndex + ", stale serializer=" + type);
			}
			assertTrue(Files.isRegularFile(manual), "case=" + caseIndex);
			assertValidManifest(packagePath.resolve(".datagen-manifest-v1"), out, "case=" + caseIndex);
		}
	}

	@Test
	void standaloneAndMavenMojoEntryPointsExerciseBothSourceRootModesAndEveryFlag(
			@TempDir Path temp) throws Exception {
		Path config = temp.resolve("schema.yaml");
		Files.writeString(config, maximalSchema(), StandardCharsets.UTF_8);
		Path standaloneOut = temp.resolve("standalone");
		Standalone.main(new String[] {config.toString(), "org.example.standalone", standaloneOut.toString(),
				"true", "true", "true", "true"});
		assertTrue(Files.isRegularFile(standaloneOut.resolve("org/example/standalone/Versions.java")));

		for (boolean testSources : List.of(false, true)) {
			Path projectDir = temp.resolve(testSources ? "mojo-test" : "mojo-main");
			Files.createDirectories(projectDir);
			Path pom = projectDir.resolve("pom.xml");
			Files.writeString(pom, "<project/>\n", StandardCharsets.UTF_8);
			MavenProject project = new MavenProject();
			project.setFile(pom.toFile());
			MavenPlugin mojo = new MavenPlugin();
			setField(mojo, "configPath", config.toFile());
			setField(mojo, "basePackageName", "org.example.mojo" + (testSources ? "test" : "main"));
			setField(mojo, "generateOldSerializers", true);
			setField(mojo, "generateTestResources", testSources);
			setField(mojo, "binaryStrings", true);
			setField(mojo, "vectorKernels", true);
			mojo.project = project;
			mojo.execute();

			Path sourceRoot = projectDir.resolve("target")
					.resolve(testSources ? "generated-test-sources" : "generated-sources")
					.resolve("database-classes/java");
			assertTrue(Files.isRegularFile(sourceRoot.resolve(testSources
					? "org/example/mojotest/Versions.java" : "org/example/mojomain/Versions.java")));
			List<String> registered = testSources ? project.getTestCompileSourceRoots()
					: project.getCompileSourceRoots();
			assertTrue(registered.contains(sourceRoot.toString()));
		}
	}

	@Test
	void loaderAndMavenMojoPreserveEmptyDocumentAndIoFailureBoundaries(@TempDir Path temp)
			throws Exception {
		NullPointerException empty = assertThrows(NullPointerException.class,
				() -> SourcesGenerator.load(new ByteArrayInputStream(new byte[0])));
		assertEquals("YAML document is empty", empty.getMessage());

		InputStream failing = new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("fuzz-input-failure");
			}
		};
		IOException inputFailure = assertThrows(IOException.class, () -> SourcesGenerator.load(failing));
		assertEquals("fuzz-input-failure", inputFailure.getMessage());

		Path projectDir = temp.resolve("mojo-io-failure");
		Files.createDirectories(projectDir);
		Path pom = projectDir.resolve("pom.xml");
		Files.writeString(pom, "<project/>\n", StandardCharsets.UTF_8);
		MavenProject project = new MavenProject();
		project.setFile(pom.toFile());
		MavenPlugin mojo = new MavenPlugin();
		setField(mojo, "configPath", projectDir.resolve("missing.yaml").toFile());
		setField(mojo, "basePackageName", "org.example.missing");
		mojo.project = project;
		MojoExecutionException mojoFailure = assertThrows(MojoExecutionException.class, mojo::execute);
		assertTrue(mojoFailure.getCause() instanceof IOException);
		assertTrue(project.getCompileSourceRoots().isEmpty());
		assertTrue(project.getTestCompileSourceRoots().isEmpty());
	}

	private static String malformedManifest(Random random,
			int caseIndex,
			List<String> valid,
			Path manual,
			Path out) throws Exception {
		String validGeneration = valid.get(1);
		String validFingerprint = valid.get(2);
		String validEntry = valid.size() > 4 ? valid.get(4)
				: sha256(new byte[0]) + "\tmissing.java";
		return switch (caseIndex % 20) {
			case 0 -> "";
			case 1 -> "wrong-header\n" + validGeneration + "\n" + validFingerprint + "\nfiles:\n";
			case 2 -> "data-generator-manifest-v2\n";
			case 3 -> "data-generator-manifest-v2\ngeneration=short\n" + validFingerprint + "\nfiles:\n";
			case 4 -> "data-generator-manifest-v2\ngeneration=" + "G".repeat(64) + "\n"
					+ validFingerprint + "\nfiles:\n";
			case 5 -> "data-generator-manifest-v2\n" + validGeneration + "\nfingerprint=short\nfiles:\n";
			case 6 -> "data-generator-manifest-v2\n" + validGeneration + "\n" + validFingerprint
					+ "\nwrong-section\n";
			case 7 -> "data-generator-manifest-v2\n" + validGeneration + "\n" + validFingerprint
					+ "\nfiles:\nshort\tfile.java\n";
			case 8 -> "data-generator-manifest-v2\n" + validGeneration + "\n" + validFingerprint + "\nfiles:\n"
					+ "0".repeat(64) + " file.java\n";
			case 9 -> "data-generator-manifest-v2\n" + validGeneration + "\n" + validFingerprint + "\nfiles:\n"
					+ "z".repeat(64) + "\tfile.java\n";
			case 10 -> "data-generator-manifest-v2\n" + validGeneration + "\n" + validFingerprint + "\nfiles:\n"
					+ sha256(new byte[0]) + "\t../" + manual.getFileName() + "\n";
			case 11 -> "data-generator-manifest-v2\n" + validGeneration + "\n" + validFingerprint + "\nfiles:\n"
					+ sha256(new byte[0]) + "\t" + manual.toAbsolutePath() + "\n";
			case 12 -> "data-generator-manifest-v2\n" + validGeneration + "\n" + validFingerprint + "\nfiles:\n"
					+ validEntry + "\n" + validEntry + "\n";
			case 13 -> "data-generator-manifest-v2\n" + validGeneration.toUpperCase() + "\n"
					+ validFingerprint + "\nfiles:\n";
			case 14 -> String.join("\r\n", valid) + "\r\n" + "bad-trailer";
			case 15 -> "data-generator-manifest-v2\n" + validGeneration + "\n" + validFingerprint + "\nfiles:\n"
					+ sha256(new byte[0]) + "\t..\n";
			case 16 -> {
				var mutated = new ArrayList<>(valid);
				int line = 4 + random.nextInt(Math.max(1, mutated.size() - 4));
				if (line < mutated.size()) mutated.set(line, mutated.get(line) + "\tduplicate-suffix");
				yield String.join("\n", mutated) + "\n";
			}
			case 17 -> "data-generator-manifest-v2\n" + validGeneration + "\n" + validFingerprint + "\nfiles:\n"
					+ sha256(new byte[0]) + "\tbad" + String.valueOf((char) 0) + "path.java\n";
			case 18 -> "data-generator-manifest-v2\n" + validGeneration + "\n" + validFingerprint + "\nfiles:\n"
					+ sha256(new byte[0]) + "\t\n";
			case 19 -> "data-generator-manifest-v2\n" + validGeneration + "\n" + validFingerprint + "\nfiles:\n"
					+ sha256(new byte[0]) + "\ta/../../outside.java\n";
			default -> throw new AssertionError();
		};
	}

	private static void assertValidManifest(Path manifest, Path out, String diagnostic) throws Exception {
		List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
		assertTrue(lines.size() >= 4, diagnostic);
		assertEquals("data-generator-manifest-v2", lines.get(0), diagnostic);
		assertTrue(lines.get(1).matches("generation=[0-9a-f]{64}"), diagnostic);
		assertTrue(lines.get(2).matches("fingerprint=[0-9a-f]{64}"), diagnostic);
		assertEquals("files:", lines.get(3), diagnostic);
		var paths = new java.util.HashSet<String>();
		for (int index = 4; index < lines.size(); index++) {
			String[] parts = lines.get(index).split("\\t", -1);
			assertEquals(2, parts.length, diagnostic + ", line=" + index);
			assertTrue(parts[0].matches("[0-9a-f]{64}"), diagnostic + ", line=" + index);
			Path relative = Path.of(parts[1]);
			assertFalse(relative.isAbsolute(), diagnostic);
			assertFalse(relative.normalize().startsWith(".."), diagnostic);
			assertTrue(paths.add(parts[1]), diagnostic + ", duplicate=" + parts[1]);
			Path generated = out.resolve(relative);
			assertTrue(Files.isRegularFile(generated), diagnostic + ", missing=" + relative);
			assertEquals(parts[0], sha256(Files.readAllBytes(generated)), diagnostic + ", digest=" + relative);
		}
	}

	private static void assertAllGeneratorFamiliesPresent(Map<String, byte[]> snapshot,
			String basePackage,
			String diagnostic) {
		String root = basePackage.replace('.', '/');
		List<String> exact = List.of(
				root + "/Versions.java",
				root + "/BaseType.java",
				root + "/SuperType.java",
				root + "/IVersion.java",
				root + "/current/CurrentVersion.java",
				root + "/current/Version.java",
				root + "/current/IBaseType.java",
				root + "/current/IType.java",
				root + "/current/data/A.java",
				root + "/current/data/B.java",
				root + "/current/data/Root.java",
				root + "/current/data/Choice.java",
				root + "/current/data/nullables/INullableIType.java",
				root + "/current/data/nullables/INullableBaseType.java",
				root + "/current/data/nullables/INullableSuperType.java",
				root + "/current/data/nullables/NullableA.java",
				root + "/current/data/nullables/NullableFixed.java",
				root + "/current/readers/RootReadPlan.java",
				root + "/current/serializers/RootSerializer.java",
				root + "/current/serializers/ChoiceSerializer.java",
				root + "/current/serializers/ArrayASerializer.java",
				root + "/current/serializers/NullableASerializer.java",
				root + "/projections/RootDigestProjection.java");
		for (String path : exact) assertTrue(snapshot.containsKey(path), diagnostic + ", missing=" + path);
		assertTrue(snapshot.keySet().stream().anyMatch(path -> path.startsWith(root + "/v0/upgraders/")
				&& path.endsWith("Upgrader.java")), diagnostic);
		assertTrue(snapshot.keySet().stream().anyMatch(path -> path.startsWith(root + "/current/data/nullables/")
				&& path.endsWith(".java")), diagnostic);
		assertTrue(snapshot.keySet().stream().filter(path -> path.endsWith(".java")).count() >= 40,
				diagnostic + ", generated=" + snapshot.size());
	}

	private static String manifestFingerprint(Path out, String basePackage) throws Exception {
		Path manifest = out.resolve(basePackage.replace('.', '/')).resolve(".datagen-manifest-v1");
		String line = Files.readAllLines(manifest, StandardCharsets.UTF_8).get(1);
		return line.substring("generation=".length());
	}

	private static Map<String, byte[]> snapshot(Path root) throws Exception {
		var result = new LinkedHashMap<String, byte[]>();
		try (var paths = Files.walk(root)) {
			for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
				result.put(root.relativize(path).toString(), Files.readAllBytes(path));
			}
		}
		return result;
	}

	private static void assertSnapshotsEqual(Map<String, byte[]> expected,
			Map<String, byte[]> actual,
			String diagnostic) {
		assertEquals(expected.keySet(), actual.keySet(), diagnostic);
		for (String path : expected.keySet()) {
			assertTrue(Arrays.equals(expected.get(path), actual.get(path)), diagnostic + ", path=" + path);
		}
	}

	private static SourcesGenerator load(String yaml) throws Exception {
		return SourcesGenerator.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
	}

	private static String sha256(byte[] value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static String flatSchema(int types) {
		StringBuilder yaml = new StringBuilder("currentVersion: v1\nbaseTypesData:\n");
		for (int type = 0; type < types; type++) {
			yaml.append("  T").append(type).append(":\n    data:\n      value: long\n");
		}
		yaml.append("versions:\n  v1:\n");
		return yaml.toString();
	}

	private static String maximalSchema() {
		return """
				currentVersion: v3
				customTypesData:
				  Fixed:
				    javaClass: java.lang.Integer
				    codec: it.cavallium.datagen.plugin.TestFixedIntCodec
				    fixedSize: 4
				baseTypesData:
				  A:
				    data:
				      kind: int
				      value: long
				  B:
				    data:
				      kind: int
				      text: String
				  Root:
				    data:
				      id: int
				      retired: String
				      choice: Choice
				      choices: Choice[]
				      child: A
				      children: A[]
				      maybeChild: -A
				      fixed: Fixed
				      fixeds: Fixed[]
				      maybeFixed: -Fixed
				      strings: String[]
				      maybeString: -String
				      packed: Int52
				      maybePacked: -Int52
				superTypesData:
				  Choice: [A, B]
				interfacesData:
				  Choice:
				    commonData: { kind: int }
				projectionsData:
				  RootDigest:
				    sourceType: Root
				    fields:
				      identifier: identifier
				      choice: choice
				      childValue: child.value
				      maybeChildValue: maybeChild.value
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - moveData: { transformClass: Root, from: id, to: identifier }
				      - removeData: { transformClass: Root, from: retired }
				      - newData:
				          transformClass: A
				          to: generation
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				          readTransform: { constant: { value: 7 } }
				  v3:
				    previousVersion: v2
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: identifier
				          type: long
				          upgrader: it.cavallium.datagen.plugin.TestSimpleIntToLongUpgrader
				          readTransform:
				            invokeStatic:
				              method: it.cavallium.datagen.plugin.TortureTransforms.widen
				              arguments:
				                - identity: { source: value }
				""";
	}

	private record GenerationInput(String basePackage,
			String yaml,
			boolean oldSerializers,
			boolean binaryStrings,
			boolean vectorKernels) {}
}
