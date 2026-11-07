package it.cavallium.datagen.plugin.fuzz.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import it.cavallium.datagen.plugin.SourcesGenerator;
import it.cavallium.datagen.plugin.fuzz.support.InvalidSchemaGen.InvalidCase;
import it.cavallium.datagen.plugin.fuzz.support.InvalidSchemaGen.Mutation;
import it.cavallium.datagen.plugin.fuzz.support.InvalidSchemaGen.Stage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every {@link Mutation} really is rejected, and rejected by the rule it is supposed to violate: the expected
 * exception class and message fragment come from running the mutation against the production code, not from
 * documentation.
 *
 * <p>{@link Stage#MODEL} cases are run through {@code buildDataModel(false)}; {@link Stage#GENERATION} cases build
 * a model and only fail while the sources are generated, because {@code DataModel} does not resolve projection
 * paths.</p>
 */
class InvalidSchemaGenTest {

	/** Cases per mutation; {@link #MAX_ATTEMPTS_PER_MUTATION} bounds the search for a host schema. */
	private static final int CASES_PER_MUTATION = 20;
	private static final int MAX_ATTEMPTS_PER_MUTATION = 200;

	@Test
	void mutationCatalogueIsCompleteAndDistinct() {
		assertEquals(Mutation.values().length, Mutation.ids().stream().distinct().count(),
				"Two mutations share an id, so a failure could not be attributed");
		for (Mutation mutation : Mutation.values()) {
			assertNotNull(mutation.expectedFailure(), mutation.id());
			assertNotNull(mutation.stage(), mutation.id());
			assertNotNull(mutation.expectedMessageFragment(), mutation.id());
		}
	}

	/**
	 * The core contract: {@value #CASES_PER_MUTATION} independently seeded cases per mutation, each failing with the
	 * declared exception and fragment. A mutation that fails with a different message fails the test, because then
	 * the schema is not exercising the rule it names.
	 */
	@Test
	void rejectedMutationsAreRejectedWithTheExpectedFailure(@TempDir Path tempDir) {
		var report = new StringBuilder();
		for (Mutation mutation : Mutation.values()) {
			int applied = 0;
			int attempts = 0;
			List<String> problems = new ArrayList<>();
			while (applied < CASES_PER_MUTATION && attempts < MAX_ATTEMPTS_PER_MUTATION) {
				long seed = 0xC0FFEEL * mutation.ordinal() + attempts++;
				InvalidCase invalidCase;
				try {
					invalidCase = InvalidSchemaGen.generate(new Random(seed), mutation);
				} catch (RuntimeException exception) {
					problems.add("attempt " + attempts + " could not be built: " + exception);
					break;
				}
				if (invalidCase == null) {
					continue;
				}
				applied++;
				assertEquals(mutation.id(), invalidCase.mutationId(), "Wrong mutation id");
				assertEquals(mutation.expectedFailure(), invalidCase.expectedFailure(), "Wrong expectation for "
						+ mutation.id());
				Throwable thrown = expectedFailure(invalidCase, mutation, tempDir, attempts);
				if (thrown == null) {
					problems.add("attempt " + attempts + " was accepted:\n"
							+ SchemaYamlEmitter.emit(invalidCase.config()));
					continue;
				}
				assertTrue(mutation.expectedFailure().isInstance(thrown), "attempt " + attempts + " of "
						+ mutation.id() + " threw " + thrown.getClass().getName() + ": " + thrown.getMessage());
				// The message fragment is the proof that the intended rule is the one that fired.
				assertTrue(contains(thrown, mutation.expectedMessageFragment()), "attempt " + attempts + " of "
						+ mutation.id() + " threw " + thrown.getClass().getName() + " without \""
						+ mutation.expectedMessageFragment() + "\": " + thrown.getMessage());
			}
			report.append(mutation.id()).append("=").append(applied).append('/').append(attempts).append(" attempts ");
			if (!problems.isEmpty()) {
				report.append("problems=").append(problems);
			}
			if (applied < CASES_PER_MUTATION || !problems.isEmpty()) {
				fail(mutation.id() + " produced " + applied + " rejected cases in " + attempts
						+ " attempts (wanted " + CASES_PER_MUTATION + "): " + problems);
			}
		}
	}

	/** A random mutation over many seeds is always one of the catalogue and always rejected. */
	@Test
	void randomMutationsAreAlwaysRejected(@TempDir Path tempDir) {
		// Catalogue-coverage is a property of THIS test, not of the fuzz budget: uniform random selection
		// cannot see all 20 ids at the QUICK tier's 10-case floor (even 60 fixed-seed cases missed three).
		// So keep drawing (deterministic seed formula) until the catalogue is complete, capped at ten
		// cases per mutation; the per-case assertions below are unchanged.
		int cases = SchemaGenProfile.cases("invalid-schema-gen.random", 20, 120, 400, 1000);
		int catalogueCap = Mutation.values().length * 10;
		var seen = new java.util.TreeSet<String>();
		for (int index = 0; index < Math.max(cases, catalogueCap); index++) {
			if (index >= cases && seen.size() == Mutation.values().length) {
				break;
			}
			InvalidCase invalidCase = InvalidSchemaGen.generate(new Random(0xB16L * (index + 1)));
			Mutation mutation = java.util.Arrays.stream(Mutation.values())
					.filter(candidate -> candidate.id().equals(invalidCase.mutationId()))
					.findFirst()
					.orElseThrow(() -> new AssertionError("Unknown mutation id " + invalidCase.mutationId()));
			seen.add(invalidCase.mutationId());
			Throwable thrown = expectedFailure(invalidCase, mutation, tempDir, index);
			assertNotNull(thrown, () -> mutation.id() + " was accepted:\n"
					+ SchemaYamlEmitter.emit(invalidCase.config()));
			assertTrue(mutation.expectedFailure().isInstance(thrown), mutation.id() + " threw "
					+ thrown.getClass().getName() + ": " + thrown.getMessage());
			assertTrue(contains(thrown, mutation.expectedMessageFragment()), mutation.id() + " message \""
					+ thrown.getMessage() + "\" lacks \"" + mutation.expectedMessageFragment() + "\"");
		}
		assertEquals(Mutation.values().length, seen.size(), "Random selection missed mutations: " + seen);
	}

	// ------------------------------------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------------------------------------

	/** Runs the case and returns what it threw, or {@code null} when it was accepted. */
	private static Throwable expectedFailure(InvalidCase invalidCase, Mutation mutation, Path tempDir, int attempt) {
		try {
			if (mutation.stage() == Stage.MODEL) {
				invalidCase.config().buildDataModel(false);
			} else {
				generateSources(invalidCase, tempDir.resolve(mutation.id() + attempt));
			}
			return null;
		} catch (RuntimeException | IOException exception) {
			return exception;
		}
	}

	/** The projection mutations need the real generator; the model alone accepts them. */
	private static void generateSources(InvalidCase invalidCase, Path out) throws IOException {
		Files.createDirectories(out);
		byte[] yaml = SchemaYamlEmitter.emit(invalidCase.config()).getBytes(StandardCharsets.UTF_8);
		try {
			SourcesGenerator.load(new ByteArrayInputStream(yaml)).generateSources("org.example.fuzz", out, true,
					false, false, false);
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	/** Fragment match over the message and, for wrappers, over their causes. */
	private static boolean contains(Throwable thrown, String fragment) {
		if (fragment.isEmpty()) {
			// ParsedClass.NoContextParameterException carries no message; the class check is the assertion.
			return true;
		}
		for (Throwable current = thrown; current != null; current = current.getCause()) {
			if (current.getMessage() != null && current.getMessage().contains(fragment)) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}
}
