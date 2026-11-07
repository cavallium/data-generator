package it.cavallium.datagen.plugin.fuzz.support;

import it.cavallium.datagen.plugin.DataModel;
import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One compiled fuzz schema: the configuration it came from, the artifact {@link SchemaCompiler} produced for
 * it, and the class loader over that artifact.
 *
 * <h2>Ownership</h2>
 * <p>An instance owns its {@link URLClassLoader} and its cache directory. {@link #close()} closes the loader
 * and then deletes the directory; it is idempotent and safe to call from a {@code try}-with-resources. The
 * loader is deliberately <em>not</em> closed inside {@link #compile}: compiled classes have to stay reachable
 * for the owner of this object. That is also why {@code close()} must never be nested in a try-with-resources
 * around the compile step itself — the plan's measured hazard is that deleting a directory while its loader is
 * open blocks, so the order here is always close-then-delete.</p>
 *
 * <p>{@link CompiledSchemas} hands the same instance to every caller that asks for the same fingerprint, so a
 * caller that obtained the instance from a cache must not close it: eviction closes it.</p>
 */
public final class CompiledSchema implements AutoCloseable {

	private final SourcesGeneratorConfiguration config;
	private final SchemaCompiler.CompileOptions options;
	private final String fingerprint;
	private final Path cacheDir;
	private final URLClassLoader loader;
	private final int generatedFileCount;
	private final long compileMillis;
	private final List<String> diagnostics;
	private boolean cached;
	private boolean closed;
	private DataModel dataModel;
	private GeneratedModel generatedModel;
	private int[] versionIds;
	private List<String> typeNames;

	private CompiledSchema(SourcesGeneratorConfiguration config,
			SchemaCompiler.CompileOptions options,
			SchemaCompiler.Loaded loaded) {
		this.config = Objects.requireNonNull(config, "config");
		this.options = Objects.requireNonNull(options, "options");
		this.fingerprint = loaded.compile().fingerprint();
		this.cacheDir = loaded.entryDir();
		this.loader = loaded.loader();
		this.generatedFileCount = loaded.compile().generatedFileCount();
		this.compileMillis = loaded.compile().compileMillis();
		this.diagnostics = loaded.compile().diagnostics();
		this.cached = loaded.cacheHit();
	}

	/**
	 * Compiles {@code config} with the harness defaults
	 * ({@link SchemaCompiler.CompileOptions#defaults(Path)} over {@link CompiledSchemas#defaultCacheRoot()}).
	 */
	public static CompiledSchema compile(SourcesGeneratorConfiguration config) {
		return compile(config, SchemaCompiler.CompileOptions.defaults(CompiledSchemas.defaultCacheRoot()));
	}

	/**
	 * Compiles {@code config} and returns the owned result. The class loader stays open: this method must not
	 * be used inside a try-with-resources that also closes the schema.
	 *
	 * @throws IllegalStateException when generation or {@code javac} fails, carrying the fingerprint and the
	 *                               {@code javac} diagnostics (see {@link SchemaCompiler#compileLoading})
	 */
	public static CompiledSchema compile(SourcesGeneratorConfiguration config, SchemaCompiler.CompileOptions opts) {
		return new CompiledSchema(config, opts, SchemaCompiler.compileLoading(config, opts));
	}

	/** The fingerprint {@link SchemaCompiler} keyed this artifact by. */
	public String fingerprint() {
		return fingerprint;
	}

	/** The configuration this schema was compiled from (the caller's instance, never a copy). */
	public SourcesGeneratorConfiguration config() {
		return config;
	}

	/** The options this schema was compiled with, including the base package and the string mode. */
	public SchemaCompiler.CompileOptions options() {
		return options;
	}

	/** The cache directory of this schema, {@code cacheRoot/<fingerprint>}. */
	public Path cacheDir() {
		return cacheDir;
	}

	/**
	 * The {@link DataModel} of this schema, built from the very same configuration and the same
	 * {@code binaryStrings} flag that were used to emit and generate it. Memoized.
	 */
	public DataModel model() {
		DataModel model = dataModel;
		if (model == null) {
			model = config.buildDataModel(options.binaryStrings());
			dataModel = model;
		}
		return model;
	}

	/** The reflective facade over the generated codecs, readers and upgraders of this schema. Memoized. */
	public GeneratedModel generatedModel() {
		GeneratedModel model = generatedModel;
		if (model == null) {
			model = new GeneratedModel(this);
			generatedModel = model;
		}
		return model;
	}

	/** The class loader over the compiled classes. It is open until {@link #close()} runs. */
	public ClassLoader loader() {
		return loader;
	}

	/** The version id the generated {@code CurrentVersion} reads and writes as latest. */
	public int currentVersionId() {
		try {
			return loader.loadClass(rootPackage() + ".current.Version").getField("VERSION").getInt(null);
		} catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException exception) {
			throw lookupFailed("current.Version.VERSION", exception);
		}
	}

	/** Every version id present in the compiled artifact, ascending, read from the generated {@code Versions}. */
	public int[] allVersionIds() {
		int[] ids = versionIds;
		if (ids == null) {
			Object[] versions = versionsArray();
			ids = new int[versions.length];
			for (int index = 0; index < versions.length; index++) {
				ids[index] = invokeVersionGetter(versions[index]);
			}
			Arrays.sort(ids);
			versionIds = ids;
		}
		return ids.clone();
	}

	/** Number of versions in the compiled artifact. */
	public int versionCount() {
		return allVersionIds().length;
	}

	/**
	 * The names of every base type of the compiled schema, in generated {@code BaseType} declaration order.
	 * These are exactly the constants {@code CurrentVersion.read} and {@code getCodec} accept.
	 */
	public List<String> typeNames() {
		List<String> names = typeNames;
		if (names == null) {
			names = Arrays.stream(baseTypeConstants()).map(name -> ((Enum<?>) name).name()).toList();
			typeNames = names;
		}
		return names;
	}

	/** The generated data class of the current version, e.g. {@code <base>.current.data.Root}. */
	public Class<?> recordClass(String simpleName) {
		Class<?> type = load(rootPackage() + ".current.data." + simpleName);
		if (type.isInterface()) {
			throw lookupFailed("current.data." + simpleName + " (is an interface, not a record class)", null);
		}
		return type;
	}

	/** The generated sealed union interface, e.g. {@code <base>.current.data.Choice}. */
	public Class<?> unionInterface(String simpleName) {
		Class<?> type = load(rootPackage() + ".current.data." + simpleName);
		if (!type.isInterface()) {
			throw lookupFailed("current.data." + simpleName + " (is not an interface)", null);
		}
		return type;
	}

	/** The generated {@code BaseType} enum class. */
	public Class<?> baseTypeClass() {
		return load(rootPackage() + ".BaseType");
	}

	/** The generated {@code BaseType} constants, in declaration order. */
	public Object[] baseTypeConstants() {
		Class<?> type = baseTypeClass();
		Object[] constants = type.getEnumConstants();
		if (constants == null) {
			throw lookupFailed("BaseType enum constants", null);
		}
		return constants;
	}

	/** The generated root package, i.e. the base package the schema was generated into. */
	public String rootPackage() {
		return options.basePackage();
	}

	/** Number of {@code .java} files the generator produced for this schema (caller extras excluded). */
	public int generatedFileCount() {
		return generatedFileCount;
	}

	/** Wall-clock {@code javac} time of this artifact, {@code 0} when it came from a cache. */
	public long compileMillis() {
		return compileMillis;
	}

	/** The non-error {@code javac} diagnostics of this artifact, if any. */
	public List<String> diagnostics() {
		return diagnostics;
	}

	/**
	 * Whether this schema came from a cache instead of being generated and compiled right now: either the
	 * in-memory cache of {@link CompiledSchemas} or a cache directory of an earlier run.
	 */
	public synchronized boolean isCached() {
		return cached;
	}

	/** Marks this schema as cache-served; used by {@link CompiledSchemas} when it returns a cached instance. */
	synchronized void markCached() {
		this.cached = true;
	}

	/**
	 * Closes the class loader and deletes the cache directory. Idempotent, and ordering-safe: the loader is
	 * always closed before anything is deleted.
	 */
	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			loader.close();
		} catch (IOException exception) {
			// A loader that refuses to close still must not keep the cache directory alive forever.
		}
		SchemaCompiler.deleteRecursively(cacheDir);
	}

	/** True once {@link #close()} has run. */
	public synchronized boolean isClosed() {
		return closed;
	}

	Object[] versionsArray() {
		try {
			Class<?> versions = loader.loadClass(rootPackage() + ".Versions");
			Field field = versions.getField("VERSIONS");
			Object value = field.get(null);
			List<Object> elements = new ArrayList<>();
			for (int index = 0; index < Array.getLength(value); index++) {
				elements.add(Array.get(value, index));
			}
			return elements.toArray();
		} catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException exception) {
			throw lookupFailed("Versions.VERSIONS", exception);
		}
	}

	private int invokeVersionGetter(Object versionInstance) {
		try {
			Method method = versionInstance.getClass().getMethod("getVersion");
			return (int) method.invoke(versionInstance);
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
			throw lookupFailed("IVersion.getVersion", exception);
		}
	}

	Class<?> load(String binaryName) {
		try {
			return loader.loadClass(binaryName);
		} catch (ClassNotFoundException exception) {
			throw lookupFailed(binaryName, exception);
		}
	}

	private IllegalStateException lookupFailed(String lookup, Throwable cause) {
		IllegalStateException exception = new IllegalStateException("Lookup failed in compiled fuzz schema: "
				+ "fingerprint=" + fingerprint + ", lookup=" + lookup + ", compiledTypes=" + generatedFileCount, cause);
		return exception;
	}

	/** Sorts by fingerprint so that callers can print deterministic cache dumps. */
	static Comparator<CompiledSchema> byFingerprint() {
		return Comparator.comparing(CompiledSchema::fingerprint);
	}
}
