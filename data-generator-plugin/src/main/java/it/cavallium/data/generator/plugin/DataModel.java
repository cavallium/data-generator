package it.cavallium.data.generator.plugin;

import static java.util.Objects.requireNonNull;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

public class DataModel {

	private static final List<String> NATIVE_TYPES = List.of("String",
			"boolean",
			"short",
			"char",
			"int",
			"long",
			"float",
			"double",
			"byte",
			"Int52"
	);

	private final int currentVersion;
	private final Int2ObjectMap<Map<String, ParsedClass>> classConfig;
	private final int hash;
	private final Map<String, ParsedInterface> interfacesData;
	private final Int2ObjectMap<ComputedVersion> versions;
	private final Map<String, Set<String>> superTypes;
	private final Map<String, CustomTypesConfiguration> customTypes;

	public DataModel(int hash,
			String currentVersionKey,
			Map<String, InterfaceDataConfiguration> interfacesData,
			Map<String, ClassConfiguration> baseTypesData,
			Map<String, Set<String>> superTypesData,
			Map<String, CustomTypesConfiguration> customTypesData,
			Map<String, VersionConfiguration> rawVersions) {

		this.hash = hash;

		if (rawVersions.isEmpty()) {
			throw new IllegalArgumentException("No defined versions");
		}

		if (!rawVersions.containsKey(currentVersionKey)) {
			throw new IllegalArgumentException("Current version " + currentVersionKey + " is not defined in versions!");
		}

		// Check if there are multiple root versions
		String rootVersion = rawVersions.entrySet().stream()
				.filter(e -> e.getValue().previousVersion == null)
				.map(Entry::getKey)
				.collect(toSingleton(DataModel::throwMultiRootVersions));

		// Check if multiple versions depend on the same version
		rawVersions.entrySet().stream()
				.filter(v -> v.getValue().previousVersion != null)
				.collect(Collectors.groupingBy(v -> v.getValue().previousVersion))
				.entrySet()
				.stream()
				.filter(x -> x.getValue().size() > 1)
				.forEach(x -> {
					throw new IllegalArgumentException("Multiple versions depend on version " + x.getKey() + ": " + x.getValue()
							.stream().map(Entry::getKey).collect(Collectors.joining(", ")));
				});

		// Create the next versions map
		Map<String, String> nextVersionMap = rawVersions.keySet().stream()
				.map(version -> Map.entry(version, rawVersions
						.entrySet()
						.stream()
						.filter(x -> Objects.equals(version, x.getValue().previousVersion))
						.map(Entry::getKey)
						.collect(toOptional(nextVersions -> throwMultiNextVersions(version, nextVersions))))
				)
				.filter(x -> x.getValue().isPresent())
				.map(x -> Map.entry(x.getKey(), x.getValue().get()))
				.collect(Collectors.toMap(Entry::getKey, Entry::getValue));

		// Build versions sequence
		List<String> rawVersionsSequence = new ArrayList<>();
		int versionsCount = 0;
		Int2ObjectMap<String> versionToName = new Int2ObjectOpenHashMap<>();
		Object2IntMap<String> nameToVersion = new Object2IntOpenHashMap<>();
		{
			String lastVersion = null;
			String nextVersion = rootVersion;
			while (nextVersion != null) {
				rawVersionsSequence.add(nextVersion);
				lastVersion = nextVersion;
				versionToName.put(versionsCount, nextVersion);
				nameToVersion.put(nextVersion, versionsCount);
				nextVersion = getNextVersion(nextVersionMap, nextVersion);
				versionsCount++;
			}
			if (!Objects.equals(lastVersion, currentVersionKey)) {
				throw new IllegalArgumentException("Last version " + lastVersion
						+ " is different than the defined current version: " + currentVersionKey);
			}
		}

		int latestVersion = versionsCount - 1;

		// Collect all existing base types
		List<String> baseTypes = new ArrayList<>(baseTypesData.keySet());

		// Collect all existing super types
		List<String> superTypes = new ArrayList<>(superTypesData.keySet());

		// Collect all custom types
		List<String> customTypes = new ArrayList<>(customTypesData.keySet());

		List<String> allTypes = Stream.concat(Stream.concat(Stream.concat(baseTypes.stream(), superTypes.stream()),
								customTypes.stream()), NATIVE_TYPES.stream())
				.distinct()
				.toList();

		Stream.concat(Stream.concat(Stream.concat(baseTypes.stream(), superTypes.stream()),
						customTypes.stream()), NATIVE_TYPES.stream())
				.collect(Collectors.groupingBy(Function.identity()))
				.values()
				.stream()
				.filter(x -> x.size() > 1)
				.forEach(x -> {
					var type = x.get(0);
					throw new IllegalArgumentException("Type " + type + " has been defined more than once (check base, super, and custom types)!");
				});

		// Compute the numeric versions map
		Int2ObjectMap<ParsedVersion> versions = new Int2ObjectOpenHashMap<>();
		rawVersions.forEach((k, v) -> versions.put(nameToVersion.getInt(k), new ParsedVersion(v)));

		Int2ObjectMap<Map<String, ParsedClass>> computedClassConfig = new Int2ObjectOpenHashMap<>();
		for (int versionIndex = 0; versionIndex < versionsCount; versionIndex++) {
			if (versionIndex == 0) {
				computedClassConfig.put(0, baseTypesData.entrySet().stream()
						.map(e -> Map.entry(e.getKey(), new ParsedClass(e.getValue())))
						.collect(Collectors.toMap(Entry::getKey, Entry::getValue))
				);
			} else {
				var version = versions.get(versionIndex);
				Map<String, ParsedClass> prevVersionConfiguration
						= requireNonNull(computedClassConfig.get(versionIndex - 1));
				Map<String, ParsedClass> newVersionConfiguration = prevVersionConfiguration.entrySet().stream()
						.map(entry -> Map.entry(entry.getKey(), entry.getValue().clone()))
						.collect(Collectors.toMap(Entry::getKey, Entry::getValue));

				for (VersionTransformation rawTransformation : version.transformations) {
					TransformationConfiguration transformation;
					var transformCoordinate = "Transformation at index "
							+ version.transformations.indexOf(rawTransformation) + " in version "
							+ versionToName.get(versionIndex);
					try {
						transformation = rawTransformation.getTransformation();
					} catch (Exception ex) {
						throw new IllegalArgumentException(transformCoordinate + " is not consistent", ex);
					}
					final String transformName = transformation.getTransformName();
					switch (transformName) {
						case "move-data" -> {
							var t = (MoveDataConfiguration) transformation;
							var transformClass = newVersionConfiguration.get(t.transformClass);
							if (transformClass == null) {
								throw new IllegalArgumentException(transformCoordinate + " refers to an unknown type: "
										+ t.transformClass);
							}
							String definition = transformClass.data.remove(t.from);
							if (definition == null) {
								throw new IllegalArgumentException(transformCoordinate + " refers to an unknown field: " + t.from);
							}
							var prevDef = transformClass.data.put(t.to, definition);
							if (prevDef != null) {
								throw new IllegalArgumentException(
										transformCoordinate + " tries to overwrite the existing field \"" + t.to + "\" of value \""
												+ prevDef + "\" with the field \"" + t.from + "\" of type \"" + definition + "\"");
							}
						}
						case "new-data" -> {
							var t = (NewDataConfiguration) transformation;
							var transformClass = newVersionConfiguration.get(t.transformClass);
							if (transformClass == null) {
								throw new IllegalArgumentException(transformCoordinate + " refers to an unknown type: "
										+ t.transformClass);
							}
							if (!allTypes.contains(extractTypeName(t.type))) {
								throw new IllegalArgumentException(transformCoordinate + " refers to an unknown type: " + t.type);
							}
							var prevDef = transformClass.data.put(t.to, fixType(t.type));
							if (prevDef != null) {
								throw new IllegalArgumentException(transformCoordinate + " tries to overwrite the existing field \""
										+ t.to + "\" of value \"" + prevDef
										+ "\" with the new type \"" + t.type + "\"");
							}
						}
						case "remove-data" -> {
							var t = (RemoveDataConfiguration) transformation;
							var transformClass = newVersionConfiguration.get(t.transformClass);
							if (transformClass == null) {
								throw new IllegalArgumentException(transformCoordinate + " refers to an unknown type: "
										+ t.transformClass);
							}
							var prevDef = transformClass.data.remove(t.from);
							if (prevDef == null) {
								throw new IllegalArgumentException(transformCoordinate + " tries to remove the nonexistent field \""
										+ t.from + "\"");
							}
						}
						case "upgrade-data" -> {
							var t = (UpgradeDataConfiguration) transformation;
							var transformClass = newVersionConfiguration.get(t.transformClass);
							if (transformClass == null) {
								throw new IllegalArgumentException(transformCoordinate + " refers to an unknown type: "
										+ t.transformClass);
							}
							if (!allTypes.contains(extractTypeName(t.type))) {
								throw new IllegalArgumentException(transformCoordinate + " refers to an unknown type: " + t.type);
							}
							String prevDefinition = transformClass.data.put(t.from, fixType(t.type));
							if (prevDefinition == null) {
								throw new IllegalArgumentException(transformCoordinate + " refers to an unknown field: " + t.from);
							}
						}
						default -> throw new IllegalArgumentException("Unknown transform name: "+ transformName);
					}
				}

				computedClassConfig.put(versionIndex, newVersionConfiguration);
			}
		}

		this.classConfig = computedClassConfig;
		this.interfacesData = interfacesData.entrySet().stream()
				.map(e -> Map.entry(e.getKey(), new ParsedInterface(e.getValue())))
				.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
		this.versions = versions
				.int2ObjectEntrySet()
				.stream()
				.collect(Collectors.toMap(Int2ObjectMap.Entry::getIntKey,
						e -> new ComputedVersion(e.getValue(),
								e.getIntKey(),
								e.getIntKey() == latestVersion,
								versionToName.get(e.getIntKey()),
								computedClassConfig.get(e.getIntKey())
						),
						(a, b) -> {
							throw new IllegalStateException();
						},
						Int2ObjectOpenHashMap::new
				));
		this.currentVersion = versionsCount - 1;
		this.superTypes = superTypesData;
		this.customTypes = customTypesData;
	}

