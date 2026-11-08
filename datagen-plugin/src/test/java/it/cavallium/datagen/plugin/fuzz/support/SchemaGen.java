package it.cavallium.datagen.plugin.fuzz.support;

import it.cavallium.datagen.plugin.ClassConfiguration;
import it.cavallium.datagen.plugin.CustomTypesConfiguration;
import it.cavallium.datagen.plugin.DetailsConfiguration;
import it.cavallium.datagen.plugin.InterfaceDataConfiguration;
import it.cavallium.datagen.plugin.MoveDataConfiguration;
import it.cavallium.datagen.plugin.NewDataConfiguration;
import it.cavallium.datagen.plugin.NewSubtypeConfiguration;
import it.cavallium.datagen.plugin.NewTypeConfiguration;
import it.cavallium.datagen.plugin.ProjectionConfiguration;
import it.cavallium.datagen.plugin.ReadTransformConfiguration;
import it.cavallium.datagen.plugin.RemoveDataConfiguration;
import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;
import it.cavallium.datagen.plugin.UpgradeDataConfiguration;
import it.cavallium.datagen.plugin.VersionConfiguration;
import it.cavallium.datagen.plugin.VersionTransformation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import it.cavallium.datagen.plugin.fuzz.support.SchemaGenProfile.Feature;

/**
 * Random schema synthesizer: produces a {@link SourcesGeneratorConfiguration} that always passes
 * {@code buildDataModel(false)}, from a {@link SchemaGenProfile} and a {@link Random}.
 *
 * <p>It is the typed replacement for the string-building generator inside
 * {@code SourcesGeneratorSchemaDeepFuzzTest}: same vocabulary (records {@code G?T0..}, unions {@code U..}, versions
 * {@code v1..}, {@code newData}/{@code moveData}/{@code removeData}/{@code upgradeData}/{@code newType}/
 * {@code newSubtype}), but the result is the configuration model, so callers can emit it with
 * {@link SchemaYamlEmitter}, canonicalize it with {@link SchemaCanonicalizer} or hand it to {@link SchemaCompiler}
 * without ever parsing text.</p>
 *
 * <h2>Rules that must never break (each one mirrors {@code DataModel})</h2>
 * <ul>
 *   <li>one linear version chain whose last version is {@code currentVersion}; the root version has no
 *   transformations ({@code DataModel:139});</li>
 *   <li>every type name is unique across base, super, custom and native names ({@code DataModel:277}) and is never a
 *   native name such as {@code int};</li>
 *   <li>{@code baseTypesData} is the <em>version one</em> shape: transformations are applied on top of it, so the
 *   generator keeps a version-one snapshot for output and a live copy for simulation;</li>
 *   <li>a field's plain type exists in the version where that version's snapshot declares it
 *   ({@code validateActiveTypeReferences}): records only reference already-created types;</li>
 *   <li>{@code newSubtype} only appends: the union exists, the subtype exists by then, is not already an alternative,
 *   and the union stays within 256 alternatives;</li>
 *   <li>{@code stringRepresenter} names a field the record keeps in every version, so it is only set on records no
 *   transformation touches;</li>
 *   <li>{@code commonData}/{@code commonGetters} of a union interface are real data of EVERY alternative — the way
 *   {@code ChatEntityId.tableId} is data of {@code SuperCollectionId}, {@code UserId}, … — so the fields are declared
 *   in version one of every member (also of every union extending it through {@code extendInterfaces}), are never
 *   moved or removed, and only unions that can guarantee them get them;</li>
 *   <li>no nullable array: {@code -T[]} is rejected by {@code DataModel.fixType}, so it is never emitted;</li>
 *   <li>{@code contextParameters} only on {@code newData}/{@code upgradeData}, naming fields the record already has
 *   at that point;</li>
 *   <li>every {@link VersionTransformation} holds exactly one operation and every {@code readTransform} node holds
 *   exactly one operation; {@code construct} gets exactly one of {@code type}/{@code className} and {@code type:} is
 *   only ever a native name;</li>
 *   <li>a {@code readTransform} only ever comes from the value-conserving shapes of its paired plain location
 *   ({@link ReadPairedLocation}), because the fused read and the initializer/upgrader path must return the same
 *   value;</li>
 *   <li>projections have at least one field, valid Java identifier names, and paths that resolve in every version —
 *   they only read records that exist since version one and that no transformation ever touched.</li>
 * </ul>
 *
 * <p>Deterministic: the same {@code (seed, profile)} pair yields the same configuration, hence byte-identical
 * {@link SchemaYamlEmitter#emit} output. Only {@link Random} drives the shape, consumed in a fixed order, and no
 * hash-ordered collection is iterated.</p>
 */
public final class SchemaGen {

	private SchemaGen() {
	}

	/**
	 * Same content as the private {@code DataModel.NATIVE_TYPES}; pinned against it by
	 * {@code SchemaGenTest.nativeNamesMatchTheModel}.
	 */
	static final List<String> NATIVE_TYPES = List.of("String", "boolean", "short", "char", "int", "long", "float",
			"double", "byte", "Int52");

	/** Transform targets from this module's test sources, so generated code stays compilable. */
	static final String LONG_INITIALIZER = "it.cavallium.datagen.plugin.TestSimpleLongInitializer";
	static final String CONTEXT_LONG_INITIALIZER = "it.cavallium.datagen.plugin.TestContextLongInitializer";
	static final String INT_TO_LONG_UPGRADER = "it.cavallium.datagen.plugin.TestSimpleIntToLongUpgrader";
	static final String CONTEXT_INT_TO_LONG_UPGRADER = "it.cavallium.datagen.plugin.TestContextIntToLongUpgrader";
	static final String INT_TO_NULLABLE_LONG_UPGRADER = "it.cavallium.datagen.plugin.TestIntToNullableLongUpgrader";
	/**
	 * Instance-form locations are resolved while generating sources, never by {@code buildDataModel}, so the field
	 * does not have to exist for the schema to validate.
	 */
	static final String INSTANCE_INITIALIZER = "it.cavallium.datagen.plugin.fuzz.FuzzInstanceLocations.LONG";
	static final String INSTANCE_UPGRADER = "it.cavallium.datagen.plugin.fuzz.FuzzInstanceLocations.INT_TO_LONG";
	/** Static targets a {@code readTransform} may call; they mirror the initializer/upgrader values. */
	static final String TORTURE_TRANSFORMS = "it.cavallium.datagen.plugin.TortureTransforms";
	/** Custom types need a value class plus a {@code FixedDataCodec}; this pair is the one SourcesGeneratorTest uses. */
	static final String CUSTOM_JAVA_CLASS = "java.lang.Integer";
	static final String CUSTOM_CODEC = "it.cavallium.datagen.plugin.TestFixedIntCodec";
	/** Context parameter the shared {@code TestContext*} transforms read through {@code context.messageId()}. */
	static final String CONTEXT_FIELD = "messageId";
	/**
	 * The two interface-bound fields a union with {@code commonData}/{@code commonGetters} demands. Production is the
	 * model: {@code ChatEntityId}'s {@code commonData: tableId} is a real field of every alternative, so these are
	 * emitted as version-one data of every member and are never moved or removed afterwards.
	 */
	static final String COMMON_DATA_FIELD = "c0";
	static final String COMMON_GETTER_FIELD = "c1";

