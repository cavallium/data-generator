package it.cavallium.datagen.plugin.fuzz.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.datagen.plugin.ClassConfiguration;
import it.cavallium.datagen.plugin.ProjectionConfiguration;
import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;
import it.cavallium.datagen.plugin.VersionConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Fast, engine-agnostic checks for the compile harness and its fingerprint-keyed cache: identity, hit and miss
 * accounting, field-order sensitivity of the fingerprint, loud failure, and that closing and evicting actually
 * remove the cache directory.
 *
 * <p>No compile-time budget is asserted here: the plan puts the "mean compile &le; 60 ms" target in the
 * separate {@code HarnessBudgetTest} package.</p>
 */
@Tag("fast")
class CompileCacheTest {

	@BeforeAll
	static void startFromAnEmptyCacheRoot() {
		// Only this class uses target/fuzz-schemas, so clearing it is safe and it makes both the on-disk cache
		// and the "no directories accumulate" count deterministic. Loaders are closed BEFORE anything is
		// deleted, which is the ordering the plan requires.
		CompiledSchemas shared = CompiledSchemas.shared();
		shared.evictAll();
		try (Stream<Path> paths = Files.list(shared.cacheRoot())) {
			for (Path directory : paths.toList()) {
				delete(directory);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to clear " + shared.cacheRoot(), exception);
		}
	}

	@Test
	void sameFingerprintCompilesOnceAndIsServedFromTheCache() {
		CompiledSchemas cache = CompiledSchemas.shared();
		SchemaCompiler.CompileOptions options = options("org.example.fuzz.cache.identity");
		SourcesGeneratorConfiguration config = schema("CacheIdentityRoot", "id", "long", "label", "String");

		long hitsBefore = cache.hitCount();
		long missesBefore = cache.missCount();
		CompiledSchema first = cache.compile(config, options);
		// isCached() has to be sampled before the second call: both calls return the SAME instance, and a hit
		// marks it as cache-served.
		boolean firstWasCompiled = !first.isCached();
		CompiledSchema second = cache.compile(config, options);

		assertSame(first, second, "the same fingerprint must return the same instance");
		assertEquals(1, cache.missCount() - missesBefore, "exactly one compile");
		assertEquals(1, cache.hitCount() - hitsBefore, "exactly one cache hit");
		assertTrue(firstWasCompiled, "the first call generated and compiled the schema");
		assertTrue(second.isCached(), "the second call was served from the cache");
		assertTrue(first.generatedFileCount() > 0, "the generator produced sources");
		assertNotNull(first.loader());
		assertTrue(Files.isDirectory(first.cacheDir()), "the artifact lives in the fingerprint directory");
	}

	@Test
	void fieldOrderIsPartOfTheFingerprint() {
		SchemaCompiler.CompileOptions options = options("org.example.fuzz.cache.fieldorder");
		LinkedHashMap<String, String> forward = new LinkedHashMap<>();
		forward.put("id", "long");
		forward.put("label", "String");
		LinkedHashMap<String, String> reversed = new LinkedHashMap<>();
		reversed.put("label", "String");
		reversed.put("id", "long");

		CompiledSchema first = CompiledSchemas.shared().compile(schema("FieldOrderRoot", forward), options);
		CompiledSchema second = CompiledSchemas.shared().compile(schema("FieldOrderRoot", reversed), options);

		assertNotEquals(first.fingerprint(), second.fingerprint(),
				"field order is the wire contract, so it must change the fingerprint");
		assertTrue(first.generatedFileCount() > 0, "the first order compiled");
		assertTrue(second.generatedFileCount() > 0, "the second order compiled");
	}

	@Test
	void invalidProgramFailsLoudlyWithTheFingerprint() {
		SchemaCompiler.CompileOptions options = options("org.example.fuzz.cache.invalid");
		SourcesGeneratorConfiguration config = schemaWithBrokenProjection();
		String fingerprint = SchemaCompiler.fingerprint(config, options);

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> CompiledSchemas.shared().compile(config, options));

		assertTrue(failure.getMessage().contains(fingerprint),
				() -> "message must carry the fingerprint " + fingerprint + ", but was: " + failure.getMessage());
		assertTrue(failure.getMessage().contains("generatedFiles="),
				() -> "message must say how much was generated, but was: " + failure.getMessage());
		assertFalse(Files.isDirectory(options.cacheRoot().resolve(fingerprint)),
				"the partial cache directory must be deleted on failure");
	}