	@Nullable
	public static String getNextVersion(Map<String, String> versionsSequence, String version) {
		return versionsSequence.get(version);
	}

	private static RuntimeException throwMultiNextVersions(String version, List<String> nextVersions) {
		return new IllegalArgumentException("Found many next versions of version " + version + ":"
				+ String.join(", ", nextVersions));
	}

	private static RuntimeException throwMultiRootVersions(List<String> rootVersions) {
		return new IllegalArgumentException("Found many root versions: " + String.join(", ", rootVersions));
	}

	public static <T> Collector<T, ?, T> toSingleton() {
		return toSingleton(x -> new IllegalStateException());
	}

	public static <T> Collector<T, ?, T> toSingleton(Function<List<T>, RuntimeException> exceptionGenerator) {
		return Collectors.collectingAndThen(
				Collectors.toList(),
				list -> {
					if (list.size() != 1) {
						throw exceptionGenerator.apply(list);
					}
					return list.get(0);
				}
		);
	}

	public static <T> Collector<T, ?, Optional<T>> toOptional() {
		return toOptional(x -> new IllegalStateException());
	}

	public static <T> Collector<T, ?, Optional<T>> toOptional(Function<List<T>, RuntimeException> exceptionGenerator) {
		return Collectors.collectingAndThen(
				Collectors.toList(),
				list -> {
					if (list.size() > 1) {
						throw exceptionGenerator.apply(list);
					}
					if (list.isEmpty()) {
						return Optional.empty();
					}
					return Optional.of(list.get(0));
				}
		);
	}

