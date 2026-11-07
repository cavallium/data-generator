package it.cavallium.datagen.plugin.fuzz.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.Context;
import it.cavallium.datagen.plugin.fuzz.support.SchemaGenProfile.Feature;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Pins the generated {@code CurrentVersion} read/upgrade surface for BOTH {@code generateOldSerializers} modes and
 * walks the old-serializers schema once per (type, version).
 *
 * <p>Why this exists: a DEEP-tier evolution failure was reported as {@code "Generated-model lookup failed,
 * lookup=CurrentVersion.read(int, BaseType, SafeDataInput)")}, because {@link GeneratedModel} wraps ANY invocation
 * throwable in that message. The surface was never missing - the emitted overload list is identical in both modes,
 * which is what the first test pins - while the harness had generated an upgrade whose previous shape was
 * {@code String} although the shared test upgraders read an {@code int}; the fused read then threw
 * {@code ClassCastException}. That was a {@link SchemaGen} configuration defect, and the second test keeps it fixed:
 * reading an old-serializers schema must return a value or a counted skip, never throw.</p>
 */
class CurrentVersionSurfaceTest {

	/** The reproduction dossier case: {@code MULTI_VERSION} + {@code DATA_UPGRADES}, {@code generateOldSerializers}. */
	private static final long CASE_SEED = -3509161920279225996L;

	/**
	 * The entry points {@link GeneratedModel} resolves, as {@code name(<parameter type names>)}. Return types are
	 * deliberately excluded: the generated ones are erased to the generated {@code IBaseType}/{@code Reader}
	 * classes, which the facade adapts with {@code asType}.
	 */
	private static final List<String> REQUIRED_LOOKUP_SIGNS = List.of(
			"read(int, <root>.BaseType, it.cavallium.stream.SafeDataInput)",
			"newReader(<root>.BaseType, it.cavallium.datagen.DecodeLimits)",
			"newReader(int, <root>.BaseType, it.cavallium.datagen.DecodeLimits)",
			"upgradeDataToLatestVersion(int, java.lang.Object)");

	@Test
	void currentVersionSurfaceIsIdenticalWithAndWithoutOldSerializers() {
		List<String> withOldSerializers = surface(schema(), true);
		List<String> withoutOldSerializers = surface(schema(), false);

		assertEquals(withoutOldSerializers, withOldSerializers,
				"generateOldSerializers changed the emitted CurrentVersion read/upgrade surface, so a "
						+ "\"lookup failed\" message in one mode would be a real surface change, not a harness defect");
		List<String> lookupSigns = lookupSigns(withOldSerializers);
		for (String required : REQUIRED_LOOKUP_SIGNS) {
			assertTrue(lookupSigns.contains(required),
					() -> "Missing " + required + " in " + withOldSerializers);
		}
	}

	/**
	 * The regression itself: with historical serializers this schema really reads version-0 bytes, so a config whose
	 * previous shape disagrees with the upgrader throws here instead of being a counted skip.
	 * {@link GeneratedOracles#upgradeEquivalence} returns a skip marker or {@code null}; it must not throw.
	 */
	@Test
	void oldSerializersSchemaReadsEveryVersionWithoutThrowing() {
		SchemaCompiler.CompileOptions options = new SchemaCompiler.CompileOptions(
				GeneratedOracles.FUZZ_BASE_PACKAGE, true, false, false,
				CompiledSchemas.defaultCacheRoot(), Map.of());

		try (CompiledSchema schema = CompiledSchema.compile(schema(), options)) {
			Context ctx = Context.of(schema);
			ReflectiveValueGen values = ctx.values();
			int checked = 0;
			for (String typeName : ctx.schema().typeNames()) {
				if (!values.supports(typeName)) {
					continue;
				}
				for (int version : ctx.model().versionIds()) {
					Object value = values.value(typeName, version, new Random(7), 2, mask -> { });
					String skip;
					try {
						skip = GeneratedOracles.upgradeEquivalence(ctx, typeName, version, value);
					} catch (RuntimeException failure) {
						throw new AssertionError(typeName + "@v" + version + " of " + schema.fingerprint()
								+ " threw " + failure + " instead of returning null or a counted skip", failure);
					}
					assertTrue(skip == null || skip.startsWith("type-absent") || skip.startsWith("no-old-serializer"),
							() -> typeName + "@v" + version + " reported an unexpected skip: " + skip);
					checked++;
				}
			}
			assertTrue(checked >= 2, "Expected several (type, version) pairs, checked " + checked);
		}
	}

	private static SourcesGeneratorConfiguration schema() {
		var profile = SchemaGenProfile.forTier(SchemaGenProfile.Tier.DEFAULT)
				.withForced(Feature.MULTI_VERSION, Feature.DATA_UPGRADES);
		return SchemaGen.generate(new Random(CASE_SEED), profile, new SchemaGen.Coverage());
	}

	/**
	 * Every declared read/upgrade/newReader method of {@code CurrentVersion}, sorted and de-duplicated, as
	 * {@code modifiers returnType name(<parameter type names>)}. Declaration order is a javac detail, so it is
	 * normalized away; the content is what both modes must agree on.
	 */
	private static List<String> surface(SourcesGeneratorConfiguration config, boolean oldSerializers) {
		SchemaCompiler.CompileOptions options = new SchemaCompiler.CompileOptions(
				GeneratedOracles.FUZZ_BASE_PACKAGE + ".surface." + (oldSerializers ? "old" : "current"),
				oldSerializers, false, false, CompiledSchemas.defaultCacheRoot(), Map.of());
		try (CompiledSchema schema = CompiledSchema.compile(config, options)) {
			Class<?> currentVersion = new GeneratedModel(schema).currentVersionClass();
			List<String> signatures = new ArrayList<>();
			for (Method method : currentVersion.getDeclaredMethods()) {
				String name = method.getName();
				if (!name.startsWith("read") && !name.startsWith("newReader") && !name.startsWith("upgrade")) {
					continue;
				}
				signatures.add((Modifier.toString(method.getModifiers()) + " " + method.getReturnType().getName()
						+ " " + name + "(" + parameterTypes(method) + ")")
						// The two modes are compiled into different packages on purpose, so the package is not
						// part of the comparison: it is the one thing that legitimately differs.
						.replace(schema.rootPackage(), "<root>"));
			}
			return List.copyOf(new TreeSet<>(signatures));
		}
	}

	/** The same signatures reduced to {@code name(<parameter type names>)}, the form {@link #REQUIRED_LOOKUP_SIGNS} uses. */
	private static List<String> lookupSigns(List<String> signatures) {
		List<String> signs = new ArrayList<>();
		for (String signature : signatures) {
			int openParen = signature.indexOf('(');
			int nameStart = signature.lastIndexOf(' ', openParen) + 1;
			signs.add(signature.substring(nameStart));
		}
		return signs;
	}

	private static String parameterTypes(Method method) {
		var parameters = new StringBuilder();
		for (Class<?> parameter : method.getParameterTypes()) {
			parameters.append(parameters.isEmpty() ? "" : ", ").append(parameter.getName());
		}
		return parameters.toString();
	}
}
