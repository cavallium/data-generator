package it.cavallium.datagen.plugin.fuzz.support;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Knobs of {@link SchemaGen}: how big a schema may get and which language features it may use.
 *
 * <p>{@link #forTier(FuzzTiers.Tier)} maps a fuzz tier onto a profile; the tier ladder mirrors
 * {@code it.cavallium.datagen.fuzz.FuzzTiers.Tier}, which lives in {@code datagen}'s test sources and therefore
 * cannot be referenced from this module at compile time (see {@link #currentTier()} for the reflective bridge and
 * {@link #cases(String, int)} for the shared case-count knob).</p>
 *
 * @param minTypes           lowest number of record types that exist in version one
 * @param maxTypes           highest number of record types that exist in version one
 * @param minFields          lowest number of fields per record
 * @param maxFields          highest number of fields per record
 * @param minVersions        lowest length of the linear version chain
 * @param maxVersions        highest length of the linear version chain
 * @param maxUnionAlternatives upper bound for union alternatives; {@code < 2} disables unions entirely
 * @param maxDepth           maximum nesting depth of plain (non-nullable, non-array) record references
 * @param allowRecursion     whether a record may reference itself (always through a nullable or an array)
 * @param allowCustomTypes   whether {@code customTypesData} may be populated
 * @param allowProjections   whether {@code projectionsData} may be populated
 * @param allowInterfaces    whether {@code interfacesData} may be populated
 * @param allowStringRepresenters whether a record may declare {@code stringRepresenter}
 * @param allowInstanceFormUpgraders whether transformations may use {@code initializerInstance}/
 *                                   {@code upgraderInstance} instead of the class form
 * @param allowContextParameters   whether {@code newData}/{@code upgradeData} may declare
 *                                 {@code contextParameters}
 * @param allowIndexInsertion      whether {@code newData}/{@code moveData} may set {@code index}
 * @param allowNullable      whether {@code -Type} fields may be generated
 * @param allowArrays        whether {@code Type[]} fields may be generated
 * @param allowInt52         whether {@code Int52} may be used
 * @param allowBinaryString  advisory knob for the caller: value to pass to
 *                           {@code buildDataModel(boolean)}/{@code generateSources}; the YAML never carries it
 * @param forcedFeatures     features that MUST be present in the generated schema, whatever the tier says
 */
public record SchemaGenProfile(int minTypes,
		int maxTypes,
		int minFields,
		int maxFields,
		int minVersions,
		int maxVersions,
		int maxUnionAlternatives,
		int maxDepth,
		boolean allowRecursion,
		boolean allowCustomTypes,
		boolean allowProjections,
		boolean allowInterfaces,
		boolean allowStringRepresenters,
		boolean allowInstanceFormUpgraders,
		boolean allowContextParameters,
		boolean allowIndexInsertion,
		boolean allowNullable,
		boolean allowArrays,
		boolean allowInt52,
		boolean allowBinaryString,
		Set<Feature> forcedFeatures) {

	/**
	 * Every feature the synthesizer can emit. {@link SchemaGen.Coverage} detects them again by walking the produced
	 * configuration, so coverage reports what the schema actually contains rather than what the generator intended.
	 */
	public enum Feature {
		/** {@code superTypesData} declares at least one union. */
		UNIONS,
		/** A version grows an existing union through {@code newSubtype}. */
		UNION_EXTENSIONS,
		/** A record takes part in a reference cycle. */
		RECURSION,
		/** {@code customTypesData} declares at least one custom type. */
		CUSTOM_TYPES,
		/** {@code projectionsData} declares at least one projection. */
		PROJECTIONS,
		/** {@code interfacesData} declares at least one union interface. */
		INTERFACES,
		/** A record declares {@code stringRepresenter}. */
		STRING_REPRESENTERS,
		/** A transformation uses {@code initializerInstance} or {@code upgraderInstance}. */
		INSTANCE_FORM_UPGRADERS,
		/** A {@code newData}/{@code upgradeData} names {@code contextParameters}. */
		CONTEXT_PARAMETERS,
		/** A {@code newData}/{@code moveData} positions the field with {@code index}. */
		INDEX_INSERTION,
		/** A field of type {@code -<native>}. */
		NULLABLE_SCALAR,
		/** A field of type {@code -<schema type>}. */
		NULLABLE_VERSIONED,
		/** A field of type {@code <native>[]}. */
		ARRAY_SCALAR,
		/** A field of type {@code <schema type>[]}. */
		ARRAY_VERSIONED,
		/** A field whose plain type is {@code Int52}. */
		INT52,
		/** A version introduces a record through {@code newType}. */
		NEW_TYPES,
		/** A version adds a field through {@code newData}. */
		DATA_ADDS,
		/** A version renames a field through {@code moveData}. */
		DATA_MOVES,
		/** A version drops a field through {@code removeData}. */
		DATA_REMOVALS,
		/** A version rewrites a field through {@code upgradeData}. */
		DATA_UPGRADES,
		/** A transformation carries a {@code readTransform}. */
		READ_TRANSFORMS,
		/** More than one version is defined. */
		MULTI_VERSION,
		/** A version carries {@code details.changelog}. */
		VERSION_DETAILS
	}

	/** Mirror of {@code it.cavallium.datagen.fuzz.FuzzTiers.Tier} (that class is not on this module's classpath). */
	public enum Tier {
		QUICK,
		DEFAULT,
		DEEP,
		NIGHTLY;

		/**
		 * Exact copy of {@code FuzzTiers.cases(String, int)}'s tier arithmetic (FuzzTiers.java:149-159 plus its
		 * {@code multiplierFor} at FuzzTiers.java:189-196 and {@code atLeastOne} at FuzzTiers.java:230-232):
		 * the {@code double} multiplier (0.1 / 1 / 10 / 50) scales the base, a result at or above
		 * {@code Integer.MAX_VALUE} saturates instead of overflowing to a negative number, and the computed
		 * value rounds (so QUICK is {@code Math.round(base * 0.1)}, not a floor division) and floors at one.
		 */
		int scaled(int base) {
			double multiplier = switch (this) {
				case QUICK -> 0.1d;
				case DEFAULT -> 1d;
				case DEEP -> 10d;
				case NIGHTLY -> 50d;
			};
			double scaled = (double) base * multiplier;
			if (!(scaled < (double) Integer.MAX_VALUE)) {
				return Integer.MAX_VALUE;
			}
			return Math.max(1, (int) Math.round(scaled));
		}
	}

	public SchemaGenProfile {
		requireRange("types", minTypes, maxTypes, 1);
		requireRange("fields", minFields, maxFields, 1);
		requireRange("versions", minVersions, maxVersions, 1);
		if (maxUnionAlternatives < 0) {
			throw new IllegalArgumentException("maxUnionAlternatives must be >= 0: " + maxUnionAlternatives);
		}
		if (maxDepth < 1) {
			throw new IllegalArgumentException("maxDepth must be >= 1: " + maxDepth);
		}
		forcedFeatures = Set.copyOf(Objects.requireNonNull(forcedFeatures, "forcedFeatures"));
	}

	private static void requireRange(String name, int min, int max, int absoluteMin) {
		if (min < absoluteMin || max < min) {
			throw new IllegalArgumentException("Invalid " + name + " range [" + min + ", " + max + "]");
		}
	}

	/** The smallest schema the synthesizer can produce: one record, one field, one version, no optional features. */
	public static SchemaGenProfile minimal() {
		return new SchemaGenProfile(1, 1, 1, 1, 1, 1, 0, 1,
				false, false, false, false, false, false, false, false, false, false, false, false,
				EnumSet.noneOf(Feature.class));
	}

	/** Every feature on, wide and deep, but still inside the production limits (256 union alternatives). */
	public static SchemaGenProfile maximal() {
		return new SchemaGenProfile(6, 10, 4, 12, 4, 7, 12, 4,
				true, true, true, true, true, true, true, true, true, true, true, true,
				EnumSet.allOf(Feature.class));
	}

	/** Tier ladder: QUICK is a smoke schema, NIGHTLY is wide, deep and uses every feature. */
	public static SchemaGenProfile forTier(Tier tier) {
		Objects.requireNonNull(tier, "tier");
		return switch (tier) {
			case QUICK -> new SchemaGenProfile(1, 2, 1, 3, 1, 2, 3, 2,
					false, false, true, false, false, false, false, false, true, true, false, false,
					EnumSet.noneOf(Feature.class));
			case DEFAULT -> new SchemaGenProfile(2, 4, 2, 6, 1, 3, 4, 2,
					true, true, true, false, true, false, false, false, true, true, true, true,
					EnumSet.noneOf(Feature.class));
			case DEEP -> new SchemaGenProfile(2, 7, 2, 9, 1, 4, 6, 3,
					true, true, true, true, true, true, true, true, true, true, true, true,
					EnumSet.of(Feature.MULTI_VERSION));
			case NIGHTLY -> new SchemaGenProfile(3, 10, 3, 12, 2, 6, 10, 4,
					true, true, true, true, true, true, true, true, true, true, true, true,
					EnumSet.allOf(Feature.class));
		};
	}

	/** Copy with additional features that must appear in the generated schema. */
	public SchemaGenProfile withForced(Feature... features) {
		EnumSet<Feature> forced = EnumSet.noneOf(Feature.class);
		forced.addAll(forcedFeatures);
		for (Feature feature : features) {
			forced.add(Objects.requireNonNull(feature, "feature"));
		}
		// A union is the precondition of an interface or a union extension, so forcing those forces unions too.
		boolean needsUnions = forced.contains(Feature.UNIONS) || forced.contains(Feature.INTERFACES)
				|| forced.contains(Feature.UNION_EXTENSIONS);
		return new SchemaGenProfile(minTypes, maxTypes, minFields, maxFields, minVersions, maxVersions,
				Math.max(maxUnionAlternatives, needsUnions ? 3 : maxUnionAlternatives),
				Math.max(maxDepth, forced.contains(Feature.RECURSION) ? 2 : maxDepth),
				allowRecursion || forced.contains(Feature.RECURSION),
				allowCustomTypes || forced.contains(Feature.CUSTOM_TYPES),
				allowProjections || forced.contains(Feature.PROJECTIONS),
				allowInterfaces || forced.contains(Feature.INTERFACES),
				allowStringRepresenters || forced.contains(Feature.STRING_REPRESENTERS),
				allowInstanceFormUpgraders || forced.contains(Feature.INSTANCE_FORM_UPGRADERS),
				allowContextParameters || forced.contains(Feature.CONTEXT_PARAMETERS),
				allowIndexInsertion || forced.contains(Feature.INDEX_INSERTION),
				allowNullable || forced.contains(Feature.NULLABLE_SCALAR) || forced.contains(Feature.NULLABLE_VERSIONED),
				allowArrays || forced.contains(Feature.ARRAY_SCALAR) || forced.contains(Feature.ARRAY_VERSIONED),
				allowInt52 || forced.contains(Feature.INT52),
				allowBinaryString,
				forced);
	}

	public boolean forced(Feature feature) {
		return forcedFeatures.contains(feature);
	}

	/** Reflective {@code FuzzTiers.tier()}, with its documented fallback when that class is absent. */
	public static Tier currentTier() {
		try {
			Class<?> tiers = Class.forName("it.cavallium.datagen.fuzz.FuzzTiers");
			Object tier = tiers.getMethod("tier").invoke(null);
			return Tier.valueOf(((Enum<?>) tier).name());
		} catch (ReflectiveOperationException absent) {
			// Not on the classpath: apply FuzzTiers' documented rule.
		}
		String raw = property("datagen.fuzz.tier");
		if (raw == null) {
			return property("datagen.fuzz.seed") == null ? Tier.DEFAULT : Tier.DEEP;
		}
		for (Tier candidate : Tier.values()) {
			if (candidate.name().equalsIgnoreCase(raw)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException(
				"Invalid datagen.fuzz.tier='" + raw + "': expected quick|default|deep|nightly");
	}

	/** @return the trimmed property value, or {@code null} when unset or blank (FuzzTiers.java:221-228) */
	private static String property(String key) {
		String raw = System.getProperty(key);
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/**
	 * @return the literal {@code -Ddatagen.fuzz.cases} value, {@code null} when unset or blank, and never
	 * floored — a literal {@code 0} or negative value is honored as written (FuzzTiers.java:207-218)
	 */
	private static Integer casesOverride() {
		String raw = property("datagen.fuzz.cases");
		if (raw == null) {
			return null;
		}
		try {
			return Integer.valueOf(raw);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid datagen.fuzz.cases='" + raw + "': expected an int", e);
		}
	}

	/** Reflective {@code FuzzTiers.cases(id, base)}: {@code -Ddatagen.fuzz.cases} override, else tier-scaled. */
	public static int cases(String id, int base) {
		Integer bridged = invokeCases(id, base);
		if (bridged != null) {
			return bridged;
		}
		Integer override = casesOverride();
		if (override != null) {
			return override;
		}
		return currentTier().scaled(base);
	}

	/**
	 * Reflective {@code FuzzTiers.cases(id, quick, base, deep, nightly)}: lets a test keep the DEFAULT count it
	 * needs while bounding the deeper tiers, which would otherwise multiply by 10 or 50.
	 */
	public static int cases(String id, int quick, int base, int deep, int nightly) {
		Integer bridged = invokeCases(id, quick, base, deep, nightly);
		if (bridged != null) {
			return bridged;
		}
		Integer override = casesOverride();
		if (override != null) {
			return override;
		}
		return switch (currentTier()) {
			case QUICK -> Math.max(1, quick);
			case DEFAULT -> Math.max(1, base);
			case DEEP -> Math.max(1, deep);
			case NIGHTLY -> Math.max(1, nightly);
		};
	}

	/** {@code null} when {@code FuzzTiers} is not on the classpath; its answer otherwise. */
	private static Integer invokeCases(String id, int... arguments) {
		Class<?> tiers;
		try {
			tiers = Class.forName("it.cavallium.datagen.fuzz.FuzzTiers");
		} catch (ClassNotFoundException absent) {
			return null;
		}
		var boxed = new Object[arguments.length + 1];
		boxed[0] = id;
		for (int index = 0; index < arguments.length; index++) {
			boxed[index + 1] = arguments[index];
		}
		var parameterTypes = new Class<?>[arguments.length + 1];
		parameterTypes[0] = String.class;
		for (int index = 0; index < arguments.length; index++) {
			parameterTypes[index + 1] = int.class;
		}
		try {
			return (int) tiers.getMethod("cases", parameterTypes).invoke(null, boxed);
		} catch (java.lang.reflect.InvocationTargetException failure) {
			Throwable cause = failure.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("Unable to read the case count for " + id, cause);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("Unable to read the case count for " + id, failure);
		}
	}
}