	/**
	 * The plain initializer/upgrader a {@code readTransform} is paired with, and the value that location computes.
	 *
	 * <p>A transformation that carries BOTH a {@code readTransform} and a plain location is read two different ways:
	 * the fused {@code CurrentVersion.read} compiles the declarative transform, while {@code GenUpgraderBaseX}
	 * resolves the plain location and the codec-read + {@code upgradeDataToLatestVersion} path calls it. The two
	 * values must be equal or the {@code GeneratedOracles.upgradeEquivalence} oracle fails, so a {@code readTransform}
	 * is only ever drawn from the value-conserving shapes of its paired location — pinned by
	 * {@code SchemaGenTest.readTransformsAgreeWithTheirPlainLocation}.</p>
	 */
	enum ReadPairedLocation {
		/** {@link #LONG_INITIALIZER}: {@code initialize(context) == 123}, independent of any context. */
		INITIALIZED_LONG,
		/** {@link #INT_TO_LONG_UPGRADER}: {@code upgrade(old) == old + 1000}. */
		UPGRADED_FROM_INT
	}

	public static SourcesGeneratorConfiguration generate(Random rnd, SchemaGenProfile profile) {
		return generate(rnd, profile, null);
	}

	/** Generates a schema and, when {@code coverage} is not {@code null}, records the features it contains. */
	public static SourcesGeneratorConfiguration generate(Random rnd, SchemaGenProfile profile, Coverage coverage) {
		Objects.requireNonNull(rnd, "rnd");
		Objects.requireNonNull(profile, "profile");
		var config = new State(rnd, profile).build();
		if (coverage != null) {
			coverage.record(config);
		}
		return config;
	}

	/** Features present in {@code configs}; the counterpart of {@code AdversarialEvolutionTest.EvolutionCoverage}. */
	public static Set<Feature> coverageSummary(Iterable<SourcesGeneratorConfiguration> configs) {
		Coverage coverage = new Coverage();
		for (SourcesGeneratorConfiguration config : configs) {
			coverage.record(config);
		}
		return coverage.seen();
	}

	/** Accumulates the {@link Feature}s observed across generated schemas, mirroring {@code EvolutionCoverage}. */
	public static final class Coverage {

		private final EnumSet<Feature> seen = EnumSet.noneOf(Feature.class);

		public Coverage record(SourcesGeneratorConfiguration config) {
			detect(Objects.requireNonNull(config, "config"), seen);
			return this;
		}

		public Set<Feature> seen() {
			return seen.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(seen);
		}

		public Set<Feature> missing() {
			EnumSet<Feature> missing = EnumSet.allOf(Feature.class);
			missing.removeAll(seen);
			return missing;
		}

		/** Human-readable summary for assertion messages. */
		public String summary() {
			return "seen=" + names(seen) + " missing=" + names(missing());
		}

		private static Set<String> names(Set<Feature> features) {
			return new TreeSet<>(features.stream().map(Enum::name).toList());
		}
	}

	// ------------------------------------------------------------------------------------------------
	// feature detection, done on the produced configuration
	// ------------------------------------------------------------------------------------------------

	private static void detect(SourcesGeneratorConfiguration config, Collection<Feature> sink) {
		if (isNotEmpty(config.superTypesData)) {
			sink.add(Feature.UNIONS);
		}
		if (isNotEmpty(config.customTypesData)) {
			sink.add(Feature.CUSTOM_TYPES);
		}
		if (isNotEmpty(config.projectionsData)) {
			sink.add(Feature.PROJECTIONS);
		}
		if (isNotEmpty(config.interfacesData)) {
			sink.add(Feature.INTERFACES);
		}
		Map<String, List<String>> edges = new LinkedHashMap<>();
		if (config.baseTypesData != null) {
			config.baseTypesData.forEach((typeName, type) -> {
				if (type == null) {
					return;
				}
				if (isFieldReference(type.stringRepresenter)) {
					sink.add(Feature.STRING_REPRESENTERS);
				}
				classify(type.data, typeName, sink, edges);
			});
		}
		if (config.versions == null) {
			return;
		}
		if (config.versions.size() > 1) {
			sink.add(Feature.MULTI_VERSION);
		}
		for (VersionConfiguration version : config.versions.values()) {
			if (version == null) {
				continue;
			}
			if (version.details != null && version.details.changelog != null && !version.details.changelog.isBlank()) {
				sink.add(Feature.VERSION_DETAILS);
			}
			if (version.transformations == null) {
				continue;
			}
			for (VersionTransformation transformation : version.transformations) {
				detectTransformation(transformation, sink);
				if (transformation != null && transformation.newType != null) {
					sink.add(Feature.NEW_TYPES);
					if (isFieldReference(transformation.newType.stringRepresenter)) {
						sink.add(Feature.STRING_REPRESENTERS);
					}
					classify(transformation.newType.data, transformation.newType.name, sink, edges);
				}
			}
		}
		if (hasCycle(edges)) {
			sink.add(Feature.RECURSION);
		}
	}

	private static void detectTransformation(VersionTransformation transformation, Collection<Feature> sink) {
		if (transformation == null) {
			return;
		}
		if (transformation.newSubtype != null) {
			sink.add(Feature.UNION_EXTENSIONS);
		}
		if (transformation.moveData != null) {
			sink.add(Feature.DATA_MOVES);
			if (transformation.moveData.index != null) {
				sink.add(Feature.INDEX_INSERTION);
			}
		}
		if (transformation.removeData != null) {
			sink.add(Feature.DATA_REMOVALS);
		}
		var newData = transformation.newData;
		if (newData != null) {
			sink.add(Feature.DATA_ADDS);
			if (newData.initializerInstance != null) {
				sink.add(Feature.INSTANCE_FORM_UPGRADERS);
			}
			if (newData.index != null) {
				sink.add(Feature.INDEX_INSERTION);
			}
			if (newData.contextParameters != null && !newData.contextParameters.isEmpty()) {
				sink.add(Feature.CONTEXT_PARAMETERS);
			}
			if (newData.readTransform != null) {
				sink.add(Feature.READ_TRANSFORMS);
			}
			classifyField(newData.type, sink);
		}
		var upgradeData = transformation.upgradeData;
		if (upgradeData != null) {
			sink.add(Feature.DATA_UPGRADES);
			if (upgradeData.upgraderInstance != null) {
				sink.add(Feature.INSTANCE_FORM_UPGRADERS);
			}
			if (upgradeData.contextParameters != null && !upgradeData.contextParameters.isEmpty()) {
				sink.add(Feature.CONTEXT_PARAMETERS);
			}
			if (upgradeData.readTransform != null) {
				sink.add(Feature.READ_TRANSFORMS);
			}
			classifyField(upgradeData.type, sink);
		}
	}

	private static void classify(Map<String, String> data, String owner, Collection<Feature> sink,
			Map<String, List<String>> edges) {
		if (data == null) {
			return;
		}
		data.forEach((fieldName, rawType) -> {
			classifyField(rawType, sink);
			String plain = plainType(rawType);
			if (owner != null && isSchemaTypeName(plain)) {
				edges.computeIfAbsent(owner, key -> new ArrayList<>()).add(plain);
			}
		});
	}

	private static void classifyField(String rawType, Collection<Feature> sink) {
		if (rawType == null || rawType.isBlank()) {
			return;
		}
		boolean nullable = rawType.startsWith("-");
		boolean array = rawType.endsWith("[]");
		String plain = plainType(rawType);
		if (NATIVE_TYPES.contains(plain)) {
			if (array) {
				sink.add(Feature.ARRAY_SCALAR);
			} else if (nullable) {
				sink.add(Feature.NULLABLE_SCALAR);
			}
		} else if (array) {
			sink.add(Feature.ARRAY_VERSIONED);
		} else if (nullable) {
			sink.add(Feature.NULLABLE_VERSIONED);
		}
		if (plain.equals("Int52")) {
			sink.add(Feature.INT52);
		}
	}

