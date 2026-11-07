package it.cavallium.datagen.plugin.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.datagen.plugin.fuzz.support.CompiledSchema;
import it.cavallium.datagen.plugin.fuzz.support.CompiledSchemas;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.SchemaCase;
import it.cavallium.datagen.plugin.fuzz.support.SchemaCompiler;
import it.cavallium.datagen.plugin.fuzz.support.SchemaGenProfile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <p>DEEP-tier budget test for the shared compile cache: twelve distinct generated schemas must (1) come
 * back from the on-disk cache fast enough to keep a fuzz campaign practical — a SIBLING
 * {@link CompiledSchemas} (own entries, same cache root) must reload every one of them with no
 * {@code javac} at all ({@code compileMillis() == 0}); the MEDIAN of three such rounds must average
 * at most {@value #MAX_MEAN_RELOAD_MILLIS} ms wall-clock per schema; (2) be served as the SAME
 * instance by the in-memory cache, proven with {@code hitCount}/{@code missCount} deltas rather than
 * assumed; and (3) keep the cache entries and the {@code target/fuzz-schemas} directories within
 * {@code maxEntries}.
 *
 * <p>Plain Jupiter (one fixed workload, not random input). The deep tier is enforced twice — via
 * {@code @Tag("deep")} for tag filtering and via {@link GeneratedOracles#requireMinimumTier} for a plain
 * {@code -Ddatagen.fuzz.tier=...} run — per the fuzz tag rule that deep work is opt-in.</p>
 */
@Tag("deep")
class HarnessBudgetTest {

	/** Mean wall-clock budget per schema for the disk-cache reload pass (no javac allowed). */
	private static final long MAX_MEAN_RELOAD_MILLIS = 60;

	private static final int DISTINCT_SCHEMAS = 12;

	@Test
	@DisplayName("12 schemas: disk-cache reload within budget, in-memory hits proven, entries bounded")
	void compileBudgetCacheReuseAndEntryBounds() {
		GeneratedOracles.requireMinimumTier(SchemaGenProfile.Tier.DEEP);
		CompiledSchemas cache = CompiledSchemas.shared();

		// Walk the spread until twelve DISTINCT fingerprints are warm on disk. Spread entries whose
		// generation itself fails (recorded by compileCase as a SchemaGen DEFECT, outside this scope)
		// are skipped; enough healthy entries exist to always reach twelve.
		List<SchemaCase> cases = new ArrayList<>();
		Set<String> fingerprints = new LinkedHashSet<>();
		for (var item : GeneratedOracles.spread(SchemaGenProfile.currentTier(), false)) {
			SchemaCase compiled = GeneratedOracles.compileCase(item, 0xB057L);
			if (compiled == null || !fingerprints.add(compiled.schema().fingerprint())) {
				continue;
			}
			cases.add(compiled);
			if (cases.size() == DISTINCT_SCHEMAS) {
				break;
			}
		}
		assertEquals(DISTINCT_SCHEMAS, cases.size(),
				"the spread must yield 12 compilable distinct schemas, got " + cases.size());

		// 1. In-memory reuse: every repeat request returns the SAME instance; counters prove the hit.
		CompiledSchemas.Stats before = cache.stats();
		for (SchemaCase schemaCase : cases) {
			CompiledSchema again = cache.compile(schemaCase.config(), options(schemaCase));
			assertSame(schemaCase.schema(), again,
					"the shared cache returned a different instance for " + schemaCase.item().id());
		}
		CompiledSchemas.Stats afterRepeat = cache.stats();
		assertTrue(afterRepeat.hitCount() - before.hitCount() >= DISTINCT_SCHEMAS,
				"repeat requests did not register as hits: " + before + " -> " + afterRepeat);
		assertEquals(0, afterRepeat.missCount() - before.missCount(),
				"repeat requests must not recompile: " + before + " -> " + afterRepeat);

		// 2. Budget: a SIBLING cache (its own entries, same root directory) must serve all 12 from the
		// on-disk artifacts — zero javac (compileMillis()==0) and within the wall-clock budget. (evictAll
		// would DELETE the directories, so the sibling — not eviction — is the disk-reuse proof.)
		// THREE independent rounds, and the MEDIAN is the measured value: one round couples the number to
		// whatever else the fork is doing (class loading, disk warm/cold), which made a single timed pass
		// flaky. The reload itself is intrinsically URLClassLoader + directory walking — that IS the
		// artifact reuse being timed; only the surrounding noise is averaged out.
		double[] rounds = new double[3];
		for (int round = 0; round < rounds.length; round++) {
			rounds[round] = reloadRoundMillisPerSchema(cache, cases);
		}
		java.util.Arrays.sort(rounds);
		double meanMillis = rounds[rounds.length / 2]; // median of 3

		// 3. Bounds.
		assertTrue(cache.size() <= cache.maxEntries(),
				"cache holds " + cache.size() + " entries, maxEntries is " + cache.maxEntries());
		assertTrue(cache.cacheDirectories().size() <= cache.maxEntries(),
				"cache root " + cache.cacheRoot() + " holds " + cache.cacheDirectories().size()
						+ " directories, maxEntries is " + cache.maxEntries());

		System.out.println("[budget] " + GeneratedOracles.describe() + " 12 schemas reloaded from disk, "
				+ "median of 3 rounds: " + String.format(java.util.Locale.ROOT, "%.1f", meanMillis)
				+ " ms/schema (budget " + MAX_MEAN_RELOAD_MILLIS + " ms mean), cache=" + cache.stats());
		assertTrue(meanMillis <= MAX_MEAN_RELOAD_MILLIS,
				"mean disk-reload per schema was " + meanMillis + " ms, budget is " + MAX_MEAN_RELOAD_MILLIS
						+ " ms (" + GeneratedOracles.describe() + ")");
	}

	private static SchemaCompiler.CompileOptions options(SchemaCase schemaCase) {
		return new SchemaCompiler.CompileOptions(GeneratedOracles.FUZZ_BASE_PACKAGE,
				schemaCase.item().oldSerializers(), false, false,
				CompiledSchemas.defaultCacheRoot(), Map.of());
	}

	/**
	 * One reload round: a FRESH sibling cache (own entries, shared root) compiles every case from the
	 * on-disk artifacts. Returns the mean wall-clock milliseconds per schema; asserts zero javac and
	 * that all 12 came through as sibling misses (no shared in-memory instances). The sibling is
	 * deliberately NOT evicted: close() would delete the shared root's directories out from under the
	 * still-open shared entries.
	 */
	private static double reloadRoundMillisPerSchema(CompiledSchemas cache, List<SchemaCase> cases) {
		CompiledSchemas sibling = cache.withMaxEntries(DISTINCT_SCHEMAS);
		long reloadStart = System.nanoTime();
		for (SchemaCase schemaCase : cases) {
			CompiledSchema reloaded = sibling.compile(schemaCase.config(), options(schemaCase));
			assertEquals(0, reloaded.compileMillis(),
					"the disk reload of " + schemaCase.item().id() + " ran javac (compileMillis="
							+ reloaded.compileMillis() + ") instead of serving the on-disk artifact, "
							+ GeneratedOracles.describe());
		}
		double meanMillis = (System.nanoTime() - reloadStart) / 1_000_000.0 / DISTINCT_SCHEMAS;
		assertEquals(DISTINCT_SCHEMAS, sibling.stats().missCount(),
				"the sibling cache must not share in-memory instances: " + sibling.stats());
		return meanMillis;
	}
}