	@Test
	void closeIsIdempotentAndRemovesTheCacheDirectory() throws IOException {
		SchemaCompiler.CompileOptions options = options("org.example.fuzz.cache.close");
		CompiledSchema compiled = CompiledSchema.compile(schema("CloseRoot", "id", "long"), options);
		Path cacheDir = compiled.cacheDir();
		assertTrue(Files.isDirectory(cacheDir), "compiling creates the cache directory");
		assertTrue(Files.isDirectory(cacheDir.resolve("classes")), "javac output lives inside it");

		compiled.close();
		compiled.close();

		assertTrue(Files.notExists(cacheDir), "close deletes the cache directory recursively");
		assertTrue(compiled.isClosed());
	}

	@Test
	void evictionClosesTheLoaderAndDeletesItsDirectory() {
		CompiledSchemas bounded = CompiledSchemas.shared().withMaxEntries(1);
		CompiledSchema first = bounded.compile(schema("EvictFirstRoot", "id", "long"),
				options("org.example.fuzz.cache.evict"));
		Path firstDir = first.cacheDir();
		assertTrue(Files.isDirectory(firstDir));

		bounded.compile(schema("EvictSecondRoot", "id", "long"), options("org.example.fuzz.cache.evict"));

		assertEquals(1, bounded.size(), "the cache is bounded to one entry");
		assertTrue(first.isClosed(), "the evicted schema was closed");
		assertTrue(Files.notExists(firstDir), "the evicted schema's directory was deleted");
		assertEquals(1L, bounded.evictionCount());
	}

	@Test
	void aBoundedCampaignNeverLeaksCacheDirectories() {
		CompiledSchemas cache = CompiledSchemas.shared();
		int maxEntries = cache.maxEntries();
		SchemaCompiler.CompileOptions options = options("org.example.fuzz.cache.loop");

		for (int index = 0; index < 25; index++) {
			CompiledSchema compiled = cache.compile(schema("LoopRoot" + index, "id", "long"), options);
			assertTrue(compiled.generatedFileCount() > 0, "schema " + index + " compiled");
			assertTrue(cache.size() <= maxEntries,
					() -> "cache size " + cache.size() + " exceeded maxEntries " + maxEntries);
		}

		assertTrue(cache.size() <= maxEntries, "size stays within maxEntries");
		assertTrue(cache.cacheDirectories().size() <= maxEntries,
				() -> "cache directories accumulated: " + cache.cacheDirectories());
		assertFalse(cache.cacheDirectories().isEmpty(), "the cache root is the module's target directory");
	}

	// ------------------------------------------------------------------------------------------------
	// fixtures
	// ------------------------------------------------------------------------------------------------

	/** A distinct base package per test keeps the fingerprints, and therefore the cache entries, disjoint. */
	private static SchemaCompiler.CompileOptions options(String basePackage) {
		return new SchemaCompiler.CompileOptions(basePackage, false, false, false,
				CompiledSchemas.defaultCacheRoot(), Map.of());
	}

	private static SourcesGeneratorConfiguration schema(String typeName, String... fieldNameAndTypePairs) {
		LinkedHashMap<String, String> data = new LinkedHashMap<>();
		for (int index = 0; index + 1 < fieldNameAndTypePairs.length; index += 2) {
			data.put(fieldNameAndTypePairs[index], fieldNameAndTypePairs[index + 1]);
		}
		return schema(typeName, data);
	}

	private static SourcesGeneratorConfiguration schema(String typeName, LinkedHashMap<String, String> data) {
		ClassConfiguration type = new ClassConfiguration();
		type.data = data;
		LinkedHashMap<String, ClassConfiguration> baseTypes = new LinkedHashMap<>();
		baseTypes.put(typeName, type);
		LinkedHashMap<String, VersionConfiguration> versions = new LinkedHashMap<>();
		versions.put("v1", new VersionConfiguration());

		SourcesGeneratorConfiguration config = new SourcesGeneratorConfiguration();
		config.currentVersion = "v1";
		config.baseTypesData = baseTypes;
		config.versions = versions;
		return config;
	}

	/** A projection whose source path cannot resolve, so generation or {@code javac} has to fail. */
	private static SourcesGeneratorConfiguration schemaWithBrokenProjection() {
		SourcesGeneratorConfiguration config = schema("BrokenProjectionRoot", "id", "long");
		ProjectionConfiguration projection = new ProjectionConfiguration();
		projection.sourceType = "BrokenProjectionRoot";
		LinkedHashMap<String, String> fields = new LinkedHashMap<>();
		fields.put("nothing", "this.path.does.not.exist");
		projection.fields = fields;
		LinkedHashMap<String, ProjectionConfiguration> projections = new LinkedHashMap<>();
		projections.put("BrokenProjectionDigest", projection);
		config.projectionsData = projections;
		return config;
	}

	private static void delete(Path directory) {
		SchemaCompiler.deleteRecursively(directory);
	}
}