	static String plainType(String rawType) {
		String value = rawType.startsWith("-") || rawType.startsWith("§") ? rawType.substring(1) : rawType;
		return value.endsWith("[]") ? value.substring(0, value.length() - 2) : value;
	}

	private static boolean isSchemaTypeName(String name) {
		return name != null && !name.isBlank() && !NATIVE_TYPES.contains(name);
	}

	private static boolean isFieldReference(String value) {
		return value != null && !value.isBlank() && !value.contains(".");
	}

	private static boolean isNotEmpty(Map<String, ?> map) {
		return map != null && !map.isEmpty();
	}

	/** Cycle detection over the record reference graph; a record that references itself counts as recursion. */
	private static boolean hasCycle(Map<String, List<String>> edges) {
		Set<String> visiting = new HashSet<>();
		Set<String> done = new HashSet<>();
		for (String start : edges.keySet()) {
			if (reachesItself(edges, start, visiting, done)) {
				return true;
			}
		}
		return false;
	}

	private static boolean reachesItself(Map<String, List<String>> edges, String node, Set<String> visiting,
			Set<String> done) {
		if (done.contains(node)) {
			return false;
		}
		if (!visiting.add(node)) {
			return true;
		}
		for (String next : edges.getOrDefault(node, List.of())) {
			if (reachesItself(edges, next, visiting, done)) {
				return true;
			}
		}
		visiting.remove(node);
		done.add(node);
		return false;
	}

	// ------------------------------------------------------------------------------------------------
	// generation
	// ------------------------------------------------------------------------------------------------

	/** One generation: the bookkeeping that mirrors what {@code DataModel} computes. */
	private static final class State {

		private final Random rnd;
		private final SchemaGenProfile profile;
		private final String prefix;
		private final int minTypes;
		private final int maxTypes;
		private final int minFields;
		private final int maxFields;
		private final int versionCount;
		private final boolean allowInt52;
		private final boolean allowRecursion;
		private final LinkedHashMap<String, LinkedHashMap<String, String>> live = new LinkedHashMap<>();
		private final LinkedHashMap<String, LinkedHashMap<String, String>> initial = new LinkedHashMap<>();
		private final Map<String, Integer> nextFieldIndex = new HashMap<>();
		private final Map<String, Integer> depth = new HashMap<>();
		private final LinkedHashMap<String, List<String>> unions = new LinkedHashMap<>();
		private final LinkedHashMap<String, List<String>> initialUnions = new LinkedHashMap<>();
		/** Decided with the unions: an interface with common data needs those fields as data of every member. */
		private final LinkedHashMap<String, InterfaceDataConfiguration> interfacePlan = new LinkedHashMap<>();
		private final Set<String> commonFieldUnions = new LinkedHashSet<>();
		private final Set<String> extendingUnions = new LinkedHashSet<>();
		private final LinkedHashMap<String, CustomTypesConfiguration> customs = new LinkedHashMap<>();
		private final Set<String> touched = new HashSet<>();
		private String reserved;
		private final LinkedHashMap<String, VersionConfiguration> versions = new LinkedHashMap<>();
		private final EnumSet<Feature> emitted = EnumSet.noneOf(Feature.class);
		private final List<Feature> pendingOperations = new ArrayList<>();
		private int newTypeCounter;
		private int projectionCounter;
		private int customCounter;

		State(Random rnd, SchemaGenProfile profile) {
			this.rnd = rnd;
			this.profile = profile;
			this.prefix = "G" + Long.toString(rnd.nextLong() & 0x7ffffffL, 36);
			boolean needsFields = profile.forced(Feature.DATA_MOVES)
					|| profile.forced(Feature.DATA_REMOVALS)
					|| profile.forced(Feature.DATA_UPGRADES)
					|| profile.forced(Feature.CONTEXT_PARAMETERS);
			this.minFields = needsFields ? Math.max(profile.minFields(), 2) : profile.minFields();
			this.maxFields = Math.max(profile.maxFields(), this.minFields);
			// A union extension needs a union, and a union needs two members plus one record outside it.
			this.minTypes = profile.forced(Feature.UNION_EXTENSIONS) || profile.forced(Feature.INTERFACES)
					? Math.max(profile.minTypes(), 3)
					: profile.minTypes();
			this.maxTypes = Math.max(profile.maxTypes(), this.minTypes);
			this.allowInt52 = profile.allowInt52() || profile.forced(Feature.INT52);
			this.allowRecursion = profile.allowRecursion()
					|| profile.forced(Feature.RECURSION)
					|| profile.forced(Feature.NULLABLE_VERSIONED)
					|| profile.forced(Feature.ARRAY_VERSIONED);
			this.versionCount = versionCount();
			for (Feature feature : List.of(Feature.DATA_ADDS, Feature.DATA_MOVES, Feature.DATA_REMOVALS,
					Feature.DATA_UPGRADES, Feature.NEW_TYPES, Feature.UNION_EXTENSIONS)) {
				if (profile.forced(feature)) {
					pendingOperations.add(feature);
				}
			}
		}

		SourcesGeneratorConfiguration build() {
			createCustomTypes();
			createInitialTypes();
			createUnions();
			createVersions();
			repairForcedFeatures();

			var config = new SourcesGeneratorConfiguration();
			config.currentVersion = "v" + versionCount;
			config.versions = new LinkedHashMap<>(versions);
			config.baseTypesData = baseTypes();
			if (!customs.isEmpty()) {
				config.customTypesData = new LinkedHashMap<>(customs);
			}
			if (!initialUnions.isEmpty()) {
				config.superTypesData = copies(initialUnions);
			}
			var interfaces = interfaces();
			if (interfaces != null) {
				config.interfacesData = interfaces;
			}
			var projections = projections();
			if (projections != null) {
				config.projectionsData = projections;
			}
			return config;
		}

		// ---- sizing --------------------------------------------------------------------------------

		private int versionCount() {
			boolean needsVersions = profile.forced(Feature.MULTI_VERSION)
					|| profile.forced(Feature.DATA_ADDS)
					|| profile.forced(Feature.DATA_MOVES)
					|| profile.forced(Feature.DATA_REMOVALS)
					|| profile.forced(Feature.DATA_UPGRADES)
					|| profile.forced(Feature.NEW_TYPES)
					|| profile.forced(Feature.UNION_EXTENSIONS)
					|| profile.forced(Feature.READ_TRANSFORMS)
					|| profile.forced(Feature.CONTEXT_PARAMETERS)
					|| profile.forced(Feature.INSTANCE_FORM_UPGRADERS)
					|| profile.forced(Feature.INDEX_INSERTION);
			int count = between(profile.minVersions(), profile.maxVersions());
			return needsVersions ? Math.max(count, Math.min(2, profile.maxVersions())) : count;
		}

		private int between(int min, int max) {
			return min + rnd.nextInt(max - min + 1);
		}

		private boolean chance(int percent) {
			return rnd.nextInt(100) < percent;
		}

		/** A forced feature is always on; otherwise the profile's allowance plus the tier's probability decide. */
		private boolean enabled(Feature feature, boolean allowance, int percent) {
			return profile.forced(feature) || allowance && chance(percent);
		}

		private <T> T pick(List<T> values) {
			return values.isEmpty() ? null : values.get(rnd.nextInt(values.size()));
		}

		// ---- custom types --------------------------------------------------------------------------

