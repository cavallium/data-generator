package it.cavallium.datagen.plugin.fuzz.support;

import it.cavallium.datagen.plugin.ClassConfiguration;
import it.cavallium.datagen.plugin.CustomTypesConfiguration;
import it.cavallium.datagen.plugin.MoveDataConfiguration;
import it.cavallium.datagen.plugin.NewDataConfiguration;
import it.cavallium.datagen.plugin.NewTypeConfiguration;
import it.cavallium.datagen.plugin.ParsedClass;
import it.cavallium.datagen.plugin.ProjectionConfiguration;
import it.cavallium.datagen.plugin.ReadTransformConfiguration;
import it.cavallium.datagen.plugin.RemoveDataConfiguration;
import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;
import it.cavallium.datagen.plugin.VersionConfiguration;
import it.cavallium.datagen.plugin.VersionTransformation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Turns a valid {@link SchemaGen} schema into an invalid one: a single, well-understood rule violation per case,
 * with the exception {@code DataModel} (or the projection generator) really throws.
 *
 * <p>Every entry of {@link Mutation} was produced and run against the real implementation; the expected class and
 * message fragment are the ones observed, not documentation guesses. All mutations except the two projection ones
 * fail inside {@code buildDataModel(false)}; the projection ones fail while generating sources, because
 * {@code DataModel} deliberately does not resolve projection paths (see {@link Mutation#stage()}).</p>
 */
public final class InvalidSchemaGen {

	private InvalidSchemaGen() {
	}

	/** Where the violation is detected. */
	public enum Stage {
		/** {@code SourcesGeneratorConfiguration.buildDataModel(boolean)} throws. */
		MODEL,
		/** The model builds; generating sources from it throws (projection paths are not a model concern). */
		GENERATION
	}

	/** An invalid schema plus the failure it must produce. */
	public record InvalidCase(SourcesGeneratorConfiguration config,
			Class<? extends RuntimeException> expectedFailure,
			String expectedMessageFragment,
			String mutationId) {

		public InvalidCase {
			Objects.requireNonNull(config, "config");
			Objects.requireNonNull(expectedFailure, "expectedFailure");
			Objects.requireNonNull(expectedMessageFragment, "expectedMessageFragment");
			Objects.requireNonNull(mutationId, "mutationId");
		}
	}

	/**
	 * The mutation catalogue. {@code fragment} is empty only where the production exception carries no message
	 * ({@link ParsedClass.NoContextParameterException}), which the test asserts explicitly.
	 */
	public enum Mutation {

		UNKNOWN_TYPE_REFERENCE("unknown-type-reference", IllegalArgumentException.class,
				"refers to type ", Stage.MODEL),
		DUPLICATE_TYPE_NAME("duplicate-type-name", IllegalArgumentException.class,
				"has been defined more than once", Stage.MODEL),
		NON_LINEAR_VERSION_CHAIN("non-linear-version-chain", IllegalArgumentException.class,
				"Multiple versions depend on version ", Stage.MODEL),
		UNION_TOO_WIDE("union-too-wide", IllegalArgumentException.class,
				"alternatives; the unsigned-byte discriminator supports at most 256", Stage.MODEL),
		NULLABLE_ARRAY("nullable-array", UnsupportedOperationException.class,
				"Arrays cannot be null", Stage.MODEL),
		RESERVED_NAME_ITEMS_SIZE("reserved-name-items-size", IllegalArgumentException.class,
				"Generated public method collision", Stage.MODEL),
		RESERVED_NAME_HAS("reserved-name-has", IllegalArgumentException.class,
				"Generated public method collision", Stage.MODEL),
		PROJECTION_UNKNOWN_SEGMENT("projection-unknown-segment", IllegalArgumentException.class,
				"has no field ", Stage.GENERATION),
		PROJECTION_NON_IDENTIFIER("projection-non-identifier", IllegalArgumentException.class,
				"is not a valid Java identifier: ", Stage.GENERATION),
		CONSTRUCT_BOTH_TYPE_AND_CLASS_NAME("construct-both-type-and-class", IllegalArgumentException.class,
				".construct must not declare both schema type and className", Stage.MODEL),
		MAP_NULLABLE_MISSING_SOURCE("map-nullable-missing-source", IllegalArgumentException.class,
				"mapNullable.source is required", Stage.MODEL),
		STRING_REPRESENTER_REMOVED("string-representer-removed", IllegalArgumentException.class,
				"removes the stringRepresenter field ", Stage.MODEL),
		CONTEXT_PARAMETER_MISSING("context-parameters-missing-field", ParsedClass.NoContextParameterException.class,
				"", Stage.MODEL),
		UNKNOWN_TRANSFORM_CLASS("unknown-transform-class", IllegalArgumentException.class,
				"refers to an unknown type: ", Stage.MODEL),
		UNKNOWN_FROM_FIELD("unknown-from-field", IllegalArgumentException.class,
				"tries to remove the nonexistent field ", Stage.MODEL),
		NEW_DATA_OVERWRITES("new-data-overwrites-existing-field", IllegalArgumentException.class,
				"tries to overwrite the existing field ", Stage.MODEL),
		EMPTY_UNION("empty-union", IllegalArgumentException.class,
				"must define at least one alternative", Stage.MODEL),
		DUPLICATE_UNION_ALTERNATIVE("duplicate-union-alternative", IllegalArgumentException.class,
				"contains duplicate alternative ", Stage.MODEL),
		BLANK_TYPE_NAME("blank-type-name", IllegalArgumentException.class,
				"must define a non-blank name", Stage.MODEL),
		MOVE_TO_OCCUPIED("move-data-to-occupied-target", IllegalArgumentException.class,
				"tries to overwrite the existing field ", Stage.MODEL);

		private final String id;
		private final Class<? extends RuntimeException> expectedFailure;
		private final String expectedMessageFragment;
		private final Stage stage;

		Mutation(String id, Class<? extends RuntimeException> expectedFailure, String expectedMessageFragment,
				Stage stage) {
			this.id = id;
			this.expectedFailure = expectedFailure;
			this.expectedMessageFragment = expectedMessageFragment;
			this.stage = stage;
		}

		public String id() {
			return id;
		}

		public Class<? extends RuntimeException> expectedFailure() {
			return expectedFailure;
		}

		public String expectedMessageFragment() {
			return expectedMessageFragment;
		}

		public Stage stage() {
			return stage;
		}

		public static List<String> ids() {
			return java.util.Arrays.stream(values()).map(Mutation::id).toList();
		}
	}

	/** Applies a random mutation to a fresh valid schema; never {@code null}. */
	public static InvalidCase generate(Random rnd) {
		for (int attempt = 0; attempt < 32; attempt++) {
			Mutation mutation = Mutation.values()[rnd.nextInt(Mutation.values().length)];
			InvalidCase invalidCase = generate(rnd, mutation);
			if (invalidCase != null) {
				return invalidCase;
			}
		}
		throw new IllegalStateException("Unable to build an invalid case after 32 attempts");
	}

	/**
	 * Applies {@code mutation} to a freshly generated valid schema, or returns {@code null} when the base schema
	 * cannot host it (the caller then retries with a new seed).
	 */
	public static InvalidCase generate(Random rnd, Mutation mutation) {
		Objects.requireNonNull(rnd, "rnd");
		Objects.requireNonNull(mutation, "mutation");
		var config = SchemaGen.generate(rnd, baseProfile(mutation));
		var suffix = "X" + Integer.toUnsignedString(rnd.nextInt(), 36);
		if (apply(mutation, config, suffix)) {
			return new InvalidCase(config, mutation.expectedFailure, mutation.expectedMessageFragment, mutation.id());
		}
		return null;
	}

	/**
	 * A profile whose schemas carry the structures the mutation needs, so almost every attempt is applicable.
	 * The projection mutations stay small on purpose: their failure comes from generating sources.
	 */
	private static SchemaGenProfile baseProfile(Mutation mutation) {
		var profile = SchemaGenProfile.forTier(SchemaGenProfile.Tier.DEEP);
		return switch (mutation) {
			case PROJECTION_UNKNOWN_SEGMENT, PROJECTION_NON_IDENTIFIER -> SchemaGenProfile.minimal()
					.withForced(SchemaGenProfile.Feature.PROJECTIONS);
			case NON_LINEAR_VERSION_CHAIN -> profile.withForced(SchemaGenProfile.Feature.MULTI_VERSION);
			case STRING_REPRESENTER_REMOVED -> profile.withForced(SchemaGenProfile.Feature.STRING_REPRESENTERS,
					SchemaGenProfile.Feature.MULTI_VERSION);
			case UNION_TOO_WIDE -> SchemaGenProfile.minimal();
			default -> profile;
		};
	}

	// ------------------------------------------------------------------------------------------------
	// mutations
	// ------------------------------------------------------------------------------------------------

	private static boolean apply(Mutation mutation, SourcesGeneratorConfiguration config, String suffix) {
		return switch (mutation) {
			case UNKNOWN_TYPE_REFERENCE -> unknownTypeReference(config, suffix);
			case DUPLICATE_TYPE_NAME -> duplicateTypeName(config);
			case NON_LINEAR_VERSION_CHAIN -> nonLinearVersionChain(config);
			case UNION_TOO_WIDE -> unionTooWide(config, suffix);
			case NULLABLE_ARRAY -> nullableArray(config);
			case RESERVED_NAME_ITEMS_SIZE -> reservedNamePair(config, suffix, "items", "long[]", "itemsSize", "int");
			case RESERVED_NAME_HAS -> reservedNamePair(config, suffix, "flag", "-boolean", "hasFlag", "boolean");
			case PROJECTION_UNKNOWN_SEGMENT -> projectionPath(config, "missing" + suffix + ".deeper");
			case PROJECTION_NON_IDENTIFIER -> projectionPath(config, "1bad");
			case CONSTRUCT_BOTH_TYPE_AND_CLASS_NAME -> readTransformViolation(config, suffix, node -> {
				var construct = new ReadTransformConfiguration.Construct();
				construct.type = "long";
				construct.className = "java.util.Optional";
				node.construct = construct;
			});
			case MAP_NULLABLE_MISSING_SOURCE -> readTransformViolation(config, suffix, node -> {
				var mapNullable = new ReadTransformConfiguration.MapNullable();
				var transform = new ReadTransformConfiguration();
				var constant = new ReadTransformConfiguration.Constant();
				constant.value = 1L;
				transform.constant = constant;
				mapNullable.transform = transform;
				node.mapNullable = mapNullable;
			});
			case STRING_REPRESENTER_REMOVED -> stringRepresenterRemoved(config);
			case CONTEXT_PARAMETER_MISSING -> contextParameterMissing(config, suffix);
			case UNKNOWN_TRANSFORM_CLASS -> unknownTransformClass(config, suffix);
			case UNKNOWN_FROM_FIELD -> unknownFromField(config, suffix);
			case NEW_DATA_OVERWRITES -> newDataOverwrites(config);
			case EMPTY_UNION -> emptyUnion(config, suffix);
			case DUPLICATE_UNION_ALTERNATIVE -> duplicateUnionAlternative(config, suffix);
			case BLANK_TYPE_NAME -> blankTypeName(config);
			case MOVE_TO_OCCUPIED -> moveToOccupied(config);
		};
	}

	private static boolean unknownTypeReference(SourcesGeneratorConfiguration config, String suffix) {
		var type = firstTypeWithField(config);
		if (type == null) {
			return false;
		}
		type.getValue().data.put("fMissing", "Missing" + suffix);
		return true;
	}

	private static boolean duplicateTypeName(SourcesGeneratorConfiguration config) {
		String name = config.baseTypesData == null || config.baseTypesData.isEmpty()
				? null
				: config.baseTypesData.keySet().iterator().next();
		if (name == null) {
			return false;
		}
		var custom = new CustomTypesConfiguration();
		custom.setJavaClass(SchemaGen.CUSTOM_JAVA_CLASS);
		custom.codec = SchemaGen.CUSTOM_CODEC;
		custom.fixedSize = Integer.BYTES;
		config.customTypesData = config.customTypesData == null
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(config.customTypesData);
		config.customTypesData.put(name, custom);
		return true;
	}

	private static boolean nonLinearVersionChain(SourcesGeneratorConfiguration config) {
		if (config.versions == null || config.versions.size() < 3) {
			return false;
		}
		var names = new ArrayList<>(config.versions.keySet());
		var sibling = new VersionConfiguration();
		// A second child of the root version: the chain is no longer linear.
		sibling.previousVersion = names.get(0);
		config.versions.put(names.get(1) + "Sibling", sibling);
		return true;
	}

	private static boolean unionTooWide(SourcesGeneratorConfiguration config, String suffix) {
		var alternatives = new ArrayList<String>();
		for (int index = 0; index < 257; index++) {
			String name = "W" + suffix + index;
			config.baseTypesData.put(name, record("f0", "long"));
			alternatives.add(name);
		}
		config.superTypesData = config.superTypesData == null
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(config.superTypesData);
		config.superTypesData.put("W" + suffix + "Union", alternatives);
		return true;
	}

	private static boolean nullableArray(SourcesGeneratorConfiguration config) {
		var type = firstTypeWithField(config);
		if (type == null) {
			return false;
		}
		type.getValue().data.put("fArray", "-int[]");
		return true;
	}

	private static boolean reservedNamePair(SourcesGeneratorConfiguration config, String suffix,
			String first, String firstType, String second, String secondType) {
		config.baseTypesData.put("R" + suffix, record(first, firstType, second, secondType));
		return true;
	}

	private static boolean projectionPath(SourcesGeneratorConfiguration config, String path) {
		if (config.baseTypesData == null || config.baseTypesData.isEmpty()) {
			return false;
		}
		var projection = new ProjectionConfiguration();
		projection.sourceType = config.baseTypesData.keySet().iterator().next();
		var fields = new LinkedHashMap<String, String>();
		fields.put("p0", path);
		projection.fields = fields;
		config.projectionsData = config.projectionsData == null
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(config.projectionsData);
		config.projectionsData.put("Bad" + Math.abs(path.hashCode()), projection);
		return true;
	}

	private interface ReadTransformFill {
		void fill(ReadTransformConfiguration node);
	}

	private static boolean readTransformViolation(SourcesGeneratorConfiguration config, String suffix,
			ReadTransformFill fill) {
		String type = firstTypeName(config);
		if (type == null) {
			return false;
		}
		var node = new ReadTransformConfiguration();
		fill.fill(node);
		var newData = new NewDataConfiguration();
		newData.transformClass = type;
		newData.to = "bad" + suffix;
		newData.type = "long";
		newData.readTransform = node;
		addTransformation(config, transformation -> transformation.newData = newData);
		return true;
	}

	private static boolean stringRepresenterRemoved(SourcesGeneratorConfiguration config) {
		Map.Entry<String, ClassConfiguration> candidate = null;
		for (var entry : config.baseTypesData.entrySet()) {
			if (entry.getValue().stringRepresenter != null && !entry.getValue().stringRepresenter.isBlank()) {
				candidate = entry;
				break;
			}
		}
		if (candidate == null) {
			return false;
		}
		var removeData = new RemoveDataConfiguration();
		removeData.transformClass = candidate.getKey();
		removeData.from = candidate.getValue().stringRepresenter;
		addTransformation(config, transformation -> transformation.removeData = removeData);
		return true;
	}

	private static boolean contextParameterMissing(SourcesGeneratorConfiguration config, String suffix) {
		String type = firstTypeName(config);
		if (type == null) {
			return false;
		}
		var newData = new NewDataConfiguration();
		newData.transformClass = type;
		newData.to = "ctx" + suffix;
		newData.type = "long";
		newData.initializer = SchemaGen.LONG_INITIALIZER;
		newData.contextParameters = new ArrayList<>(List.of("nope" + suffix));
		addTransformation(config, transformation -> transformation.newData = newData);
		return true;
	}

	private static boolean unknownTransformClass(SourcesGeneratorConfiguration config, String suffix) {
		var newData = new NewDataConfiguration();
		newData.transformClass = "NoSuchType" + suffix;
		newData.to = "f0";
		newData.type = "long";
		newData.initializer = SchemaGen.LONG_INITIALIZER;
		addTransformation(config, transformation -> transformation.newData = newData);
		return true;
	}

	private static boolean unknownFromField(SourcesGeneratorConfiguration config, String suffix) {
		String type = firstTypeName(config);
		if (type == null) {
			return false;
		}
		var removeData = new RemoveDataConfiguration();
		removeData.transformClass = type;
		removeData.from = "nope" + suffix;
		addTransformation(config, transformation -> transformation.removeData = removeData);
		return true;
	}

	private static boolean newDataOverwrites(SourcesGeneratorConfiguration config) {
		var shape = shapeBeforeMutationVersion(config);
		var type = firstEntryWith(shape, 1);
		if (type == null) {
			return false;
		}
		var newData = new NewDataConfiguration();
		newData.transformClass = type.getKey();
		newData.to = type.getValue().get(0);
		newData.type = "long";
		newData.initializer = SchemaGen.LONG_INITIALIZER;
		addTransformation(config, transformation -> transformation.newData = newData);
		return true;
	}

	private static boolean emptyUnion(SourcesGeneratorConfiguration config, String suffix) {
		config.superTypesData = config.superTypesData == null
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(config.superTypesData);
		config.superTypesData.put("E" + suffix, new ArrayList<>());
		return true;
	}

	private static boolean duplicateUnionAlternative(SourcesGeneratorConfiguration config, String suffix) {
		String name = firstTypeName(config);
		if (name == null) {
			return false;
		}
		config.baseTypesData.put("V" + suffix, record("f0", "long"));
		config.superTypesData = config.superTypesData == null
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(config.superTypesData);
		config.superTypesData.put("D" + suffix, new ArrayList<>(List.of(name, name)));
		return true;
	}

	private static boolean blankTypeName(SourcesGeneratorConfiguration config) {
		var newType = new NewTypeConfiguration();
		newType.name = " ";
		newType.data = record("f0", "long").data;
		addTransformation(config, transformation -> transformation.newType = newType);
		return true;
	}

	private static boolean moveToOccupied(SourcesGeneratorConfiguration config) {
		var shape = shapeBeforeMutationVersion(config);
		var type = firstEntryWith(shape, 2);
		if (type == null) {
			return false;
		}
		var names = type.getValue();
		var moveData = new MoveDataConfiguration();
		moveData.transformClass = type.getKey();
		// Positions matter: ParsedClass.tryInsertAtIndex only sees a clash when the occupied name sits in the part
		// of the record before the insertion point, so the target has to be a field earlier than the source.
		moveData.from = names.get(1);
		moveData.to = names.get(0);
		addTransformation(config, transformation -> transformation.moveData = moveData);
		return true;
	}

	private static Map<String, List<String>> shapeBeforeMutationVersion(SourcesGeneratorConfiguration config) {
		String target = mutationVersionName(config);
		var shape = new LinkedHashMap<String, List<String>>();
		if (config.baseTypesData != null) {
			config.baseTypesData.forEach((name, type) -> {
				if (type != null && type.data != null) {
					shape.put(name, new ArrayList<>(type.data.keySet()));
				}
			});
		}
		if (config.versions != null) {
			for (var entry : config.versions.entrySet()) {
				var version = entry.getValue();
				// The mutation goes first in its own version, so only the earlier versions have happened yet.
				if (version == null || entry.getKey().equals(target) || version.transformations == null) {
					continue;
				}
				for (var transformation : version.transformations) {
					applyToShape(shape, transformation);
				}
			}
		}
		return shape;
	}

	private static void applyToShape(Map<String, List<String>> shape, VersionTransformation transformation) {
		if (transformation == null) {
			return;
		}
		if (transformation.newType != null) {
			var fields = new ArrayList<String>();
			if (transformation.newType.data != null) {
				fields.addAll(transformation.newType.data.keySet());
			}
			shape.put(transformation.newType.name, fields);
			return;
		}
		var fields = transformation.removeData != null
				? shape.get(transformation.removeData.transformClass)
				: transformation.moveData != null
						? shape.get(transformation.moveData.transformClass)
						: transformation.newData != null ? shape.get(transformation.newData.transformClass) : null;
		if (fields == null) {
			return;
		}
		if (transformation.removeData != null) {
			fields.remove(transformation.removeData.from);
		} else if (transformation.moveData != null) {
			fields.remove(transformation.moveData.from);
			if (!fields.contains(transformation.moveData.to)) {
				fields.add(transformation.moveData.to);
			}
		} else if (!fields.contains(transformation.newData.to)) {
			fields.add(transformation.newData.to);
		}
	}

	/** The version {@link #addTransformation} appends; it is not in the map yet, so every transformation counts. */
	private static String mutationVersionName(SourcesGeneratorConfiguration config) {
		return "v" + (config.versions.size() + 1);
	}

	private static Map.Entry<String, List<String>> firstEntryWith(Map<String, List<String>> shape, int minFields) {
		for (var entry : shape.entrySet()) {
			if (entry.getValue().size() >= minFields) {
				return entry;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------------
	// shared helpers
	// ------------------------------------------------------------------------------------------------

	/**
	 * Appends a version that holds the mutation as its <em>only</em> transformation. A sibling transformation of the
	 * same version could otherwise rename or remove the field this one points at, which would change the reason the
	 * schema fails; a root version may not carry transformations at all, so a new version is needed anyway.
	 */
	private static void addTransformation(SourcesGeneratorConfiguration config,
			java.util.function.Consumer<VersionTransformation> fill) {
		String name = "v" + (config.versions.size() + 1);
		var version = new VersionConfiguration();
		version.previousVersion = config.currentVersion;
		var transformation = new VersionTransformation();
		fill.accept(transformation);
		version.transformations = new ArrayList<>(List.of(transformation));
		config.versions.put(name, version);
		config.currentVersion = name;
	}

	private static Map.Entry<String, ClassConfiguration> firstTypeWithField(SourcesGeneratorConfiguration config) {
		if (config.baseTypesData == null) {
			return null;
		}
		return config.baseTypesData.entrySet().stream()
				.filter(entry -> entry.getValue() != null && entry.getValue().data != null
						&& !entry.getValue().data.isEmpty())
				.findFirst()
				.orElse(null);
	}

	private static String firstTypeName(SourcesGeneratorConfiguration config) {
		var entry = firstTypeWithField(config);
		return entry == null ? null : entry.getKey();
	}

	private static ClassConfiguration record(String... fieldNameAndTypePairs) {
		var data = new LinkedHashMap<String, String>();
		for (int index = 0; index + 1 < fieldNameAndTypePairs.length; index += 2) {
			data.put(fieldNameAndTypePairs[index], fieldNameAndTypePairs[index + 1]);
		}
		var type = new ClassConfiguration();
		type.data = data;
		return type;
	}
}
