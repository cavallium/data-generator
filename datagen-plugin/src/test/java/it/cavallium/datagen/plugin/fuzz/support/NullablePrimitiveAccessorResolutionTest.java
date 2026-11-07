package it.cavallium.datagen.plugin.fuzz.support;

import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pinned (no jqwik randomness) regression test for the projection-path accessor resolution of DEFECT 1:
 * the {@code RandomSchemaCorruptionPropertyTest} flake {@code IllegalStateException: Accessor f0 failed /
 * NoSuchElementException: Nullable field f0 is empty} (see {@code target/fuzz-failures/
 * projections.force_CONTEXT_PARAMETERS.txt}).
 *
 * <p>A nullable field whose base is a PRIMITIVE has no {@code fOrNull()} on the generated class — only
 * {@code hasF()} plus a plain {@code f()} that throws when empty (proven by {@code target/fuzz-schemas/
 * 680c77ee267787ab217644f49fc69d020f31ca82f7f6aab5a0d7bce61ce8f146/sources/org/example/fuzz/e2e/current/
 * data/G1taspsT0.java}, lines 46-55). The oracle must resolve such an absent step to {@code null} via the
 * class's OWN presence boolean — never by blanket-catching {@code NoSuchElementException}, so a genuine
 * accessor error still throws.</p>
 */
class NullablePrimitiveAccessorResolutionTest {

	/** The dossier schema, pinned: f0 is a nullable PRIMITIVE (byte), selected by projection path. */
	private static final String SCHEMA_YAML = """
			currentVersion: v1
			baseTypesData:
			  NpT0:
			    data: {f0: '-byte', f1: String}
			projectionsData:
			  NpP0:
			    sourceType: NpT0
			    fields: {p0: f0}
			versions:
			  v1: {}
			""";

	@Test
	void absentNullablePrimitivePathStepPassesProjectionParity() throws Exception {
		SourcesGeneratorConfiguration config = SchemaYamlEmitter.load(SCHEMA_YAML);
		CompiledSchemas cache = CompiledSchemas.shared();
		CompiledSchema schema = cache.compile(config, new SchemaCompiler.CompileOptions(
				GeneratedOracles.FUZZ_BASE_PACKAGE, false, false, false,
				CompiledSchemas.defaultCacheRoot(), java.util.Map.of()));
		try {
			GeneratedOracles.Context ctx = GeneratedOracles.Context.of(schema);
			// Pinned value: fixed seed, then clearF0() so the nullable PRIMITIVE step is provably absent
			// (the exact shape of the flake: f0 empty while the projection selects it).
			Object value = ctx.values().value("NpT0", ctx.currentVersion(), new Random(7L), 1);
			value = value.getClass().getMethod("clearF0").invoke(value);
			assertEquals(Boolean.FALSE, value.getClass().getMethod("hasF0").invoke(value),
					"the pinned value must have the nullable primitive f0 absent");
			assertEquals(1, GeneratedOracles.projectionParity(ctx, "NpT0", value),
					"the pinned schema has exactly one projection; before the fix this threw"
							+ " IllegalStateException: Accessor f0 failed (NoSuchElementException: empty)");
		} finally {
			cache.release(schema);
		}
	}

	@Test
	void emptyPrimitiveNullableResolvesToNullThroughPresenceFlag() {
		assertEquals(null, GeneratedOracles.readGeneratedAccessor(new PrimitiveNullable(false, (byte) 0), "f0"),
				"a hasF()-false, no-OrNull field is absent, not an error");
		assertEquals((byte) 42, GeneratedOracles.readGeneratedAccessor(new PrimitiveNullable(true, (byte) 42), "f0"),
				"hasF()-true resolves through the plain accessor");
	}

	@Test
	void genuineAccessorErrorStillThrowsWithoutPresenceFlag() {
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> GeneratedOracles.readGeneratedAccessor(new GenuineError(), "f0"));
		assertTrue(failure.getCause() instanceof IllegalStateException,
				"the accessor's own failure must surface, not be swallowed as absence: " + failure);
	}

	/** Generated-surface shape of a nullable primitive field (mirrors G1taspsT0.f0/hasF0). */
	private record PrimitiveNullable(boolean present, byte value) {
		public boolean hasF0() {
			return present;
		}

		public byte f0() {
			if (!present) {
				throw new NoSuchElementException("Nullable field f0 is empty");
			}
			return value;
		}
	}

	/** A class with NO hasF()/OrNull form whose plain accessor fails for a real reason. */
	private static final class GenuineError {
		public int f0() {
			throw new IllegalStateException("genuine accessor error");
		}
	}
}