		private void createCustomTypes() {
			if (!enabled(Feature.CUSTOM_TYPES, profile.allowCustomTypes(), 40)) {
				return;
			}
			int count = between(1, 2);
			for (int index = 0; index < count; index++) {
				var custom = new CustomTypesConfiguration();
				custom.setJavaClass(CUSTOM_JAVA_CLASS);
				custom.codec = CUSTOM_CODEC;
				custom.fixedSize = Integer.BYTES;
				customs.put(prefix + "C" + customCounter++, custom);
			}
		}

		// ---- version one ---------------------------------------------------------------------------

		private void createInitialTypes() {
			int typeCount = between(minTypes, maxTypes);
			for (int index = 0; index < typeCount; index++) {
				createType(prefix + "T" + index, profile.forced(Feature.RECURSION) && index == 0);
			}
			appendForcedFieldKinds();
			// One version-one record that no transformation may touch: it is the projection source that forced
			// PROJECTIONS can always rely on, and its fields stay resolvable in every version.
			reserved = live.keySet().stream().filter(name -> name.startsWith(prefix + "T")).reduce((a, b) -> b).orElse(null);
		}

		private void createType(String name, boolean mustRecurse) {
			var fields = new LinkedHashMap<String, String>();
			live.put(name, fields);
			initial.put(name, new LinkedHashMap<>());
			depth.put(name, 1);
			nextFieldIndex.put(name, 0);
			if (profile.allowStringRepresenters() || profile.forced(Feature.STRING_REPRESENTERS)) {
				// A representer must name a field, and only a String field is usable by the generated toString.
				fields.put(nextField(name), "String");
			}
			if (chance(40)) {
				// The shared TestContext* transforms read a long `messageId` context parameter.
				if (!fields.containsKey(CONTEXT_FIELD)) {
					fields.put(CONTEXT_FIELD, "long");
					bumpFieldIndex(name);
				}
			}
			int fieldCount = Math.max(between(minFields, maxFields), fields.size());
			while (fields.size() < fieldCount) {
				fields.put(nextField(name), fieldType(name, mustRecurse));
			}
			initial.get(name).putAll(fields);
		}

		/** Guarantees every forced field-level feature shows up at least once. */
		private void appendForcedFieldKinds() {
			String type = live.keySet().iterator().next();
			var fields = live.get(type);
			for (Feature feature : profile.forcedFeatures()) {
				String rawType = switch (feature) {
					case NULLABLE_SCALAR -> "-" + aNative();
					case NULLABLE_VERSIONED -> "-" + indirectReference(type);
					case ARRAY_SCALAR -> aNative() + "[]";
					case ARRAY_VERSIONED -> indirectReference(type) + "[]";
					case INT52 -> "Int52";
					default -> null;
				};
				if (rawType != null) {
					fields.put(nextField(type), rawType);
				}
			}
			initial.get(type).putAll(fields);
		}

		private String nextField(String type) {
			int index = nextFieldIndex.merge(type, 1, Integer::sum) - 1;
			return "f" + index;
		}

		private void bumpFieldIndex(String type) {
			nextFieldIndex.merge(type, 1, Integer::sum);
		}

		private String aNative() {
			List<String> natives = new ArrayList<>(NATIVE_TYPES);
			if (!allowInt52) {
				natives.remove("Int52");
			}
			return natives.get(rnd.nextInt(natives.size()));
		}

		private List<String> schemaTypes() {
			List<String> names = new ArrayList<>(live.keySet());
			names.addAll(customs.keySet());
			return names;
		}

		/** An existing schema type; {@code indirect} also allows the owner, which is only legal behind a wrapper. */
		private String reference(String owner, boolean excludeSelf) {
			List<String> candidates = schemaTypes();
			if (excludeSelf) {
				candidates.remove(owner);
			}
			return candidates.isEmpty() ? aNative() : candidates.get(rnd.nextInt(candidates.size()));
		}

		private String indirectReference(String owner) {
			return allowRecursion ? reference(owner, false) : reference(owner, true);
		}

		/** A field type whose plain part already exists and whose nesting stays within {@code maxDepth}. */
		private String fieldType(String owner, boolean preferSelf) {
			if (preferSelf && allowRecursion) {
				return chance(50) ? "-" + owner : owner + "[]";
			}
			int roll = rnd.nextInt(100);
			if (profile.allowNullable() && roll < 22) {
				return "-" + indirectReference(owner);
			}
			if (profile.allowArrays() && roll < 40) {
				return indirectReference(owner) + "[]";
			}
			if (roll < 70 || profile.maxDepth() <= 1) {
				return aNative();
			}
			return directReference(owner);
		}

		/** A plain (direct) reference, which is what makes a record graph deep; capped by {@code maxDepth}. */
		private String directReference(String owner) {
			List<String> candidates = new ArrayList<>();
			for (String candidate : live.keySet()) {
				if (!candidate.equals(owner) && depth.getOrDefault(candidate, 1) + 1 <= profile.maxDepth()) {
					candidates.add(candidate);
				}
			}
			if (candidates.isEmpty() || chance(45)) {
				return aNative();
			}
			String target = candidates.get(rnd.nextInt(candidates.size()));
			depth.merge(owner, depth.getOrDefault(target, 1) + 1, Integer::max);
			return target;
		}

		private void createUnions() {
			boolean needsExtension = profile.forced(Feature.UNION_EXTENSIONS);
			boolean needsUnion = needsExtension || profile.forced(Feature.INTERFACES);
			if (profile.maxUnionAlternatives() < 2 || live.size() < (needsUnion ? 3 : 2)
					|| !(enabled(Feature.UNIONS, true, 50) || needsUnion)) {
				return;
			}
			int unionCount = Math.min(between(profile.minUnions(), profile.maxUnions()),
					Math.max(1, live.size() / 2));
			List<String> members = new ArrayList<>(live.keySet());
			for (int index = 0; index < unionCount; index++) {
				Collections.shuffle(members, rnd);
				int alternatives = Math.min(between(2, profile.maxUnionAlternatives()), members.size());
				if (needsExtension) {
					// Leave at least one record outside every union, so that a forced newSubtype has a candidate.
					alternatives = Math.min(alternatives, members.size() - 1);
				}
				unions.put(prefix + "U" + index, new ArrayList<>(members.subList(0, alternatives)));
			}
			planInterfaces();
			// The snapshot is taken AFTER the interface plan, which may have grown a union's alternatives and gave
			// the common fields to their records: version one must already contain both.
			initialUnions.putAll(copies(unions));
		}

