package it.cavallium.datagen.plugin.fuzz.support;

import it.cavallium.datagen.plugin.Fingerprints;
import it.cavallium.datagen.plugin.Manifests;
import it.cavallium.datagen.plugin.SourcesGenerator;
import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * The stateless compile engine behind the plugin-side fuzz harness: schema configuration -> deterministic YAML
 * -> {@link Fingerprints fingerprint} -> cache directory -> source generation -> {@code javac}.
 *
 * <h2>Cache layout</h2>
 * <p>One directory per fingerprint, {@code cacheRoot/<fingerprint>}, holding {@code sources/} (the generated
 * sources plus the generator's own {@value Manifests#FILE_NAME}), {@code classes/} (the {@code javac} output)
 * and {@value #MARKER_NAME}, a marker that records the fingerprint, the caller-supplied extra sources and a
 * digest of the generator's own bytecode. A marker that does not match every one of those forces a
 * regeneration, so a stale directory left over by an earlier build can never be mistaken for a hit.</p>
 *
 * <h2>No silent failures</h2>
 * <p>The pre-existing test helper ({@code ModelCompiler.compile}-style, and the {@code compileGeneratedSources}
 * copies in {@code SourcesGeneratorTest} / {@code AdversarialEvolutionTest}) either asserted or returned
 * {@code null} when {@code javac} failed, which hides the diagnostics. This engine never does: generation or
 * compilation failure throws {@link IllegalStateException} carrying the fingerprint, the number of generated
 * files and the first {@value #MAX_REPORTED_DIAGNOSTICS} {@code javac} diagnostics (kind, position, message),
 * and the partially written cache directory is deleted before the exception reaches the caller.</p>
 *
 * <h2>Loader ownership</h2>
 * <p>{@link #compileLoading(SourcesGeneratorConfiguration, CompileOptions)} returns an open
 * {@link URLClassLoader}: the caller owns it and must close it. {@link #compile(SourcesGeneratorConfiguration,
 * CompileOptions)} is the "does it build?" convenience and closes the loader it created, because it hands no
 * loader out.</p>
 */
public final class SchemaCompiler {

	/** Name of the harness cache marker written next to the generated tree. */
	public static final String MARKER_NAME = ".fuzz-cache-marker";

	/** Maximum number of {@code javac} diagnostics quoted by a failure message. */
	public static final int MAX_REPORTED_DIAGNOSTICS = 20;

	/** Sub-directory of a cache entry that holds the generated sources. */
	public static final String SOURCES_DIRECTORY = "sources";

	/** Sub-directory of a cache entry that holds the {@code javac} output. */
	public static final String CLASSES_DIRECTORY = "classes";

	/** Default base package of a fuzz schema. */
	public static final String DEFAULT_BASE_PACKAGE = "org.example.fuzz";

	private static final AtomicLong TEMPORARY_SUFFIX = new AtomicLong();

	private SchemaCompiler() {
	}

	/**
	 * Everything that influences the generated program except the schema itself.
	 *
	 * @param basePackage           base package passed to {@link SourcesGenerator#generateSources}
	 * @param generateOldSerializers whether historical serializers may write values
	 * @param binaryStrings          use binary strings
	 * @param vectorKernels          generate vector kernels (adds {@code --add-modules jdk.incubator.vector})
	 * @param cacheRoot              directory holding one sub-directory per fingerprint
	 * @param extraSources           extra {@code .java} sources the caller wants written into the generated
	 *                               tree before {@code javac} runs, keyed by path relative to the sources
	 *                               directory (the {@code AdversarialEvolutionTest.writeOpaqueBoundaryUpgrader}
	 *                               pattern). Extra sources are NOT part of {@link Fingerprints}, so they are
	 *                               recorded in the cache marker instead.
	 */
	public record CompileOptions(String basePackage,
			boolean generateOldSerializers,
			boolean binaryStrings,
			boolean vectorKernels,
			Path cacheRoot,
			Map<String, String> extraSources) {

		public CompileOptions {
			Objects.requireNonNull(basePackage, "basePackage");
			Objects.requireNonNull(cacheRoot, "cacheRoot");
			extraSources = extraSources == null ? Map.of() : Map.copyOf(extraSources);
		}

		/** The harness defaults: {@value #DEFAULT_BASE_PACKAGE}, no old serializers, UTF-8 strings, no vectors. */
		public static CompileOptions defaults(Path cacheRoot) {
			return new CompileOptions(DEFAULT_BASE_PACKAGE, false, false, false, cacheRoot, Map.of());
		}
	}

	/**
	 * The measurement part of a compile. Deliberately free of any resource handle: the class loader lives in
	 * {@link Loaded}, because a compiled schema is only useful while its loader stays open.
	 *
	 * @param fingerprint        {@link Fingerprints#ofSchema} of the emitted schema
	 * @param generatedFileCount number of {@code .java} files the generator produced (extras excluded)
	 * @param generateMillis     wall-clock generation time, {@code 0} for a cache hit
	 * @param compileMillis      wall-clock {@code javac} time, {@code 0} for a cache hit
	 * @param diagnostics        the first {@value #MAX_REPORTED_DIAGNOSTICS} non-error {@code javac}
	 *                           diagnostics (warnings and notes) of this compile
	 */
	public record CompiledCompile(String fingerprint,
			int generatedFileCount,
			long generateMillis,
			long compileMillis,
			List<String> diagnostics) {

		public CompiledCompile {
			Objects.requireNonNull(fingerprint, "fingerprint");
			diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
		}
	}

	/**
	 * A compile plus the open class loader over its {@code classes} directory. The owner of this record owns
	 * the loader; {@link #close()} closes only the loader, never the cache directory, because deleting the
	 * directory of a schema that is still cached is wrong.
	 */
	public record Loaded(CompiledCompile compile, Path entryDir, boolean cacheHit, URLClassLoader loader)
			implements AutoCloseable {

		public Loaded {
			Objects.requireNonNull(compile, "compile");
			Objects.requireNonNull(entryDir, "entryDir");
			Objects.requireNonNull(loader, "loader");
		}

		@Override
		public void close() throws IOException {
			loader.close();
		}
	}

	/** The fingerprint of {@code config} under {@code options}, without touching the filesystem. */
	public static String fingerprint(SourcesGeneratorConfiguration config, CompileOptions options) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(options, "options");
		return Fingerprints.ofSchema(yamlBytes(config), options.basePackage(), options.generateOldSerializers(),
				options.binaryStrings(), options.vectorKernels());
	}

	/**
	 * Generates and compiles {@code config} into {@code options.cacheRoot()}/{@code <fingerprint>} and returns
	 * the measurement only. Any class loader created here is closed before returning: use
	 * {@link #compileLoading} when the compiled classes must stay reachable.
	 */
	public static CompiledCompile compile(SourcesGeneratorConfiguration config, CompileOptions options) {
		Loaded loaded = compileLoading(config, options);
		try {
			return loaded.compile();
		} finally {
			try {
				loaded.close();
			} catch (IOException exception) {
				throw new IllegalStateException("Unable to close the loader of fingerprint="
						+ loaded.compile().fingerprint(), exception);
			}
		}
	}

	/**
	 * Generates and compiles {@code config}, or reuses the cached artifact of the same fingerprint, and returns
	 * the result together with an <em>open</em> {@link URLClassLoader} over the compiled classes.
	 *
	 * <p>The loader is intentionally not closed here: the compiled schema's classes must remain usable by the
	 * owner ({@link CompiledSchema}), which closes it on eviction. Deleting the cache directory of an open
	 * loader is also unsafe, so this method does not delete anything on success either.</p>
	 *
	 * @throws IllegalStateException when generation or {@code javac} fails; the message contains the
	 *                               fingerprint, the generated file count and the first
	 *                               {@value #MAX_REPORTED_DIAGNOSTICS} diagnostics, and the partial cache
	 *                               directory has already been deleted when this exception is thrown
	 */
	public static Loaded compileLoading(SourcesGeneratorConfiguration config, CompileOptions options) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(options, "options");
		byte[] yaml = yamlBytes(config);
		String fingerprint = Fingerprints.ofSchema(yaml, options.basePackage(), options.generateOldSerializers(),
				options.binaryStrings(), options.vectorKernels());
		Path entryDir = options.cacheRoot().resolve(fingerprint);
		Path sourcesDir = entryDir.resolve(SOURCES_DIRECTORY);
		Path classesDir = entryDir.resolve(CLASSES_DIRECTORY);
		String marker = marker(fingerprint, options);
		try {
			Files.createDirectories(options.cacheRoot());
			if (markerMatches(entryDir, marker)) {
				return new Loaded(new CompiledCompile(fingerprint, countJavaFiles(sourcesDir), 0L, 0L, List.of()),
						entryDir, true, newLoader(classesDir));
			}
			if (Files.exists(entryDir)) {
				// stale entry (the fingerprint matches but the marker does not — e.g. different extra
				// sources): publish() would refuse to overwrite it and the fresh tree would be discarded,
				// silently serving the stale classes. Drop the stale entry first.
				deleteRecursively(entryDir);
			}
			return generateCompileAndLoad(fingerprint, yaml, options, entryDir, sourcesDir, classesDir, marker);
		} catch (IOException exception) {
			deleteRecursively(entryDir);
			throw new IllegalStateException("Failed to compile fuzz schema fingerprint=" + fingerprint
					+ " into " + entryDir, exception);
		} catch (RuntimeException exception) {
			deleteRecursively(entryDir);
			throw exception;
		}
	}

	private static Loaded generateCompileAndLoad(String fingerprint,
			byte[] yaml,
			CompileOptions options,
			Path entryDir,
			Path sourcesDir,
			Path classesDir,
			String marker) throws IOException {
		// Build in a sibling temporary directory and move it into place, so that a concurrent compile of the
		// same fingerprint (another thread, or another JVM sharing the module's target directory) can never
		// observe a half-written entry.
		Path temporaryDir = options.cacheRoot().resolve(fingerprint + ".tmp-" + TEMPORARY_SUFFIX.incrementAndGet());
		Path temporarySources = temporaryDir.resolve(SOURCES_DIRECTORY);
		Path temporaryClasses = temporaryDir.resolve(CLASSES_DIRECTORY);
		Files.createDirectories(temporarySources);
		Files.createDirectories(temporaryClasses);
		int generatedFileCount;
		long generateMillis;
		try {
			long started = System.nanoTime();
			try {
				SourcesGenerator.load(new ByteArrayInputStream(yaml)).generateSources(options.basePackage(),
						temporarySources, true, options.generateOldSerializers(), options.binaryStrings(),
						options.vectorKernels());
			} catch (RuntimeException exception) {
				generatedFileCount = countJavaFiles(temporarySources);
				throw new IllegalStateException("Fuzz schema generation failed: fingerprint=" + fingerprint
						+ ", generatedFiles=" + generatedFileCount + ", basePackage=" + options.basePackage()
						+ ", cause=" + exception, exception);
			} catch (IOException exception) {
				generatedFileCount = countJavaFiles(temporarySources);
				throw new IllegalStateException("Fuzz schema generation failed: fingerprint=" + fingerprint
						+ ", generatedFiles=" + generatedFileCount + ", basePackage=" + options.basePackage()
						+ ", cause=" + exception, exception);
			}
			generateMillis = (System.nanoTime() - started) / 1_000_000L;
			generatedFileCount = countJavaFiles(temporarySources);
			for (Map.Entry<String, String> extra : options.extraSources().entrySet()) {
				Path extraPath = temporarySources.resolve(extra.getKey());
				Files.createDirectories(extraPath.getParent());
				Files.writeString(extraPath, extra.getValue(), StandardCharsets.UTF_8);
			}
			long compileStarted = System.nanoTime();
			List<String> diagnostics = compile(fingerprint, generatedFileCount, temporarySources, temporaryClasses,
					options.vectorKernels());
			long compileMillis = (System.nanoTime() - compileStarted) / 1_000_000L;
			Files.writeString(temporaryDir.resolve(MARKER_NAME), marker, StandardCharsets.UTF_8);
			if (!publish(temporaryDir, entryDir)) {
				// Another writer published the same fingerprint first; their entry is complete and equivalent,
				// so discard ours and serve theirs.
				deleteRecursively(temporaryDir);
				return new Loaded(new CompiledCompile(fingerprint, countJavaFiles(sourcesDir), generateMillis,
						compileMillis, diagnostics), entryDir, true, newLoader(classesDir));
			}
			return new Loaded(new CompiledCompile(fingerprint, generatedFileCount, generateMillis, compileMillis,
					diagnostics), entryDir, false, newLoader(classesDir));
		} catch (RuntimeException | IOException exception) {
			deleteRecursively(temporaryDir);
			throw exception;
		}
	}

	/** Moves {@code temporaryDir} to {@code entryDir}; {@code false} when the destination already exists. */
	private static boolean publish(Path temporaryDir, Path entryDir) throws IOException {
		try {
			Files.move(temporaryDir, entryDir, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (FileAlreadyExistsException exception) {
			return false;
		}
	}

	/**
	 * Runs {@code javac} over every generated source. Never silent: a failed task throws
	 * {@link IllegalStateException} with the fingerprint, the file count and the first diagnostics.
	 *
	 * @return the non-error diagnostics ({@code javac} warnings and notes) of a successful compile
	 */
	private static List<String> compile(String fingerprint,
			int generatedFileCount,
			Path sourcesDir,
			Path classesDir,
			boolean vectorKernels) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new IllegalStateException("No system Java compiler is available (a JDK is required): fingerprint="
					+ fingerprint);
		}
		List<Path> files = new ArrayList<>();
		try (var paths = Files.walk(sourcesDir)) {
			paths.filter(path -> path.toString().endsWith(".java")).sorted().forEach(files::add);
		}
		DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
		List<String> arguments = new ArrayList<>(List.of("--release", "25", "-parameters", "-classpath",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				"-d", classesDir.toString()));
		if (vectorKernels) {
			// Generated vector kernels reference jdk.incubator.vector, which is not resolved by default.
			arguments.add("--add-modules");
			arguments.add("jdk.incubator.vector");
		}
		int status;
		try (StandardJavaFileManager fileManager =
				compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
			var units = fileManager.getJavaFileObjectsFromPaths(files);
			status = Boolean.TRUE.equals(compiler.getTask(null, fileManager, collector, arguments, null, units).call())
					? 0 : 1;
		}
		List<Diagnostic<? extends JavaFileObject>> diagnostics = collector.getDiagnostics();
		if (status != 0) {
			List<String> reported = new ArrayList<>();
			for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
				if (reported.size() >= MAX_REPORTED_DIAGNOSTICS) {
					break;
				}
				reported.add(describe(diagnostic));
			}
			throw new IllegalStateException("Fuzz schema javac failed: fingerprint=" + fingerprint
					+ ", generatedFiles=" + generatedFileCount + ", sources=" + sourcesDir
					+ ", diagnostics=" + diagnostics.size() + ", first=" + reported,
					null /* cause: the diagnostics are the explanation */);
		}
		List<String> notes = new ArrayList<>();
		for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
			if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
				notes.add(describe(diagnostic));
			}
		}
		return List.copyOf(notes);
	}

	private static String describe(Diagnostic<? extends JavaFileObject> diagnostic) {
		return diagnostic.getKind() + "@" + diagnostic.getSource().getName() + ":" + diagnostic.getLineNumber()
				+ ":" + diagnostic.getColumnNumber() + " " + diagnostic.getMessage(null);
	}

	private static URLClassLoader newLoader(Path classesDir) throws IOException {
		if (!Files.isDirectory(classesDir)) {
			throw new IllegalStateException("Compiled classes directory is missing: " + classesDir);
		}
		return new URLClassLoader(new URL[] {classesDir.toUri().toURL()}, SchemaCompiler.class.getClassLoader());
	}

	/** The exact YAML bytes the generator would fingerprint, i.e. the input of {@link #fingerprint}. */
	private static byte[] yamlBytes(SourcesGeneratorConfiguration config) {
		return SchemaYamlEmitter.emit(config).getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Marker content: fingerprint, extra sources, and a digest of this harness's own generator class, so that
	 * a directory produced by a different version of the generator is never treated as a hit.
	 */
	private static String marker(String fingerprint, CompileOptions options) {
		StringBuilder builder = new StringBuilder();
		builder.append("fingerprint=").append(fingerprint).append('\n');
		builder.append("generator=").append(generatorDigest()).append('\n');
		builder.append("javaCompiler=").append(System.getProperty("java.version", "unknown")).append('\n');
		List<String> keys = options.extraSources().keySet().stream().sorted().toList();
		builder.append("extras=").append(keys.size()).append('\n');
		for (String key : keys) {
			builder.append('+').append(key).append('=')
					.append(sha256(options.extraSources().get(key).getBytes(StandardCharsets.UTF_8)))
					.append('\n');
		}
		return builder.toString();
	}

	private static volatile String generatorDigest;

	/** SHA-256 of {@link SourcesGenerator}'s own class file, i.e. of the generator that produced the cache. */
	private static String generatorDigest() {
		String digest = generatorDigest;
		if (digest == null) {
			String value;
			try (var input = SourcesGenerator.class.getResourceAsStream("/"
					+ SourcesGenerator.class.getName().replace('.', '/') + ".class")) {
				value = input == null ? "unknown" : sha256(input.readAllBytes());
			} catch (IOException exception) {
				value = "unknown";
			}
			generatorDigest = value;
			digest = value;
		}
		return digest;
	}

	private static boolean markerMatches(Path entryDir, String marker) {
		Path markerPath = entryDir.resolve(MARKER_NAME);
		if (!Files.isRegularFile(markerPath)) {
			return false;
		}
		try {
			if (!Files.readString(markerPath, StandardCharsets.UTF_8).equals(marker)) {
				return false;
			}
			Path classesDir = entryDir.resolve(CLASSES_DIRECTORY);
			if (!Files.isDirectory(classesDir)) {
				return false;
			}
			try (var paths = Files.walk(classesDir)) {
				return paths.filter(path -> path.toString().endsWith(".class")).findAny().isPresent();
			}
		} catch (IOException exception) {
			return false;
		}
	}

	private static int countJavaFiles(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		try (var paths = Files.walk(dir)) {
			return (int) paths.filter(path -> path.toString().endsWith(".java")).count();
		}
	}

	/**
	 * Recursively deletes {@code dir}, deepest first. Missing directories are not an error and individual
	 * failures are swallowed: a leftover directory under {@code target/} is never worth failing a run over,
	 * while the plan's hazard is the opposite one — blocking on a directory an open loader still holds.
	 */
	public static void deleteRecursively(Path dir) {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (var paths = Files.walk(dir)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// Best-effort, see above.
				}
			}
		} catch (IOException ignored) {
			// Same policy as above.
		}
	}

	/** SHA-256 of {@code value} as lower-case hex. */
	static String sha256(byte[] value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError("Every Java implementation must provide SHA-256", exception);
		}
	}
}
