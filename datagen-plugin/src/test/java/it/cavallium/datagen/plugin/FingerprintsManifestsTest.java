package it.cavallium.datagen.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the extraction of {@link Fingerprints} and {@link Manifests} out of {@link SourcesGenerator}:
 * the extracted routines must keep producing exactly what the former private statics produced.
 *
 * <p>Fingerprint byte-identity is checked against the still-present private
 * {@code SourcesGenerator.generationFingerprint(String, boolean, boolean, boolean, byte[])} through
 * reflection, so the expected values are computed by the generator's own code path and are not literals.
 * The manifest expectations come from a manifest written by a real {@code generateSources} run, parsed
 * independently in this test.
 */
class FingerprintsManifestsTest {

	private static final String BASE_PACKAGE = "org.example";

	private static final String SMALL_SCHEMA = """
			currentVersion: v1
			baseTypesData:
			  A: {data: {value: int}}
			superTypesData:
			  Choice: [A]
			versions:
			  v1:
			""";

	private static final String SMALL_SCHEMA_VARIANT = """
			currentVersion: v1
			baseTypesData:
			  A: {data: {value: int}}
			  B: {data: {value: long}}
			superTypesData:
			  Choice: [A]
			versions:
			  v1:
			""";

	@Test
	void fingerprintsOfSchemaMatchThePrivateGeneratorFingerprint() throws Exception {
		String adversarial = resource("/adversarial-evolution.yaml");
		// Every generator option plus the schema itself is exercised by at least one case.
		record Case(String name, String yaml, String basePackage, boolean oldSerializers,
				boolean binaryStrings, boolean vectorKernels) {}
		List<Case> cases = List.of(
				new Case("defaults", SMALL_SCHEMA, BASE_PACKAGE, false, false, false),
				new Case("old-serializers", SMALL_SCHEMA, BASE_PACKAGE, true, false, false),
				new Case("binary-strings", SMALL_SCHEMA, BASE_PACKAGE, false, true, false),
				new Case("vector-kernels", SMALL_SCHEMA, BASE_PACKAGE, false, false, true),
				new Case("other-base-package", SMALL_SCHEMA, "org.example.other", false, false, false),
				new Case("other-schema", SMALL_SCHEMA_VARIANT, BASE_PACKAGE, false, false, false),
				new Case("all-options", SMALL_SCHEMA_VARIANT, "net.variant", true, true, true),
				new Case("adversarial", adversarial, BASE_PACKAGE, false, false, false),
				new Case("adversarial-binary", adversarial, BASE_PACKAGE, false, true, false));

		for (Case testCase : cases) {
			// The schema must be a real, loadable configuration, exactly like the generator receives it.
			SourcesGenerator.load(new ByteArrayInputStream(
					testCase.yaml().getBytes(StandardCharsets.UTF_8)));
			String expected = privateGenerationFingerprint(testCase.yaml(), testCase.basePackage(),
					testCase.oldSerializers(), testCase.binaryStrings(), testCase.vectorKernels());
			String actual = Fingerprints.ofSchema(testCase.yaml().getBytes(StandardCharsets.UTF_8),
					testCase.basePackage(), testCase.oldSerializers(), testCase.binaryStrings(),
					testCase.vectorKernels());
			assertEquals(expected, actual, () -> "fingerprint mismatch for " + testCase.name());
			assertEquals(64, actual.length(), testCase.name());
		}
	}

	@Test
	void everyFingerprintInputChangesTheFingerprintAndRepeatsAreIdentical() throws Exception {
		String baseline = Fingerprints.ofSchema(SMALL_SCHEMA.getBytes(StandardCharsets.UTF_8),
				BASE_PACKAGE, false, false, false);

		assertEquals(baseline, Fingerprints.ofSchema(SMALL_SCHEMA.getBytes(StandardCharsets.UTF_8),
				BASE_PACKAGE, false, false, false), "identical schema and options must repeat");
		assertNotEquals(baseline, Fingerprints.ofSchema(SMALL_SCHEMA.getBytes(StandardCharsets.UTF_8),
				BASE_PACKAGE, true, false, false), "generateOldSerializers must change the fingerprint");
		assertNotEquals(baseline, Fingerprints.ofSchema(SMALL_SCHEMA.getBytes(StandardCharsets.UTF_8),
				BASE_PACKAGE, false, true, false), "binaryStrings must change the fingerprint");
		assertNotEquals(baseline, Fingerprints.ofSchema(SMALL_SCHEMA.getBytes(StandardCharsets.UTF_8),
				BASE_PACKAGE, false, false, true), "vectorKernels must change the fingerprint");
		assertNotEquals(baseline, Fingerprints.ofSchema(SMALL_SCHEMA.getBytes(StandardCharsets.UTF_8),
				"org.example.other", false, false, false), "basePackageName must change the fingerprint");
		assertNotEquals(baseline, Fingerprints.ofSchema(SMALL_SCHEMA_VARIANT.getBytes(StandardCharsets.UTF_8),
				BASE_PACKAGE, false, false, false), "schema content must change the fingerprint");
		// The fingerprint is over raw schema bytes, so even a comment-only difference is visible.
		assertNotEquals(baseline, Fingerprints.ofSchema((SMALL_SCHEMA + "# mutation\n").getBytes(StandardCharsets.UTF_8),
				BASE_PACKAGE, false, false, false), "raw schema bytes must change the fingerprint");
	}