		/**
		 * Decides {@code interfacesData} together with the unions, because both demand things a later transformation
		 * cannot provide:
		 *
		 * <ul>
		 *   <li>{@code commonData}/{@code commonGetters} are abstract methods of the union interface, so every
		 *   alternative must carry those fields as real data — production's {@code ChatEntityId} declares
		 *   {@code tableId} and {@code SuperCollectionId}, {@code BasicGroupId}, {@code UserId} and
		 *   {@code DeprecatedId} all have it as a field. They are therefore declared in version one of every
		 *   member, where no transformation can miss them;</li>
		 *   <li>{@code extendInterfaces} makes the extended union's abstract methods (its common data and its
		 *   {@code getMetaId$<Union>()}) part of the extending union's contract too, so the extended union must list
		 *   EVERY alternative of the extending one — again exactly what the telegramdb corpus does with
		 *   {@code GroupId extends ChatEntityId}.</li>
		 * </ul>
		 *
		 * <p>{@code extendInterfaces} only ever points at the lower-numbered union, so the common-field requirement
		 * propagates along that chain. A {@code newSubtype} can join neither a common-field union (its fields must
		 * exist since version one) nor an extending one (the parent could only be extended by a second
		 * {@code newSubtype}), so with a forced {@code UNION_EXTENSIONS} the first union stays free of both.</p>
		 */
		private void planInterfaces() {
			if (unions.isEmpty() || !enabled(Feature.INTERFACES, profile.allowInterfaces(), 50)) {
				return;
			}
			List<String> unionNames = new ArrayList<>(unions.keySet());
			String reservedUnion = profile.forced(Feature.UNION_EXTENSIONS) ? unionNames.get(0) : null;
			var plans = new ArrayList<InterfaceDataConfiguration>();
			for (int index = 0; index < unionNames.size(); index++) {
				boolean reserved = unionNames.get(index).equals(reservedUnion);
				boolean extendsLower = index > 0 && !reserved && (profile.forced(Feature.INTERFACES) || chance(60));
				boolean common = !reserved && (profile.forced(Feature.INTERFACES) || chance(70));
				var configured = new InterfaceDataConfiguration();
				if (extendsLower) {
					configured.extendInterfaces.add(unionNames.get(index - 1));
				}
				if (common) {
					configured.commonData.put(COMMON_DATA_FIELD, "long");
					configured.commonGetters.put(COMMON_GETTER_FIELD, "boolean");
				}
				plans.add(configured);
			}
			// Grow the extended unions BEFORE any common field is handed out, so a record that only became an
			// alternative of the parent through this step still gets the parent's fields.
			for (int index = 0; index < unionNames.size(); index++) {
				for (String parent : plans.get(index).extendInterfaces) {
					absorbAlternatives(parent, unionNames.get(index));
				}
			}
			for (int index = 0; index < unionNames.size(); index++) {
				String union = unionNames.get(index);
				boolean common = !plans.get(index).commonData.isEmpty();
				boolean extendsLower = !plans.get(index).extendInterfaces.isEmpty();
				// Ascending order: the extended union (index - 1) was already classified here, because
				// extendInterfaces only ever points at the lower-numbered union.
				boolean inherited = extendsLower && index > 0 && commonFieldUnions.contains(unionNames.get(index - 1));
				if (common || inherited) {
					commonFieldUnions.add(union);
					addCommonFields(union);
				}
				if (extendsLower) {
					extendingUnions.add(union);
				}
				interfacePlan.put(union, plans.get(index));
			}
		}

		/**
		 * Makes {@code parent} an alternative-holder of every alternative of {@code child}, which is what an
		 * {@code extendInterfaces} relation demands; the union cap of 256 alternatives still wins.
		 */
		private void absorbAlternatives(String parent, String child) {
			List<String> parentAlternatives = unions.get(parent);
			for (String alternative : unions.get(child)) {
				if (!parentAlternatives.contains(alternative) && parentAlternatives.size() < 256) {
					parentAlternatives.add(alternative);
				}
			}
		}

		/**
		 * Gives every alternative of {@code union} the interface's common fields, in the version-one snapshot as
		 * well as in the live simulation, so they are present in every later shape without a transformation.
		 */
		private void addCommonFields(String union) {
			for (String member : unions.get(union)) {
				for (Map<String, String> shape : List.of(live.get(member), initial.get(member))) {
					shape.put(COMMON_DATA_FIELD, "long");
					shape.put(COMMON_GETTER_FIELD, "boolean");
				}
			}
		}

		private static LinkedHashMap<String, List<String>> copies(Map<String, List<String>> source) {
			var result = new LinkedHashMap<String, List<String>>();
			source.forEach((name, values) -> result.put(name, new ArrayList<>(values)));
			return result;
		}

		private LinkedHashMap<String, ClassConfiguration> baseTypes() {
			var result = new LinkedHashMap<String, ClassConfiguration>();
			initial.forEach((name, fields) -> {
				var type = new ClassConfiguration();
				type.data = new LinkedHashMap<>(fields);
				result.put(name, type);
			});
			applyStringRepresenters(result);
			return result;
		}

		private void applyStringRepresenters(LinkedHashMap<String, ClassConfiguration> baseTypes) {
			if (!enabled(Feature.STRING_REPRESENTERS, profile.allowStringRepresenters(), 40)) {
				return;
			}
			List<String> candidates = new ArrayList<>();
			baseTypes.forEach((name, type) -> {
				// Only untouched records: DataModel rejects removing or renaming the represented field, and the
				// field must exist from version one, where the representer is declared.
				if (touched.contains(name) || !representable(name)) {
					return;
				}
				candidates.add(name);
			});
			if (candidates.isEmpty()) {
				return;
			}
			// A forced representer may spread over up to maxStringRepresenters records (production schemas
			// carry several); unforced ones keep the historical one-or-two.
			int cap = profile.forced(Feature.STRING_REPRESENTERS)
					? profile.maxStringRepresenters()
					: Math.min(2, profile.maxStringRepresenters());
			int count = between(1, Math.min(cap, candidates.size()));
			for (int index = 0; index < count; index++) {
				String name = candidates.get(index % candidates.size());
				baseTypes.get(name).stringRepresenter = representableField(name);
			}
		}

		private boolean representable(String name) {
			return representableField(name) != null;
		}

		private String representableField(String name) {
			for (var entry : initial.get(name).entrySet()) {
				if ("String".equals(entry.getValue())) {
					return entry.getKey();
				}
			}
			return null;
		}

		// ---- later versions ------------------------------------------------------------------------

		private void createVersions() {
			versions.put("v1", rootVersion());
			for (int version = 2; version <= versionCount; version++) {
				versions.put("v" + version, laterVersion("v" + (version - 1)));
			}
		}

		private VersionConfiguration rootVersion() {
			var version = new VersionConfiguration();
			if (enabled(Feature.VERSION_DETAILS, true, 25)) {
				var details = new DetailsConfiguration();
				details.changelog = "initial shape of " + prefix;
				version.details = details;
				emitted.add(Feature.VERSION_DETAILS);
			}
			return version;
		}

		private VersionConfiguration laterVersion(String previousVersion) {
			var version = new VersionConfiguration();
			version.previousVersion = previousVersion;
			var transformations = new ArrayList<VersionTransformation>();
			int wanted = between(profile.minTransformationsPerVersion(), profile.maxTransformationsPerVersion());
			int rounds = Math.max(wanted, pendingOperations.size());
			// An operation can return null (no candidate record at this point); retry until the version really
			// carries `rounds` transformations, bounded so an exhausted shape still emits what it has.
			int attempts = 0;
			int maxAttempts = 4 * rounds + 4;
			while (transformations.size() < rounds && attempts++ < maxAttempts) {
				VersionTransformation transformation = !pendingOperations.isEmpty()
						? operation(pendingOperations.remove(0))
						: randomOperation();
				if (transformation != null) {
					transformations.add(transformation);
				}
			}
			if (!transformations.isEmpty()) {
				version.transformations = transformations;
			}
			if (enabled(Feature.VERSION_DETAILS, true, 20)) {
				var details = new DetailsConfiguration();
				details.changelog = "evolved " + previousVersion + " of " + prefix;
				version.details = details;
				emitted.add(Feature.VERSION_DETAILS);
			}
			return version;
		}

		private VersionTransformation randomOperation() {
			List<Feature> available = new ArrayList<>();
			available.add(Feature.DATA_ADDS);
			if (hasAnyField()) {
				available.add(Feature.DATA_MOVES);
				available.add(Feature.DATA_UPGRADES);
			}
			if (hasRemovableField()) {
				available.add(Feature.DATA_REMOVALS);
			}
			if (live.size() < maxTypes + 4) {
				available.add(Feature.NEW_TYPES);
			}
			if (hasUnionExtension()) {
				available.add(Feature.UNION_EXTENSIONS);
			}
			return operation(available.get(rnd.nextInt(available.size())));
		}

