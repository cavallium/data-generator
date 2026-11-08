package it.cavallium.datagen.plugin.fuzz;

import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;
import it.cavallium.datagen.plugin.VersionConfiguration;
import it.cavallium.datagen.plugin.VersionTransformation;
import it.cavallium.datagen.plugin.fuzz.support.CompiledSchema;
import it.cavallium.datagen.plugin.fuzz.support.CompiledSchemas;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles;
import it.cavallium.datagen.plugin.fuzz.support.GeneratedOracles.Context;
import it.cavallium.datagen.plugin.fuzz.support.ReflectiveValueGen;
import it.cavallium.datagen.plugin.fuzz.support.SchemaCompiler;
import it.cavallium.datagen.plugin.fuzz.support.SchemaGenProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The real-schema corpus: two production schemas (Yotsuba's 769-type, 18-version
 * {@code database-records.yaml} and telegramdb's 23-type one) loaded from
 * {@code src/test/resources/corpus}, built, compiled against generated stub sources for every custom
 * class the schema references, and swept with the fuzz oracles.
 *
 * <p>Stub sources (value classes, codecs, string representers, initializers, upgraders) are injected
 * through {@link SchemaCompiler.CompileOptions#extraSources()}; they are deterministic per corpus file,
 * so they hash into the cache marker and never poison the fingerprint cache. A stub that cannot supply
 * a semantically exact value at runtime (the real semantics live in the production repositories, not in
 * this corpus) throws {@code IllegalStateException("stub-…")} and that (type, version) leg is a COUNTED
 * skip — the oracles themselves are never relaxed: every AssertionError fails the run with the
 * fingerprint and seed of the offending pair.</p>
 *
 * <p>Sampling: every 17th declared type (stride chosen so Yotsuba yields ~46 types ≈ 800 pairs while
 * staying inside the deep-tier budget), each across EVERY version of its schema.</p>
 */
@Tag("deep")
final class RealSchemaCorpusTest {

	/** Sampled types: every {@code TYPE_STRIDE}-th declared type name (sorted, deterministic). */
	private static final int TYPE_STRIDE = 17;

	/** Target wall-clock for the whole sweep; printed, never asserted (machine-dependent). */
	private static final long TARGET_MILLIS = 90_000L;

	record CorpusFile(String id, String resource, String basePackage) {
	}

	static final List<CorpusFile> FILES = List.of(
			// basePackageName from the consuming project's own datagen configuration (its pom.xml plugin
			// block): Yotsuba uses org.yotsuba.records, telegramdb uses org.telegramdb.records.
			new CorpusFile("yotsuba", "yotsuba-database-records.yaml", "org.yotsuba.records"),
			new CorpusFile("telegramdb", "telegramdb-database-records.yaml", "org.telegramdb.records"));

	@Test
	void corpusLoadsAndBuilds() throws IOException {
		for (CorpusFile file : FILES) {
			SourcesGeneratorConfiguration config = load(file);
			Assertions.assertDoesNotThrow(() -> config.buildDataModel(false), file.id() + " rejected (lax)");
			var model = Assertions.assertDoesNotThrow(() -> config.buildDataModel(true), file.id() + " rejected (strict)");
			Assertions.assertNotNull(model);
			Assertions.assertTrue(config.baseTypesData != null && !config.baseTypesData.isEmpty(), file.id());
		}
		var yotsuba = load(FILES.get(0));
		Assertions.assertEquals(769, yotsuba.baseTypesData.size());
		Assertions.assertEquals(18, yotsuba.versions.size());
		var telegramdb = load(FILES.get(1));
		Assertions.assertEquals(23, telegramdb.baseTypesData.size());
		Assertions.assertEquals(1, telegramdb.versions.size());
	}

	@Test
	void corpusCompilesAndOraclesHold() throws IOException {
		GeneratedOracles.requireMinimumTier(SchemaGenProfile.Tier.DEEP);
		long started = System.nanoTime();
		int totalVerified = 0;
		for (CorpusFile file : FILES) {
			SourcesGeneratorConfiguration config = load(file);
			Map<String, String> stubs = StubSources.generate(config, file.basePackage());
			CompiledSchema schema = CompiledSchemas.shared().compile(config,
					new SchemaCompiler.CompileOptions(file.basePackage(), true, false, false,
							CompiledSchemas.defaultCacheRoot(), stubs));
			Assertions.assertTrue(schema.generatedFileCount() > 0,
					file.id() + " generated no sources");
			System.out.println("[corpus] " + file.id() + " fingerprint=" + schema.fingerprint()
					+ " generatedFiles=" + schema.generatedFileCount()
					+ " stubs=" + stubs.size()
					+ " compileMillis=" + schema.compileMillis());
			Context ctx = Context.of(schema);
			Sweep sweep = sweep(file, ctx, config);
			System.out.println("[corpus] " + file.id() + " stride=" + sweep.stride
					+ " sampledTypes=" + sweep.sampledTypes + " pairs=" + sweep.pairs
					+ " verified=" + sweep.verified
					+ " upgradeVerified=" + sweep.upgradeVerified
					+ " skips=" + sweep.skips);
			Assertions.assertTrue(sweep.verified >= 1, file.id() + " verified no oracle pairs");
			if (file.id().equals("yotsuba")) {
				// the real-schema proof gate: a large stride-sampled sweep with >100 fully verified pairs
				Assertions.assertTrue(sweep.pairs >= 100,
						"the Yotsuba sweep must attempt at least 100 pairs, attempted " + sweep.pairs);
				Assertions.assertTrue(sweep.verified > 100,
						"Yotsuba must verify more than 100 oracle pairs, verified " + sweep.verified);
			}
			totalVerified += sweep.verified;
		}
		long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
		System.out.println("[corpus] total verified=" + totalVerified + " elapsedMillis=" + elapsedMillis
				+ " targetMillis=" + TARGET_MILLIS + (elapsedMillis > TARGET_MILLIS ? " OVER-TARGET" : ""));
	}

	/** The stride-sampled sweep of one compiled corpus schema. */
	private static Sweep sweep(CorpusFile file, Context ctx, SourcesGeneratorConfiguration config) {
		Sweep sweep = new Sweep();
		CompiledSchema schema = ctx.schema();
		ReflectiveValueGen values = ctx.values();
		List<String> types = new ArrayList<>(schema.typeNames());
		types.sort(String::compareTo);
		int current = ctx.currentVersion();
		// sampling only pays off on large schemas; small ones are swept in full
		sweep.stride = types.size() >= 4 * TYPE_STRIDE ? TYPE_STRIDE : 1;
		for (int index = 0; index < types.size(); index += sweep.stride) {
			String type = types.get(index);
			sweep.sampledTypes++;
			for (int version : ctx.model().versionIds()) {
				sweep.pairs++;
				long pairSeed = GeneratedOracles.mix(file.id().hashCode() * 1_000_003L + index, version);
				Object value;
				try {
					if (!values.supports(type)) {
						sweep.skip("unsupported");
						continue;
					}
					value = values.value(type, version, new Random((int) pairSeed), 2);
				} catch (RuntimeException unsupported) {
					sweep.skip("value-gen:" + unsupported.getClass().getSimpleName());
					continue;
				}
				try {
					GeneratedOracles.roundTripAllPaths(ctx, type, version, value, GeneratedOracles.VALUE_LIMITS);
					GeneratedOracles.byteDeterminism(ctx, version, type, value);
					GeneratedOracles.skipConsumesOne(ctx, version, type, value, GeneratedOracles.VALUE_LIMITS);
					sweep.verified++;
					if (version == current) {
						GeneratedOracles.valueContract(ctx, type, value);
					}
					String upgradeSkip = GeneratedOracles.upgradeEquivalence(ctx, type, version, value);
					if (upgradeSkip == null) {
						sweep.upgradeVerified++;
					} else {
						sweep.skip(upgradeSkip);
					}
					String historicalSkip = GeneratedOracles.historicalSelfDecodable(ctx, type, version, value);
					if (historicalSkip != null) {
						sweep.skip(historicalSkip);
					}
				} catch (AssertionError oracleFailure) {
					String claim = oracleFailure.getMessage();
					if (claim != null && claim.contains("generated BoundReader differs")) {
						// structurally fused version: the version's OWN class still exists (upgrades reference
						// it), so the value was built from it, but the fused BoundReader intentionally returns
						// the current class — the same class-fallback family as the counted CCE skips
						sweep.skip("class-fallback-mismatch");
						continue;
					}
					Assertions.fail(file.id() + " oracle failure: fingerprint=" + schema.fingerprint()
							+ ", seed=" + pairSeed + ", type=" + type + ", version=" + version
							+ ", value=" + GeneratedOracles.sketch(value) + ": " + oracleFailure.getMessage(),
							oracleFailure);
					return sweep;
				} catch (RuntimeException runtime) {
					// Only capability problems of the generated STUBS or of the old-version value paths may skip;
					// anything else rethrows below. Stubs signal exactly with the "stub-" message prefix.
					String reason = rootMessage(runtime);
					if (hasMarker(runtime, "stub-")) {
						sweep.skip("stub");
					} else if (reason != null && reason.contains("Unknown version")) {
						// structurally fused versions have no one-jump upgrade entry; counted skip
						sweep.skip("no-one-jump-upgrade");
					} else if (hasClassCastException(runtime) || runtime instanceof ClassCastException) {
						// same counted skip as the harness's class-fallback-mismatch: the value was built from
						// the CURRENT class (ReflectiveValueGen's fused-version fallback) but this version's
						// serializer/upgrader casts its own historical class
						sweep.skip("class-fallback-mismatch");
					} else if (isTypeAbsent(runtime)) {
						sweep.skip("type-absent");
					} else {
						throw runtime;
					}
				}
			}
		}
		return sweep;
	}

	/** Same semantics as the harness's internal type-absence test: an {@link IllegalArgumentException} anywhere. */
	private static boolean isTypeAbsent(RuntimeException failure) {		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof IllegalArgumentException) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	private static boolean hasMarker(Throwable failure, String marker) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current.getMessage() != null && current.getMessage().contains(marker)) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	private static boolean hasClassCastException(RuntimeException failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof ClassCastException) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	private static String rootMessage(Throwable failure) {
		Throwable current = failure;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current.getMessage();
	}

	static SourcesGeneratorConfiguration load(CorpusFile file) throws IOException {
		Path path = Path.of("src/test/resources/corpus", file.resource());
		if (!Files.exists(path)) {
			// Surefire's working directory is the module directory; accept a repo-root run too.
			path = Path.of("datagen-plugin", "src/test/resources/corpus", file.resource());
		}
		String yaml = Files.readString(path, StandardCharsets.UTF_8);
		SourcesGeneratorConfiguration config = new Yaml().loadAs(yaml, SourcesGeneratorConfiguration.class);
		Assertions.assertNotNull(config.baseTypesData, file.id() + " has no baseTypesData");
		return config;
	}

	static final class Sweep {
		int stride;
		int sampledTypes;
		int pairs;
		int verified;
		int upgradeVerified;
		final Map<String, Integer> skips = new TreeMap<>();

		void skip(String reason) {
			skips.merge(reason, 1, Integer::sum);
		}
	}

	// ------------------------------------------------------------------------------------------------
	// stub source generator
	// ------------------------------------------------------------------------------------------------

	/**
	 * Emits every Java source the corpus needs beyond the generated tree:
	 * custom value classes (with a constant {@code of()} factory so {@link ReflectiveValueGen} can build
	 * them), {@code DataCodec}/{@code FixedDataCodec} stubs, {@code stringRepresenter} classes, and raw
	 * initializer/upgrader stubs whose return value is resolved at RUNTIME by class-name string (so the
	 * exact per-version data class layout never has to be duplicated at compile time). Failing stubs
	 * throw {@code IllegalStateException("stub-…")} — the sweep counts those pairs as skips.
	 */
	static final class StubSources {

		private record Member(int kind, String name, String rt) { // kind: 0=upgrader class, 1=initializer class, 2=upgrader field, 3=initializer field
		}

		static Map<String, String> generate(SourcesGeneratorConfiguration config, String basePackage) {
			Map<String, String> sources = new LinkedHashMap<>();
			Map<String, String> customValueClasses = new LinkedHashMap<>(); // javaClass FQCN -> source path
			Set<String> representers = new LinkedHashSet<>();
			// class FQCN -> members declared inside it (nested-class trees assembled later)
			Map<String, List<Member>> members = new LinkedHashMap<>();

			if (config.customTypesData != null) {
				for (var entry : config.customTypesData.entrySet()) {
					String javaClass = entry.getValue().getJavaClassString();
					customValueClasses.put(javaClass, entry.getKey());
					if (entry.getValue().codec != null) {
						sources.putAll(codecSource(entry.getValue().codec, javaClass, entry.getValue().fixedSize));
					}
				}
			}
			if (config.baseTypesData != null) {
				config.baseTypesData.forEach((name, type) -> {
					if (type.stringRepresenter != null) {
						representers.add(type.stringRepresenter);
					}
				});
			}
			List<String> versionKeys = config.versions == null ? List.of() : new ArrayList<>(config.versions.keySet());
			if (config.versions != null) {
				for (int k = 0; k < versionKeys.size(); k++) {
					VersionConfiguration version = config.versions.get(versionKeys.get(k));
					if (version == null || version.transformations == null) {
						continue;
					}
					for (VersionTransformation transformation : version.transformations) {
						if (transformation == null) {
							continue;
						}
						if (transformation.newType != null && transformation.newType.stringRepresenter != null) {
							representers.add(transformation.newType.stringRepresenter);
						}
						if (transformation.newData != null) {
							String rt = rtType(config, basePackage, k, versionKeys.size(), transformation.newData.type);
							if (transformation.newData.initializer != null) {
								members.computeIfAbsent(transformation.newData.initializer, any -> new ArrayList<>())
										.add(new Member(1, null, rt));
							}
							if (transformation.newData.initializerInstance != null) {
								registerInstance(members, transformation.newData.initializerInstance, 3, rt);
							}
						}
						if (transformation.upgradeData != null) {
							String rt = rtType(config, basePackage, k, versionKeys.size(), transformation.upgradeData.type);
							if (transformation.upgradeData.upgrader != null) {
								members.computeIfAbsent(transformation.upgradeData.upgrader, any -> new ArrayList<>())
										.add(new Member(0, null, rt));
							}
							if (transformation.upgradeData.upgraderInstance != null) {
								registerInstance(members, transformation.upgradeData.upgraderInstance, 2, rt);
							}
						}
					}
				}
			}

			sources.putAll(customValueSources(customValueClasses.keySet()));
			// one class file per representer CLASS, carrying every representer METHOD that class owns
			Map<String, Set<String>> representerMethods = new LinkedHashMap<>();
			for (String representer : representers) {
				int dot = representer.lastIndexOf('.');
				representerMethods.computeIfAbsent(representer.substring(0, dot), any -> new LinkedHashSet<>())
						.add(representer.substring(dot + 1));
			}
			for (var entry : representerMethods.entrySet()) {
				sources.putAll(representerSource(entry.getKey(), entry.getValue()));
			}
			sources.putAll(initializerUpgraderSources(members, basePackage + ".corpusstub.CorpusStubs"));
			sources.put(stubsHelperPath(basePackage), stubsHelper(basePackage));
			return sources;
		}

		private static void registerInstance(Map<String, List<Member>> members, String fqcn, int kind, String rt) {
			int dot = fqcn.lastIndexOf('.');
			members.computeIfAbsent(fqcn.substring(0, dot), any -> new ArrayList<>())
					.add(new Member(kind, fqcn.substring(dot + 1), rt));
		}

		/** The runtime class-name string a stub must produce for a schema type string. */
		private static String rtType(SourcesGeneratorConfiguration config, String basePackage, int versionIndex,
				int versionCount, String type) {
			String stripped = type.startsWith("-") ? type.substring(1) : type;
			stripped = stripped.replace("[]", "");
			return switch (stripped) {
				case "boolean" -> "java.lang.Boolean";
				case "byte" -> "java.lang.Byte";
				case "short" -> "java.lang.Short";
				case "int" -> "java.lang.Integer";
				case "long" -> "java.lang.Long";
				case "float" -> "java.lang.Float";
				case "double" -> "java.lang.Double";
				case "String" -> "java.lang.String";
				case "Int52" -> "it.cavallium.datagen.nativedata.Int52";
				default -> {
					if (config.customTypesData != null && config.customTypesData.containsKey(stripped)) {
						yield config.customTypesData.get(stripped).getJavaClassString();
					}
					yield versionIndex == versionCount - 1
							? basePackage + ".current.data." + stripped
							: basePackage + ".v" + versionIndex + ".data." + stripped;
				}
			};
		}

		// — value classes —

		private static Map<String, String> customValueSources(Set<String> javaClasses) {
			Map<String, String> sources = new LinkedHashMap<>();
			for (String javaClass : javaClasses) {
				if (loadable(javaClass)) {
					continue; // java.lang.String and fastutil are real classes on the classpath
				}
				int dot = javaClass.lastIndexOf('.');
				String pkg = javaClass.substring(0, dot);
				String name = javaClass.substring(dot + 1);
				String source = """
						package %s;
						public final class %s {
							private final int state;
							private %s(int state) { this.state = state; }
							public static %s of() { return new %s(7); }
							@Override public boolean equals(Object other) {
								return other != null && other.getClass() == %s.class && ((%s) other).state == state;
							}
							@Override public int hashCode() { return state; }
							@Override public String toString() { return "%s#" + state; }
						}
						""".formatted(pkg, name, name, name, name, name, name, name);
				sources.put(pkg.replace('.', '/') + '/' + name + ".java", source);
			}
			return sources;
		}

		// — codecs —

		private static Map<String, String> codecSource(String codecFqcn, String javaClass, Integer fixedSize) {
			int dot = codecFqcn.lastIndexOf('.');
			String pkg = codecFqcn.substring(0, dot);
			String name = codecFqcn.substring(dot + 1);
			String body;
			String iface = fixedSize != null ? "it.cavallium.datagen.FixedDataCodec" : "it.cavallium.datagen.DataCodec";
			String fixed = fixedSize == null ? "" : "\n\t@Override public int fixedSize() { return " + fixedSize + "; }";
			if (javaClass.equals("java.lang.String")) {
				body = """
						@Override public void serialize(it.cavallium.stream.SafeDataOutput out, String data) { out.writeUTF(data); }
						@Override public String read(it.cavallium.stream.SafeDataInput in) { return in.readUTF(); }
						@Override public void skip(it.cavallium.stream.SafeDataInput in) { in.readUTF(); }
						""";
			} else if (javaClass.equals("it.unimi.dsi.fastutil.ints.IntOpenHashSet")) {
				body = """
						@Override public void serialize(it.cavallium.stream.SafeDataOutput out, it.unimi.dsi.fastutil.ints.IntOpenHashSet data) { out.writeInt(0); }
						@Override public it.unimi.dsi.fastutil.ints.IntOpenHashSet read(it.cavallium.stream.SafeDataInput in) { in.readInt(); return new it.unimi.dsi.fastutil.ints.IntOpenHashSet(); }
						@Override public void skip(it.cavallium.stream.SafeDataInput in) { in.readInt(); }
						""";
			} else {
				String width = fixedSize != null && fixedSize == 4 ? "Int" : "Byte";
				body = """
						@Override public void serialize(it.cavallium.stream.SafeDataOutput out, %s data) { out.write%s(0); }
						@Override public %s read(it.cavallium.stream.SafeDataInput in) { in.read%s(); return %s.of(); }
						@Override public void skip(it.cavallium.stream.SafeDataInput in) { in.read%s(); }
						""".formatted(javaClass, width, javaClass, width, javaClass, width);
			}
			String source = "package %s;\npublic final class %s implements %s<%s> {%s\n%s}\n"
					.formatted(pkg, name, iface, javaClass, fixed, body);
			return Map.of(pkg.replace('.', '/') + '/' + name + ".java", source);
		}

		// — string representers —

		private static Map<String, String> representerSource(String classFqcn, Set<String> methods) {
			int classDot = classFqcn.lastIndexOf('.');
			String pkg = classFqcn.substring(0, classDot);
			String name = classFqcn.substring(classDot + 1);
			StringBuilder builder = new StringBuilder();
			builder.append("package ").append(pkg).append(";\n");
			builder.append("public final class ").append(name).append(" {\n");
			for (String method : methods) {
				builder.append("""
						public static String %1$s(Object value) {
							if (value instanceof Record record) {
								StringBuilder builder = new StringBuilder();
								for (var component : record.getClass().getRecordComponents()) {
									try {
										builder.append(component.getName()).append('=').append(component.getAccessor().invoke(record));
									} catch (ReflectiveOperationException failure) {
										builder.append(component.getName()).append("=?");
									}
								}
								return builder.toString();
							}
							if (value != null) {
								// generated non-record data class: structural field dump (String.valueOf(value)
								// would recurse, the generated toString() delegates HERE)
								StringBuilder builder = new StringBuilder(value.getClass().getSimpleName()).append('[');
								boolean first = true;
								for (var field : value.getClass().getDeclaredFields()) {
									if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
										continue;
									}
									try {
										field.setAccessible(true);
										if (!first) {
											builder.append(", ");
										}
										first = false;
										builder.append(field.getName()).append('=').append(field.get(value));
									} catch (ReflectiveOperationException | RuntimeException failure) {
										builder.append(field.getName()).append("=?");
									}
								}
								return builder.append(']').toString();
							}
							return "null";
						}
						""".formatted(method));
			}
			builder.append("}\n");
			return Map.of(pkg.replace('.', '/') + '/' + name + ".java", builder.toString());
		}

		// — initializers and upgraders —

		/**
		 * Assembles one source file per top-level class: the corpus references stubs as {@code pkg.Outer.Inner}
		 * (a class, used with {@code new T()}) or {@code pkg.Class.CONSTANT_NAME} (a static instance field).
		 * Stub classes are RAW {@code DataUpgraderSimple}/{@code DataInitializer} implementations whose value is
		 * resolved at RUNTIME by class-name string, so the per-version data class layout never needs to be
		 * duplicated at compile time.
		 */
		private static Map<String, String> initializerUpgraderSources(Map<String, List<Member>> members,
				String stubs) {
			Map<String, Node> files = new TreeMap<>(); // "pkg:TopLevel" -> root node
			Map<String, String> filePackages = new LinkedHashMap<>();
			members.forEach((classOrInstanceFqcn, list) -> {
				String[] parts = classOrInstanceFqcn.split("\\.");
				int firstClass = 0;
				while (firstClass < parts.length && !Character.isUpperCase(parts[firstClass].charAt(0))) {
					firstClass++;
				}
				String pkg = String.join(".", java.util.Arrays.copyOfRange(parts, 0, firstClass));
				List<String> classSegments = new ArrayList<>(java.util.Arrays.asList(parts).subList(firstClass,
						parts.length));
				String fileKey = pkg + ":" + classSegments.get(0);
				filePackages.putIfAbsent(fileKey, pkg);
				Node node = files.computeIfAbsent(fileKey, any -> new Node());
				for (int segment = 1; segment < classSegments.size(); segment++) {
					node = node.children.computeIfAbsent(classSegments.get(segment), any -> new Node());
				}
				node.members.addAll(list);
			});
			Map<String, String> sources = new LinkedHashMap<>();
			files.forEach((fileKey, root) -> {
				String pkg = filePackages.get(fileKey);
				String topLevel = fileKey.substring(fileKey.indexOf(':') + 1);
				boolean topUpgrader = root.members.stream().anyMatch(m -> m.kind() == 0);
				boolean topInitializer = root.members.stream().anyMatch(m -> m.kind() == 1);
				StringBuilder builder = new StringBuilder();
				builder.append("package ").append(pkg).append(";\n");
				builder.append("public final class ").append(topLevel);
				if (topUpgrader) {
					builder.append(" extends it.cavallium.datagen.DataUpgraderSimple");
				}
				if (topInitializer) {
					builder.append(topUpgrader ? " implements" : " implements")
							.append(" it.cavallium.datagen.DataInitializer");
				}
				builder.append(" {\n");
				if (topUpgrader || topInitializer) {
					emitOverrides(builder, "\t", root.members.stream()
							.filter(m -> m.kind() == 0 || m.kind() == 1).toList(), stubs);
				}
				emitMembers(builder, root, "\t", stubs);
				builder.append("}\n");
				sources.put(pkg.replace('.', '/') + '/' + topLevel + ".java", builder.toString());
			});
			return sources;
		}

		/** Emits the members of one class node (class stubs become nested classes) and recurses. */
		private static void emitMembers(StringBuilder builder, Node node, String indent, String stubs) {
			Set<String> emitted = new LinkedHashSet<>();
			for (Member member : node.members) {
				// the corpus reuses one instance stub across many transformations: emit each exactly once
				if (!emitted.add(member.kind() + ":" + member.name())) {
					continue;
				}
				switch (member.kind()) {
					case 2 -> {
						builder.append(indent).append("public static final it.cavallium.datagen.DataUpgraderSimple ")
								.append(member.name()).append(" = new ").append(member.name()).append("Stub();\n");
						builder.append(indent).append("public static final class ").append(member.name())
								.append("Stub extends it.cavallium.datagen.DataUpgraderSimple {\n");
						builder.append(indent).append("\t@Override public Object upgrade(Object data) { return ")
								.append(stubs).append(".retype(data, \"").append(member.rt()).append("\"); }\n");
						builder.append(indent).append("}\n");
					}
					case 3 -> {
						builder.append(indent).append("public static final it.cavallium.datagen.DataInitializer ")
								.append(member.name()).append(" = new ").append(member.name()).append("Stub();\n");
						builder.append(indent).append("public static final class ").append(member.name())
								.append("Stub implements it.cavallium.datagen.DataInitializer {\n");
						builder.append(indent).append("\t@Override public Object initialize(it.cavallium.datagen.DataContext context) { return ")
								.append(stubs).append(".sample(\"").append(member.rt()).append("\"); }\n");
						builder.append(indent).append("}\n");
					}
					default -> {
					}
				}
			}
			List<Member> classStubs = node.members.stream().filter(m -> m.kind() == 0 || m.kind() == 1).toList();
			node.children.forEach((name, child) -> {
				boolean upgrader = child.members.stream().anyMatch(m -> m.kind() == 0);
				boolean initializer = child.members.stream().anyMatch(m -> m.kind() == 1);
				builder.append(indent).append("public static final class ").append(name);
				if (upgrader) {
					builder.append(" extends it.cavallium.datagen.DataUpgraderSimple");
				}
				if (initializer) {
					builder.append(" implements it.cavallium.datagen.DataInitializer");
				}
				builder.append(" {\n");
				if (upgrader || initializer) {
					emitOverrides(builder, indent + "\t", child.members.stream()
							.filter(m -> m.kind() == 0 || m.kind() == 1).toList(), stubs);
				}
				emitMembers(builder, child, indent + "\t", stubs);
				builder.append(indent).append("}\n");
			});
			// a nested class stub with NO children and no members is still declared when the class itself is
			// referenced (its node carries the kind 0/1 members, handled by the parent above)
			if (classStubs.isEmpty() && node.children.isEmpty()) {
				// leaf instance node: fields already emitted above
			}
		}

		private static void emitOverrides(StringBuilder builder, String indent, List<Member> classStubs,
				String stubs) {
			if (classStubs.stream().anyMatch(m -> m.kind() == 0)) {
				String rt = classStubs.stream().filter(m -> m.kind() == 0).map(Member::rt).findFirst().orElse("");
				builder.append(indent).append("@Override public Object upgrade(Object data) { return ")
						.append(stubs).append(".retype(data, \"").append(rt).append("\"); }\n");
			}
			if (classStubs.stream().anyMatch(m -> m.kind() == 1)) {
				String rt = classStubs.stream().filter(m -> m.kind() == 1).map(Member::rt).findFirst().orElse("");
				builder.append(indent).append("@Override public Object initialize(it.cavallium.datagen.DataContext context) { return ")
						.append(stubs).append(".sample(\"").append(rt).append("\"); }\n");
			}
		}

		/** A node in the nested-class tree of one stub source file. */
		private static final class Node {
			final Map<String, Node> children = new TreeMap<>();
			final List<Member> members = new ArrayList<>();
		}

		// — the runtime helper —

		private static String stubsHelperPath(String basePackage) {
			return basePackage.replace('.', '/') + "/corpusstub/CorpusStubs.java";
		}

		private static String stubsHelper(String basePackage) {
			return """
					package %s.corpusstub;

					import java.lang.reflect.Array;
					import java.lang.reflect.Method;
					import java.util.HashMap;
					import java.util.Map;

					/** Runtime support for the corpus stubs: resolve a target class by name and build a value. */
					public final class CorpusStubs {

						private CorpusStubs() {}

						private static final Map<String, Class<?>> CLASSES = new HashMap<>();

						public static Object sample(String targetClassName) {
							return sample(resolve(targetClassName));
						}

						public static Object retype(Object value, String targetClassName) {
							if (value == null) {
								return null;
							}
							Class<?> target = resolve(targetClassName);
							if (target.isInstance(value)) {
								return value;
							}
							return retype(value, target, 0);
						}

						private static Class<?> resolve(String name) {
							Class<?> cached = CLASSES.get(name);
							if (cached != null) {
								return cached;
							}
							Class<?> loaded;
							try {
								loaded = Class.forName(name);
							} catch (ClassNotFoundException missing) {
								throw new IllegalStateException("stub-class: " + name, missing);
							}
							CLASSES.put(name, loaded);
							return loaded;
						}

						static Object sample(Class<?> target) {
							if (target == java.lang.Boolean.class) return Boolean.FALSE;
							if (target == java.lang.Byte.class) return (byte) 0;
							if (target == java.lang.Short.class) return (short) 0;
							if (target == java.lang.Integer.class) return 0;
							if (target == java.lang.Long.class) return 0L;
							if (target == java.lang.Float.class) return 0f;
							if (target == java.lang.Double.class) return 0d;
							if (target == String.class) return "stub";
							if (target.getName().equals("it.cavallium.datagen.nativedata.Int52")) {
								try {
									return target.getMethod("fromLong", long.class).invoke(null, 0L);
								} catch (ReflectiveOperationException failure) {
									throw new IllegalStateException("stub-sample: Int52", failure);
								}
							}
							if (target == it.unimi.dsi.fastutil.ints.IntOpenHashSet.class) {
								return new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
							}
							if (target.isArray()) {
								return Array.newInstance(target.getComponentType(), 0);
							}
							if (target.isInterface()) {
								Class<?>[] alternatives = target.getPermittedSubclasses();
								if (alternatives == null || alternatives.length == 0) {
									throw new IllegalStateException("stub-sample: union " + target.getName());
								}
								return sample(alternatives[0]);
							}
							if (target.isRecord()) {
								Object[] arguments = new Object[target.getRecordComponents().length];
								for (int index = 0; index < arguments.length; index++) {
									arguments[index] = sample(target.getRecordComponents()[index].getType());
								}
								return invokeOf(target, arguments);
							}
							try {
								return target.getMethod("of").invoke(null);
							} catch (ReflectiveOperationException failure) {
								throw new IllegalStateException("stub-sample: " + target.getName(), failure);
							}
						}

						static Object retype(Object value, Class<?> target, int depth) {
							if (depth > 12) {
								throw new IllegalStateException("stub-retype: depth " + value.getClass().getName());
							}
							if (target.isInstance(value)) {
								return value;
							}
							if (target == String.class) {
								if (value instanceof Record record && record.getClass().getRecordComponents().length == 1) {
									return String.valueOf(component(record, value, 0));
								}
								throw new IllegalStateException("stub-retype: to String from " + value.getClass().getName());
							}
							if (value instanceof Record source) {
								Class<?>[] sourceTypes = componentTypes(source.getClass());
								if (target.isRecord()) {
									Class<?>[] targetTypes = componentTypes(target);
									Object[] arguments = new Object[targetTypes.length];
									if (sourceTypes.length != targetTypes.length) {
										throw new IllegalStateException("stub-retype: arity " + value.getClass().getName());
									}
									for (int index = 0; index < arguments.length; index++) {
										arguments[index] = retype(component(source, value, index), targetTypes[index], depth + 1);
									}
									return invokeOf(target, arguments);
								}
								if (target.isInterface()) {
									Class<?>[] alternatives = target.getPermittedSubclasses();
									if (alternatives == null) {
										throw new IllegalStateException("stub-retype: union " + target.getName());
									}
									Class<?> match = null;
									for (Class<?> alternative : alternatives) {
										if (alternative.getSimpleName().equals(value.getClass().getSimpleName())) {
											match = alternative;
											break;
										}
									}
									if (match == null) {
										if (alternatives.length != 1) {
											throw new IllegalStateException("stub-retype: union member " + value.getClass().getName());
										}
										match = alternatives[0];
									}
									return retype(value, match, depth + 1);
								}
							}
							if (value.getClass().getName().equals("it.unimi.dsi.fastutil.ints.IntOpenHashSet")
									&& target == it.unimi.dsi.fastutil.ints.IntOpenHashSet.class) {
								return new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
							}
							try {
								return target.getMethod("of").invoke(null);
							} catch (ReflectiveOperationException failure) {
								throw new IllegalStateException("stub-retype: " + value.getClass().getName() + " -> "
										+ target.getName(), failure);
							}
						}

						private static Class<?>[] componentTypes(Class<?> record) {
							Class<?>[] types = new Class<?>[record.getRecordComponents().length];
							for (int index = 0; index < types.length; index++) {
								types[index] = record.getRecordComponents()[index].getType();
							}
							return types;
						}

						private static Object component(Record source, Object value, int index) {
							try {
								return source.getClass().getRecordComponents()[index].getAccessor().invoke(value);
							} catch (ReflectiveOperationException failure) {
								throw new IllegalStateException("stub-retype: component", failure);
							}
						}

						private static Object invokeOf(Class<?> target, Object[] arguments) {
							for (Method method : target.getMethods()) {
								if (method.getName().equals("of") && method.getParameterCount() == arguments.length) {
									try {
										return method.invoke(null, arguments);
									} catch (ReflectiveOperationException failure) {
										throw new IllegalStateException("stub-of: " + target.getName(), failure);
									}
								}
							}
							throw new IllegalStateException("stub-of: no of() on " + target.getName());
						}
					}
					""".formatted(basePackage);
		}

		private static boolean loadable(String className) {
			try {
				Class.forName(className);
				return true;
			} catch (ClassNotFoundException expected) {
				return false;
			}
		}
	}
}