	@Test
	void manifestsReadReturnsWhatGenerateSourcesWroteAndCorruptionIsRejectedSilently(@TempDir Path temp)
			throws Exception {
		Path out = temp.resolve("sources");
		Files.writeString(temp.resolve("notes.txt"), "fingerprint and manifest extraction check\n");
		Files.writeString(temp.resolve("seed.txt"), SMALL_SCHEMA);

		SourcesGenerator generator = SourcesGenerator.load(
				new ByteArrayInputStream(SMALL_SCHEMA.getBytes(StandardCharsets.UTF_8)));
		generator.generateSources(BASE_PACKAGE, out, false, false, false, false);

		Path manifestPath = out.resolve(BASE_PACKAGE.replace('.', '/')).resolve(Manifests.FILE_NAME);
		assertEquals(".datagen-manifest-v1", Manifests.FILE_NAME);
		assertTrue(Files.isRegularFile(manifestPath), "generateSources must write " + manifestPath);
		Map<Path, String> written = parseManifest(manifestPath);
		assertFalse(written.isEmpty(), () -> "manifest lists no file: " + manifestPath);

		Manifests.Manifest read = Manifests.read(manifestPath);
		assertNotNull(read, "a freshly written manifest must be readable");
		assertEquals(written.keySet(), read.files().keySet(), "same keys as the generator wrote");
		assertEquals(written, read.files(), "same digests as the generator wrote");
		assertEquals(read.files().size(), read.files().keySet().size());
		for (Map.Entry<Path, String> entry : read.files().entrySet()) {
			byte[] contents = Files.readAllBytes(out.resolve(entry.getKey()));
			assertEquals(FingerprintsManifestsTest.sha256Hex(contents), entry.getValue(),
					"digest of " + entry.getKey());
		}
		assertEquals(Manifests.fingerprint(read.generationFingerprint(), read.files()), read.fingerprint(),
				"self-consistent manifest fingerprint");
		// The manifest generation fingerprint is the same value the generator computes for these inputs.
		assertEquals(privateGenerationFingerprint(SMALL_SCHEMA, BASE_PACKAGE, false, false, false),
				read.generationFingerprint());

		// Current behaviour of a corrupt manifest: no exception, just "no usable manifest".
		List<String> lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
		Path other = temp.resolve("other").resolve(Manifests.FILE_NAME);
		Files.createDirectories(other.getParent());
		Files.writeString(other, String.join("\n", lines.subList(0, 5)) + "\n", StandardCharsets.UTF_8);
		assertNull(Manifests.read(other), "a truncated manifest must read as null");
		Files.writeString(other, String.join("\n", lines.subList(1, lines.size())) + "\n", StandardCharsets.UTF_8);
		assertNull(Manifests.read(other), "a manifest with a wrong header must read as null");
		assertNull(Manifests.read(other.getParent()), "a directory is not a manifest");
		assertNull(Manifests.read(other.getParent().resolve("absent")), "an absent manifest must read as null");

		// A truncated manifest must not throw out of generateSources; it regenerates a valid one.
		Files.writeString(manifestPath, String.join("\n", lines.subList(0, 5)) + "\n", StandardCharsets.UTF_8);
		generator.generateSources(BASE_PACKAGE, out, false, false, false, false);
		Manifests.Manifest regenerated = Manifests.read(manifestPath);
		assertNotNull(regenerated, "generateSources must repair a truncated manifest");
		assertEquals(written, regenerated.files(), "regenerated manifest lists the same files");
	}

	private static Map<Path, String> parseManifest(Path manifestPath) throws IOException {
		List<String> lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
		assertEquals("data-generator-manifest-v2", lines.get(0));
		assertEquals("files:", lines.get(3));
		var files = new LinkedHashMap<Path, String>();
		for (int index = 4; index < lines.size(); index++) {
			String line = lines.get(index);
			int separator = line.indexOf('\t');
			assertEquals(64, separator, line);
			files.put(Path.of(line.substring(separator + 1)), line.substring(0, separator));
		}
		return files;
	}

	private static String privateGenerationFingerprint(String yaml,
			String basePackageName,
			boolean generateOldSerializers,
			boolean binaryStrings,
			boolean vectorKernels) throws Exception {
		Method method = SourcesGenerator.class.getDeclaredMethod("generationFingerprint",
				String.class, boolean.class, boolean.class, boolean.class, byte[].class);
		method.setAccessible(true);
		return (String) method.invoke(null, basePackageName, generateOldSerializers, binaryStrings, vectorKernels,
				yaml.getBytes(StandardCharsets.UTF_8));
	}

	private static String sha256Hex(byte[] value) throws Exception {
		return java.util.HexFormat.of().formatHex(
				java.security.MessageDigest.getInstance("SHA-256").digest(value));
	}

	private static String resource(String name) throws IOException {
		try (InputStream input = FingerprintsManifestsTest.class.getResourceAsStream(name)) {
			if (input == null) throw new IOException("Missing test resource " + name);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