		private VersionTransformation operation(Feature feature) {
			var transformation = new VersionTransformation();
			switch (feature) {
				case DATA_ADDS -> transformation.newData = newData();
				case DATA_MOVES -> transformation.moveData = moveData();
				case DATA_REMOVALS -> transformation.removeData = removeData();
				case DATA_UPGRADES -> transformation.upgradeData = upgradeData();
				case NEW_TYPES -> transformation.newType = newType();
				case UNION_EXTENSIONS -> transformation.newSubtype = newSubtype();
				default -> {
					return null;
				}
			}
			if (transformation.newData == null && transformation.moveData == null && transformation.removeData == null
					&& transformation.upgradeData == null && transformation.newType == null
					&& transformation.newSubtype == null) {
				return null;
			}
			emitted.add(feature);
			return transformation;
		}

		// ---- operations ----------------------------------------------------------------------------

		private boolean hasAnyField() {
			return mutableTypes().stream().anyMatch(type -> !live.get(type).isEmpty());
		}

		private boolean hasRemovableField() {
			return mutableTypes().stream().anyMatch(type -> live.get(type).size() > 1);
		}

		private boolean hasUnionExtension() {
			return !extensibleUnions().isEmpty()
					&& live.keySet().stream().anyMatch(name -> !inAnyUnion(name));
		}

		/**
		 * Unions a {@code newSubtype} may join. Excluded are unions whose interface declares common data (those
		 * fields are version-one data of every alternative, and a type that only joins in a later version cannot
		 * have carried them then) and unions that extend another one (their alternatives must be alternatives of
		 * the extended union too, which a single {@code newSubtype} cannot express).
		 */
		private List<String> extensibleUnions() {
			return unions.entrySet().stream()
					.filter(entry -> entry.getValue().size() < 256)
					.map(Map.Entry::getKey)
					.filter(name -> !commonFieldUnions.contains(name) && !extendingUnions.contains(name))
					.toList();
		}

		private boolean inAnyUnion(String name) {
			return unions.values().stream().anyMatch(alternatives -> alternatives.contains(name));
		}

		private NewDataConfiguration newData() {
			String type = pick(mutableTypes());
			if (type == null) {
				return null;
			}
			var newData = new NewDataConfiguration();
			newData.transformClass = type;
			newData.type = "long";
			newData.to = nextField(type);
			var fields = live.get(type);
			boolean useContext = enabled(Feature.CONTEXT_PARAMETERS, profile.allowContextParameters(), 35)
					&& fields.containsKey(CONTEXT_FIELD);
			if (enabled(Feature.READ_TRANSFORMS, true, 30) && !useContext) {
				// A non-custom readTransform still needs the plain initializer location
				// (GenUpgraderBaseX resolves it before consulting the readTransform), and the transform has to
				// compute the value that initializer computes.
				newData.initializer = LONG_INITIALIZER;
				newData.readTransform = readTransform(newData.type, ReadPairedLocation.INITIALIZED_LONG);
				emitted.add(Feature.READ_TRANSFORMS);
			} else if (useContext) {
				newData.initializer = CONTEXT_LONG_INITIALIZER;
				newData.contextParameters = new ArrayList<>(List.of(CONTEXT_FIELD));
				emitted.add(Feature.CONTEXT_PARAMETERS);
			} else if (enabled(Feature.INSTANCE_FORM_UPGRADERS, profile.allowInstanceFormUpgraders(), 25)) {
				newData.initializerInstance = INSTANCE_INITIALIZER;
				emitted.add(Feature.INSTANCE_FORM_UPGRADERS);
			} else {
				newData.initializer = LONG_INITIALIZER;
			}
			if (enabled(Feature.INDEX_INSERTION, profile.allowIndexInsertion(), 40)) {
				newData.index = rnd.nextInt(fields.size() + 1);
				emitted.add(Feature.INDEX_INSERTION);
			}
			fields.put(newData.to, newData.type);
			touched.add(type);
			return newData;
		}

		private MoveDataConfiguration moveData() {
			String type = pick(mutableTypes().stream()
					.filter(candidate -> !movableFields(candidate).isEmpty())
					.toList());
			if (type == null) {
				return null;
			}
			var fields = live.get(type);
			var moveData = new MoveDataConfiguration();
			moveData.transformClass = type;
			moveData.from = pick(movableFields(type));
			// `to` is a name this record never used, so it can never collide with an existing field.
			moveData.to = nextField(type);
			// DataModel removes the field first and only then inserts, so the index bound is the size after the
			// removal; an index equal to the old size would make ParsedClass.tryInsertAtIndex compute a negative
			// initial capacity.
			String movedType = fields.remove(moveData.from);
			if (enabled(Feature.INDEX_INSERTION, profile.allowIndexInsertion(), 40)) {
				moveData.index = rnd.nextInt(fields.size() + 1);
				emitted.add(Feature.INDEX_INSERTION);
			}
			fields.put(moveData.to, movedType);
			touched.add(type);
			return moveData;
		}

		private RemoveDataConfiguration removeData() {
			List<String> candidates = mutableTypes().stream()
					.filter(type -> live.get(type).size() > 1 && !movableFields(type).isEmpty())
					.toList();
			String type = pick(candidates);
			if (type == null) {
				return null;
			}
			var removeData = new RemoveDataConfiguration();
			removeData.transformClass = type;
			removeData.from = pick(movableFields(type));
			live.get(type).remove(removeData.from);
			touched.add(type);
			return removeData;
		}

		/**
		 * The fields a {@code moveData} or {@code removeData} may touch. The interface common fields are excluded
		 * for the same reason the {@code stringRepresenter} field is: a union interface declares accessors for
		 * them, so every alternative must keep them in every version.
		 */
		private List<String> movableFields(String type) {
			return live.get(type).keySet().stream()
					.filter(field -> !isInterfaceBound(field))
					.toList();
		}

		private static boolean isInterfaceBound(String field) {
			return COMMON_DATA_FIELD.equals(field) || COMMON_GETTER_FIELD.equals(field);
		}