	public int computeHash() {
		return hash;
	}

	public Set<Entry<String, ParsedInterface>> getInterfacesSet() {
		return interfacesData.entrySet();
	}

	public static String fixType(String fieldType) {
		if (fieldType.endsWith("[]") && fieldType.startsWith("-")) {
			throw new UnsupportedOperationException("Arrays cannot be null");
		}
		if (fieldType.endsWith("[]")) {
			return "§" + fieldType.substring(0, fieldType.length() - 2);
		} else {
			return fieldType;
		}
	}

	/**
	 * Get the base type
	 * X --> X
	 * -X --> X
	 * X[] --> X
	 */
	public static String extractTypeName(String fieldType) {
		if (fieldType.endsWith("[]") && fieldType.startsWith("-")) {
			throw new UnsupportedOperationException("Arrays cannot be null");
		}
		if (fieldType.endsWith("[]")) {
			return fieldType.substring(0, fieldType.length() - 2);
		} else if (fieldType.startsWith("-")) {
			return fieldType.substring(1);
		} else {
			return fieldType;
		}
	}

	public ObjectCollection<ComputedVersion> getVersionsSet() {
		return this.versions.values();
	}

	public int getCurrentVersionNumber() {
		return currentVersion;
	}

	public ComputedVersion getCurrentVersion() {
		return versions.get(currentVersion);
	}

	public Map<String, Set<String>> getSuperTypes() {
		return this.superTypes;
	}

	public Optional<ComputedVersion> getNextVersion(ComputedVersion versionConfiguration) {
		var nextVersion = versions.get(versionConfiguration.getVersion() + 1);
		return Optional.ofNullable(nextVersion);
	}

	public ComputedVersion getNextVersionOrThrow(ComputedVersion versionConfiguration) {
		return Objects.requireNonNull(versions.get(versionConfiguration.getVersion() + 1));
	}

	public Map<String, ParsedInterface> getInterfaces() {
		return interfacesData;
	}

	public Map<String, CustomTypesConfiguration> getCustomTypes() {
		return customTypes;
	}
}
