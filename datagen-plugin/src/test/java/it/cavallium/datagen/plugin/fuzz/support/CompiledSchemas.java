package it.cavallium.datagen.plugin.fuzz.support;

import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fingerprint-keyed LRU cache of {@link CompiledSchema} instances.
 *
 * <h2>Identity</h2>
 * <p>The cache is keyed by {@link SchemaCompiler#fingerprint}, so asking twice for the same schema under the
 * same options returns the <em>same</em> {@link CompiledSchema} instance: no second generation, no second
 * {@code javac}, no second class loader. Because the instance is shared, callers must not close it — eviction
 * does. {@link #compile} counts a hit whenever the instance was already in the map.</p>
 *
 * <h2>Eviction</h2>
 * <p>Least-recently-used. An evicted schema is closed, which closes its {@link URLClassLoader} and deletes its
 * cache directory; that is the only place a compiled schema is ever closed, and it is why the cache root
 * ({@code <module>/target/fuzz-schemas}) stays bounded even in a long campaign.</p>
 *
 * <h2>Concurrency</h2>
 * <p>{@code javac} runs <em>outside</em> the cache lock, so two different schemas compile in parallel and the
 * lock only ever guards the map itself. Compilations are serialized <em>per fingerprint</em> by a slot lock,
 * which keeps two threads from generating the same schema twice at once. Generation is NOT assumed to be
 * globally thread-safe (it writes content-hashed directories and a manifest), so the safety argument is
 * explicit rather than incidental: every fingerprint writes only into its own temporary directory, which is
 * then moved into place atomically (see {@link SchemaCompiler}), and the per-fingerprint slot makes the
 * identical-fingerprint case single-threaded. Two compilations therefore never share a directory, and no
 * lock is ever held across generation or {@code javac}.</p>
 */
public final class CompiledSchemas {

	/**
	 * Cache capacity of the QUICK tier. The codec spread is 26 entries (tier + 23 features + minimal +
	 * maximal) and the evolution spread adds two old-serializer variants, so 32 keeps a QUICK run
	 * eviction-free; 16 thrashed and made the timed budget test flaky.
	 */
	public static final int QUICK_ENTRIES = 32;
	/** Cache capacity of the DEFAULT tier (what {@code mvn test} runs). */
	public static final int DEFAULT_ENTRIES = 64;
	/** Cache capacity of the DEEP and NIGHTLY tiers. */
	public static final int DEEP_ENTRIES = 256;

	private static final String CACHE_ROOT_DIRECTORY_NAME = "fuzz-schemas";
	private static final String TIER_CLASS = "it.cavallium.datagen.fuzz.FuzzTiers";
	private static final List<String> TIER_NAMES = List.of("QUICK", "DEFAULT", "DEEP", "NIGHTLY");

	private final Path cacheRoot;
	private final int maxEntries;
	/** access-order, so the first entry of the iteration is the least recently used one. */
	private final Map<String, CompiledSchema> entries = new LinkedHashMap<>(32, 0.75f, true);
	private final Map<String, Object> slots = new ConcurrentHashMap<>();
	private long hitCount;
	private long missCount;
	private long evictionCount;

	private CompiledSchemas(Path cacheRoot, int maxEntries) {
		Objects.requireNonNull(cacheRoot, "cacheRoot");
		if (maxEntries < 1) {
			throw new IllegalArgumentException("maxEntries must be >= 1, but was " + maxEntries);
		}
		this.cacheRoot = cacheRoot;
		this.maxEntries = maxEntries;
		try {
			Files.createDirectories(cacheRoot);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create the fuzz schema cache root " + cacheRoot, exception);
		}
	}

	private static final class Holder {
		private static final CompiledSchemas SHARED = new CompiledSchemas(defaultCacheRoot(), tierEntries());
	}

	/**
	 * The process-wide cache, sized by the fuzz tier (QUICK {@value #QUICK_ENTRIES},
	 * DEFAULT {@value #DEFAULT_ENTRIES}, DEEP and NIGHTLY {@value #DEEP_ENTRIES}). Surefire fork reuse keeps it
	 * warm across test classes, which is the whole point: {@code javac} is the cost driver.
	 */
	public static CompiledSchemas shared() {
		return Holder.SHARED;
	}

	/**
	 * A sibling cache with an explicit capacity. It shares this cache's root directory, so it can reuse the
	 * on-disk artifacts, but it keeps its own entries, counters and eviction.
	 */
	public CompiledSchemas withMaxEntries(int maxEntries) {
		return new CompiledSchemas(cacheRoot, maxEntries);
	}

	/** A cache rooted at the module's {@code target/fuzz-schemas} with an explicit capacity. */
	public static CompiledSchemas create(int maxEntries) {
		return new CompiledSchemas(defaultCacheRoot(), maxEntries);
	}

	/**
	 * The fingerprint-keyed compile. Same fingerprint in this instance -> same {@link CompiledSchema} instance,
	 * without regenerating or recompiling.
	 *
	 * @throws IllegalStateException when generation or {@code javac} fails; failures are not cached, and
	 *                               {@link SchemaCompiler} has already deleted the partial cache directory
	 */
	public CompiledSchema compile(SourcesGeneratorConfiguration config, SchemaCompiler.CompileOptions opts) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(opts, "options");
		String fingerprint = SchemaCompiler.fingerprint(config, opts);
		CompiledSchema immediate = cached(fingerprint);
		if (immediate != null) {
			return immediate;
		}
		Object slot = slots.computeIfAbsent(fingerprint, key -> new Object());
		synchronized (slot) {
			CompiledSchema raced = cached(fingerprint);
			if (raced != null) {
				// Another thread finished this compilation while the current thread waited for the slot.
				return raced;
			}
			// Expensive and lock-free: generation + javac, for this fingerprint only.
			CompiledSchema compiled = CompiledSchema.compile(config, opts);
			List<CompiledSchema> evicted;
			synchronized (this) {
				missCount++;
				entries.put(fingerprint, compiled);
				evicted = evictOverflowLocked();
			}
			slots.remove(fingerprint, slot);
			closeAll(evicted);
			return compiled;
		}
	}

	/** Closes and deletes every cached schema, emptying the cache. */
	public void evictAll() {
		List<CompiledSchema> all;
		synchronized (this) {
			all = new ArrayList<>(entries.values());
			entries.clear();
			evictionCount += all.size();
		}
		slots.clear();
		closeAll(all);
	}

	/** Number of schemas currently cached. */
	public synchronized int size() {
		return entries.size();
	}

	/** Requests answered by this cache instance. */
	public synchronized long hitCount() {
		return hitCount;
	}

	/** Requests that caused generation plus {@code javac} (or a wait for another thread doing exactly that). */
	public synchronized long missCount() {
		return missCount;
	}

	/** Number of schemas closed and deleted by eviction or {@link #evictAll()}. */
	public synchronized long evictionCount() {
		return evictionCount;
	}

	/** How many schemas this cache keeps before it evicts the least recently used one. */
	public synchronized int maxEntries() {
		return maxEntries;
	}

	/** The directory holding one sub-directory per cached fingerprint. */
	public Path cacheRoot() {
		return cacheRoot;
	}

	/** A snapshot of this cache's counters. */
	public Stats stats() {
		synchronized (this) {
			return new Stats(entries.size(), maxEntries, hitCount, missCount, evictionCount, cacheRoot);
		}
	}

	/** Immutable view of a cache's state, for failure messages and budget assertions. */
	public record Stats(int size,
			int maxEntries,
			long hitCount,
			long missCount,
			long evictionCount,
			Path cacheRoot) {
	}

	/**
	 * The module's {@code target/fuzz-schemas} directory — never the repository root, so a campaign cannot
	 * leave hundreds of directories next to the poms. Resolved from where this class was loaded from
	 * ({@code <module>/target/test-classes/...}); falls back to {@code user.dir/target/fuzz-schemas}.
	 */
	public static Path defaultCacheRoot() {
		try {
			Path origin = Path.of(CompiledSchemas.class.getProtectionDomain().getCodeSource()
					.getLocation().toURI());
			for (Path parent = origin; parent != null; parent = parent.getParent()) {
				if ("target".equals(parent.getFileName().toString())) {
					return parent.resolve(CACHE_ROOT_DIRECTORY_NAME);
				}
			}
		} catch (Exception exception) {
			// Fall through to the working-directory based root.
		}
		return Path.of(System.getProperty("user.dir", "."), "target", CACHE_ROOT_DIRECTORY_NAME);
	}

	/** Capacity of the currently selected tier, resolved without a compile-time dependency on {@code datagen}. */
	public static int tierEntries() {
		return entriesFor(tierName());
	}

	/**
	 * Lists the directories currently under {@link #cacheRoot()}, sorted for stable assertions. Transient
	 * generation directories (named {@code <fingerprint>.tmp-<n>} while a compilation is in flight, see
	 * {@link SchemaCompiler}) are excluded: only completed cache entries are listed. An I/O failure is an
	 * error, not an empty listing, so a broken root cannot masquerade as an empty cache.
	 */
	public List<String> cacheDirectories() {
		try (var paths = Files.list(cacheRoot)) {
			return paths.filter(Files::isDirectory)
					.map(path -> path.getFileName().toString())
					.filter(name -> !name.contains(".tmp-"))
					.sorted(Comparator.naturalOrder())
					.toList();
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to list the fuzz schema cache root " + cacheRoot, exception);
		}
	}

	// ------------------------------------------------------------------------------------------------
	// internals
	// ------------------------------------------------------------------------------------------------

	private CompiledSchema cached(String fingerprint) {
		synchronized (this) {
			CompiledSchema cached = entries.get(fingerprint);
			if (cached != null) {
				hitCount++;
				// Served from cache: the instance may have been compiled by an earlier call (or an earlier
				// run, through the on-disk cache), so record that it was not compiled for this request.
				cached.markCached();
			}
			return cached;
		}
	}

	/** Caller holds {@code this}. Keeps {@code size <= maxEntries}, returning what must be closed. */
	private List<CompiledSchema> evictOverflowLocked() {
		if (entries.size() <= maxEntries) {
			return List.of();
		}
		List<CompiledSchema> evicted = new ArrayList<>();
		var iterator = entries.entrySet().iterator();
		while (entries.size() - evicted.size() > maxEntries && iterator.hasNext()) {
			Map.Entry<String, CompiledSchema> entry = iterator.next();
			iterator.remove();
			slots.remove(entry.getKey());
			evicted.add(entry.getValue());
			evictionCount++;
		}
		return evicted;
	}

	private static void closeAll(List<CompiledSchema> schemas) {
		for (CompiledSchema schema : schemas) {
			schema.close();
		}
	}

	private static int entriesFor(String tier) {
		return switch (tier) {
			case "QUICK" -> QUICK_ENTRIES;
			case "DEFAULT" -> DEFAULT_ENTRIES;
			default -> DEEP_ENTRIES;
		};
	}

	private static volatile String tierName;

	private static String tierName() {
		String tier = tierName;
		if (tier == null) {
			tier = readTier();
			tierName = tier;
		}
		return tier;
	}

	/**
	 * Reads the tier through {@code it.cavallium.datagen.fuzz.FuzzTiers} when that class is on the classpath,
	 * and otherwise applies its documented rule directly ({@code -Ddatagen.fuzz.tier}; unset means DEEP when
	 * {@code -Ddatagen.fuzz.seed} is set, DEFAULT otherwise; an unknown value is an
	 * {@link IllegalArgumentException}). {@code FuzzTiers} lives in {@code datagen}'s test sources, which this
	 * module does not depend on, so a compile-time reference is not available.
	 */
	private static String readTier() {
		try {
			Class<?> tiers = Class.forName(TIER_CLASS);
			Object tier = tiers.getMethod("tier").invoke(null);
			return ((Enum<?>) tier).name();
		} catch (ReflectiveOperationException exception) {
			// No FuzzTiers on the classpath: fall back to its documented semantics.
		}
		String raw = trimmedProperty("datagen.fuzz.tier");
		if (raw == null) {
			return trimmedProperty("datagen.fuzz.seed") == null ? "DEFAULT" : "DEEP";
		}
		for (String candidate : TIER_NAMES) {
			if (candidate.equalsIgnoreCase(raw)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException(
				"Invalid datagen.fuzz.tier='" + raw + "': expected quick|default|deep|nightly");
	}

	/** @return the trimmed property value, or {@code null} when unset or blank (FuzzTiers.java:221-228) */
	private static String trimmedProperty(String key) {
		String raw = System.getProperty(key);
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