		private UpgradeDataConfiguration upgradeData() {
			// The shared test upgraders convert int -> long, so the field has to be declared int in the PREVIOUS
			// version's shape. While a version is built, `live` IS that shape, so the field must already be int
			// there: overwriting the live type (as an earlier revision of this method did) emitted an upgrade
			// whose real previous type was something else, e.g. String, and the generated upgrade then cast a
			// String to Number at runtime. That only surfaced with generateOldSerializers=true, because
			// otherwise the previous version has no serializer and the pair is a counted skip anyway.
			Map<String, List<String>> candidates = intFieldsByType();
			String type = pick(new ArrayList<>(candidates.keySet()));
			var upgradeData = new UpgradeDataConfiguration();
			if (type == null) {
				// Nothing is int yet anywhere: introduce the field the upgrader needs in the previous shape,
				// which means in the emitted version-one snapshot as well as in the live simulation.
				type = pick(versionOneTypes());
				if (type == null) {
					return null;
				}
				upgradeData.from = nextField(type);
				live.get(type).put(upgradeData.from, "int");
				initial.get(type).put(upgradeData.from, "int");
			} else {
				upgradeData.from = pick(candidates.get(type));
			}
			upgradeData.transformClass = type;
			var fields = live.get(type);
			boolean useContext = enabled(Feature.CONTEXT_PARAMETERS, profile.allowContextParameters(), 35)
					&& fields.containsKey(CONTEXT_FIELD);
			upgradeData.type = useContext && chance(50) ? "-long" : "long";
			if (useContext) {
				upgradeData.upgrader = "-long".equals(upgradeData.type)
						? INT_TO_NULLABLE_LONG_UPGRADER
						: CONTEXT_INT_TO_LONG_UPGRADER;
				upgradeData.contextParameters = new ArrayList<>(List.of(CONTEXT_FIELD));
				emitted.add(Feature.CONTEXT_PARAMETERS);
			} else if (enabled(Feature.INSTANCE_FORM_UPGRADERS, profile.allowInstanceFormUpgraders(), 20)) {
				upgradeData.upgraderInstance = INSTANCE_UPGRADER;
				emitted.add(Feature.INSTANCE_FORM_UPGRADERS);
			} else if (enabled(Feature.READ_TRANSFORMS, true, 25)) {
				// Same rule as newData: the plain upgrader location is resolved even for
				// non-custom readTransforms (GenUpgraderBaseX), so both must compute old + 1000.
				upgradeData.upgrader = INT_TO_LONG_UPGRADER;
				upgradeData.readTransform = readTransform(upgradeData.type, ReadPairedLocation.UPGRADED_FROM_INT);
				emitted.add(Feature.READ_TRANSFORMS);
			} else {
				upgradeData.upgrader = INT_TO_LONG_UPGRADER;
			}
			fields.put(upgradeData.from, upgradeData.type);
			touched.add(type);
			return upgradeData;
		}

		private List<String> liveTypesWithFields() {
			return mutableTypes().stream()
					.filter(type -> !live.get(type).isEmpty())
					.toList();
		}

		/**
		 * The records an {@code int -> long} upgrade can be applied to, mapped to the fields that are declared
		 * {@code int} in the previous version's shape (the live map). A field a previous upgrade already widened
		 * is {@code long} here and therefore not a candidate again.
		 */
		private Map<String, List<String>> intFieldsByType() {
			Map<String, List<String>> candidates = new LinkedHashMap<>();
			for (String type : mutableTypes()) {
				List<String> fields = live.get(type).entrySet().stream()
						.filter(entry -> "int".equals(entry.getValue()))
						.map(Map.Entry::getKey)
						.toList();
				if (!fields.isEmpty()) {
					candidates.put(type, new ArrayList<>(fields));
				}
			}
			return candidates;
		}

		/** Records that exist since version one, so a field added to them is present in every later shape too. */
		private List<String> versionOneTypes() {
			return mutableTypes().stream()
					.filter(initial::containsKey)
					.filter(type -> !live.get(type).isEmpty())
					.toList();
		}

		/**
		 * Records a transformation may modify. The reserved record is excluded so that a forced projection always
		 * has a source whose shape never changes; when nothing else exists it is still better to transform the
		 * reserved record than to emit no operation at all.
		 */
		private List<String> mutableTypes() {
			List<String> types = new ArrayList<>(live.keySet());
			types.remove(reserved);
			return types.isEmpty() ? new ArrayList<>(live.keySet()) : types;
		}

		private NewTypeConfiguration newType() {
			String name = prefix + "N" + newTypeCounter++;
			var newType = new NewTypeConfiguration();
			newType.name = name;
			var fields = new LinkedHashMap<String, String>();
			int fieldCount = between(1, Math.max(1, Math.min(3, maxFields)));
			for (int index = 0; index < fieldCount; index++) {
				fields.put("f" + index, fieldTypeOfExisting());
			}
			// Snapshot for the emitted config: later operations mutate the live map, and {@code newType.data}
			// must stay the shape the type is introduced with, or DataModel sees the same field twice.
			newType.data = new LinkedHashMap<>(fields);
			live.put(name, fields);
			nextFieldIndex.put(name, fieldCount);
			depth.put(name, 1);
			touched.add(name);
			return newType;
		}

		private String fieldTypeOfExisting() {
			int roll = rnd.nextInt(100);
			if (profile.allowNullable() && roll < 25) {
				return "-" + reference(null, true);
			}
			if (profile.allowArrays() && roll < 45) {
				return reference(null, true) + "[]";
			}
			return aNative();
		}

		private NewSubtypeConfiguration newSubtype() {
			String union = pick(extensibleUnions());
			if (union == null) {
				return null;
			}
			List<String> alternatives = unions.get(union);
			String subtype = pick(live.keySet().stream().filter(name -> !alternatives.contains(name)).toList());
			if (subtype == null) {
				return null;
			}
			var newSubtype = new NewSubtypeConfiguration();
			newSubtype.superType = union;
			newSubtype.subtype = subtype;
			alternatives.add(subtype);
			return newSubtype;
		}

		/**
		 * A {@code readTransform} that declares exactly one operation, as {@code ReadTransformConfiguration}
		 * demands, whose shape fits the declared result type ({@code GenReadPlan} requires a non-null
		 * expected-or-explicit type for {@code constant}/{@code invokeStatic}, so the node declares its
		 * {@code type} explicitly), and — decisively — whose VALUE equals the value of the plain location the
		 * same transformation declares, see {@link ReadPairedLocation}. The fused read compiles the declarative
		 * transform while the codec-read + {@code upgradeDataToLatestVersion} path calls the initializer/upgrader,
		 * so an arbitrary pairing makes the two read paths disagree.
		 *
		 * <p>Only the two {@link ReadPairedLocation} shapes are emitted, both non-nullable: {@code mapNullable}
		 * needs a nullable result type, and this generator never carries a {@code readTransform} on a nullable
		 * field (a {@code newData} is always {@code long}, and the nullable {@code upgradeData} variant always
		 * runs with context parameters, which has no plain location to agree with). A {@code mapNullable} over a
		 * {@code null} source would also yield a non-null value the initializer path never produces.</p>
		 */
		private ReadTransformConfiguration readTransform(String resultType, ReadPairedLocation location) {
			if (resultType != null && resultType.startsWith("-")) {
				throw new IllegalStateException("No value-conserving readTransform shape exists for a nullable "
						+ "field: a paired plain location must compute the same value, and the nullable variants "
						+ "of this generator carry context parameters instead. Got " + resultType);
			}
			var transform = new ReadTransformConfiguration();
			transform.type = resultType != null ? resultType : "long";
			// `construct` is deliberately not emitted: with a native `type:` the generator casts the
			// primitive TypeName to a ClassName and throws ClassCastException (GenReadPlan:1017) —
			// only class shapes are constructible. Reported as a generator ergonomics nit.
			switch (location) {
				// TestSimpleLongInitializer.initialize(...) == 123.
				case INITIALIZED_LONG -> {
					switch (rnd.nextInt(2)) {
						case 0 -> transform.constant = constant(123L);
						// TortureTransforms.widen(-877) == -877 + 1000 == 123.
						default -> transform.invokeStatic = invokeStatic(TORTURE_TRANSFORMS + ".widen",
								List.of(constantNode(-877, "int")));
					}
				}
				// TestSimpleIntToLongUpgrader.upgrade(old) == old + 1000.
				case UPGRADED_FROM_INT -> {
					switch (rnd.nextInt(2)) {
						// widen(value) == old + 1000; `value` is the old field, so the node needs no type.
						case 0 -> transform.invokeStatic = invokeStatic(TORTURE_TRANSFORMS + ".widen",
								List.of(identityNode("value")));
						// widenWithAnchor(old, -1000) == old - 1000 + 2000 == old + 1000.
						default -> transform.invokeStatic = invokeStatic(TORTURE_TRANSFORMS + ".widenWithAnchor",
								List.of(identityNode("value"), constantNode(-1000L, "long")));
					}
				}
			}
			return transform;
		}

		private static ReadTransformConfiguration.InvokeStatic invokeStatic(String method,
				List<ReadTransformConfiguration> arguments) {
			var invokeStatic = new ReadTransformConfiguration.InvokeStatic();
			invokeStatic.method = method;
			// Argument nodes are compiled with NO expected type, so a constant node must declare the target's
			// PARAMETER type explicitly: a `long` node feeding `widen(int)` makes javac reject the call.
			invokeStatic.arguments = new ArrayList<>(arguments);
			return invokeStatic;
		}

		private static ReadTransformConfiguration identityNode(String source) {
			var node = new ReadTransformConfiguration();
			node.identity = new ReadTransformConfiguration.Identity();
			node.identity.source = source;
			return node;
		}

		private static ReadTransformConfiguration.Constant constant(Object value) {
			var constant = new ReadTransformConfiguration.Constant();
			constant.value = value;
			return constant;
		}

		/**
		 * Argument nodes are compiled with NO expected type, so they must declare it explicitly, and the declared
		 * type has to be the target's PARAMETER type: a {@code long} node feeding {@code widen(int)} makes javac
		 * reject the call as a lossy conversion.
		 */
		private static ReadTransformConfiguration constantNode(Object value, String type) {
			var node = new ReadTransformConfiguration();
			node.type = type;
			node.constant = constant(value);
			return node;
		}

		/**
		 * Forced features that no operation happened to carry (because the shape did not allow it) get one last
		 * transformation in the newest version, so {@code forced} really means "present".
		 */
		private void repairForcedFeatures() {
			if (versionCount < 2) {
				return;
			}
			var version = versions.get("v" + versionCount);
			List<VersionTransformation> transformations = version.transformations == null
					? new ArrayList<>()
					: new ArrayList<>(version.transformations);
			var target = pick(liveTypesWithFields());
			if (target == null) {
				return;
			}
			var fields = live.get(target);
			boolean added = false;
			if (profile.forced(Feature.UNION_EXTENSIONS) && !emitted.contains(Feature.UNION_EXTENSIONS)) {
				var newSubtype = newSubtype();
				if (newSubtype != null) {
					var transformation = new VersionTransformation();
					transformation.newSubtype = newSubtype;
					transformations.add(transformation);
					emitted.add(Feature.UNION_EXTENSIONS);
					added = true;
				}
			}
			if (profile.forced(Feature.INDEX_INSERTION) && !emitted.contains(Feature.INDEX_INSERTION)) {
				var newData = plainNewData(target);
				// Appending at the end is still an indexed insertion, and it can never hit an occupied slot.
				newData.index = fields.size();
				added |= commit(transformations, newData);
			}
			if (profile.forced(Feature.READ_TRANSFORMS) && !emitted.contains(Feature.READ_TRANSFORMS)) {
				var newData = plainNewData(target);
				newData.readTransform = readTransform(newData.type, ReadPairedLocation.INITIALIZED_LONG);
				added |= commit(transformations, newData);
			}
			if (profile.forced(Feature.INSTANCE_FORM_UPGRADERS) && !emitted.contains(Feature.INSTANCE_FORM_UPGRADERS)) {
				var newData = plainNewData(target);
				newData.initializerInstance = INSTANCE_INITIALIZER;
				added |= commit(transformations, newData);
			}
			if (profile.forced(Feature.CONTEXT_PARAMETERS) && !emitted.contains(Feature.CONTEXT_PARAMETERS)
					&& !fields.isEmpty()) {
				var newData = plainNewData(target);
				newData.contextParameters = new ArrayList<>(List.of(fields.keySet().iterator().next()));
				added |= commit(transformations, newData);
			}
			if (added) {
				version.transformations = transformations;
			}
		}

		private NewDataConfiguration plainNewData(String type) {
			var newData = new NewDataConfiguration();
			newData.transformClass = type;
			newData.type = "long";
			newData.to = nextField(type);
			newData.initializer = LONG_INITIALIZER;
			return newData;
		}

		private boolean commit(List<VersionTransformation> transformations, NewDataConfiguration newData) {
			var transformation = new VersionTransformation();
			transformation.newData = newData;
			transformations.add(transformation);
			live.get(newData.transformClass).put(newData.to, newData.type);
			touched.add(newData.transformClass);
			if (newData.index != null) {
				emitted.add(Feature.INDEX_INSERTION);
			}
			if (newData.readTransform != null) {
				emitted.add(Feature.READ_TRANSFORMS);
			}
			if (newData.initializerInstance != null) {
				emitted.add(Feature.INSTANCE_FORM_UPGRADERS);
			}
			if (newData.contextParameters != null && !newData.contextParameters.isEmpty()) {
				emitted.add(Feature.CONTEXT_PARAMETERS);
			}
			return true;
		}

		// ---- current version only ------------------------------------------------------------------

		private Map<String, InterfaceDataConfiguration> interfaces() {
			return interfacePlan.isEmpty() ? null : new LinkedHashMap<>(interfacePlan);
		}

		private Map<String, ProjectionConfiguration> projections() {
			List<String> sources = stableProjectionSources();
			if (sources.isEmpty() || !enabled(Feature.PROJECTIONS, profile.allowProjections(), 60)) {
				return null;
			}
			var result = new LinkedHashMap<String, ProjectionConfiguration>();
			int count = between(1, Math.min(2, sources.size()));
			for (int index = 0; index < count; index++) {
				String sourceType = sources.get(index % sources.size());
				var fields = new ArrayList<>(initial.get(sourceType).keySet());
				var projection = new ProjectionConfiguration();
				projection.sourceType = sourceType;
				var selected = new LinkedHashMap<String, String>();
				int selectedCount = between(1, Math.min(4, fields.size()));
				// With projectionMiddleRanges the selection is a strict MIDDLE range of the source's fields
				// (production projections rarely start at the first field); otherwise it stays a prefix.
				int maxStart = fields.size() - selectedCount;
				int start = profile.projectionMiddleRanges() && maxStart > 1 ? 1 + between(0, maxStart - 1) : 0;
				for (int fieldIndex = 0; fieldIndex < selectedCount; fieldIndex++) {
					// Field names are valid Java identifiers; the path is a field that exists in every version.
					selected.put("p" + fieldIndex, fields.get(start + fieldIndex));
				}
				projection.fields = selected;
				if (!profile.forced(Feature.PROJECTIONS) && chance(50)) {
					projection.consumeToEnd = Boolean.FALSE;
				}
				result.put(prefix + "P" + projectionCounter++, projection);
			}
			return result.isEmpty() ? null : result;
		}

		/** Records that exist since version one and that no transformation ever modified. */
		private List<String> stableProjectionSources() {
			List<String> sources = new ArrayList<>();
			initial.forEach((name, fields) -> {
				if (!name.startsWith(prefix + "T") || touched.contains(name) || fields.isEmpty()) {
					return;
				}
				if (fields.values().stream().anyMatch(value -> !isStableFieldType(value))) {
					return;
				}
				sources.add(name);
			});
			return sources;
		}

		private boolean isStableFieldType(String rawType) {
			String plain = plainType(rawType);
			// A projection path only reads fields of its own source record, so the plain type of a field just has
				// to be a type this schema declares at all; whether it evolves is irrelevant to the path.
			return NATIVE_TYPES.contains(plain) || customs.containsKey(plain) || initial.containsKey(plain);
		}
	}
}
