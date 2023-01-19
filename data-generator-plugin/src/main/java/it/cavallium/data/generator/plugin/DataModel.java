package it.cavallium.data.generator.plugin;

import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

import it.cavallium.data.generator.plugin.ComputedType.ComputedArrayType;
import it.cavallium.data.generator.plugin.ComputedType.ComputedBaseType;
import it.cavallium.data.generator.plugin.ComputedType.ComputedCustomType;
import it.cavallium.data.generator.plugin.ComputedType.ComputedNativeType;
import it.cavallium.data.generator.plugin.ComputedType.ComputedNullableType;
import it.cavallium.data.generator.plugin.ComputedType.ComputedSuperType;
import it.cavallium.data.generator.plugin.ComputedType.VersionedComputedType;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
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
	private final int hash;
	private final Map<String, ParsedInterface> interfacesData;
	private final Int2ObjectMap<ComputedVersion> versions;
	private final Map<String, Set<String>> superTypes;
	private final Map<String, CustomTypesConfiguration> customTypes;
	private final Int2ObjectMap<Map<String, ComputedType>> computedTypes;

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
		Int2ObjectMap<String> versionToName = new Int2ObjectLinkedOpenHashMap<>();
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
				.collect(Collectors.groupingBy(identity()))
				.values()
				.stream()
				.filter(x -> x.size() > 1)
				.forEach(x -> {
					var type = x.get(0);
					throw new IllegalArgumentException("Type " + type + " has been defined more than once (check base, super, and custom types)!");
				});

		// Compute the numeric versions map
		Int2ObjectMap<ParsedVersion> versions = new Int2ObjectLinkedOpenHashMap<>();
		rawVersions.forEach((k, v) -> versions.put(nameToVersion.getInt(k), new ParsedVersion(v)));

		Int2ObjectMap<Map<String, ParsedClass>> computedClassConfig = new Int2ObjectLinkedOpenHashMap<>();
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
							transformClass.changed = true;
							var definition = removeAndGetIndex(transformClass.data, t.from);
							if (definition.isEmpty()) {
								throw new IllegalArgumentException(transformCoordinate + " refers to an unknown field: " + t.from);
							}
							var prevDef = tryInsertAtIndex(transformClass.data,
									t.to,
									definition.get().getValue(),
									definition.get().getKey()
							);
							if (prevDef != null) {
								throw new IllegalArgumentException(
										transformCoordinate + " tries to overwrite the existing field \"" + t.to + "\" of value \""
												+ prevDef + "\" with the field \"" + t.from + "\" of type \"" + definition.orElse(null) + "\"");
							}
						}
						case "new-data" -> {
							var t = (NewDataConfiguration) transformation;
							var transformClass = newVersionConfiguration.get(t.transformClass);
							if (transformClass == null) {
								throw new IllegalArgumentException(transformCoordinate + " refers to an unknown type: "
										+ t.transformClass);
							}
							transformClass.changed = true;
							if (!allTypes.contains(extractTypeName(t.type))) {
								throw new IllegalArgumentException(transformCoordinate + " refers to an unknown type: " + t.type);
							}
							String prevDef;
							if (t.index != null) {
								prevDef = tryInsertAtIndex(transformClass.data, t.to, fixType(t.type), t.index);
							} else {
								prevDef = transformClass.data.putIfAbsent(t.to, fixType(t.type));
							}
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
							transformClass.changed = true;
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
							transformClass.changed = true;
							if (!allTypes.contains(extractTypeName(t.type))) {
								throw new IllegalArgumentException(transformCoordinate + " refers to an unknown type: " + t.type);
							}
							String prevDefinition = transformClass.data.replace(t.from, fixType(t.type));
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

		Int2ObjectMap<Map<String, ComputedType>> computedTypes = new Int2ObjectLinkedOpenHashMap<>();
		ComputedTypeSupplier computedTypeSupplier = new ComputedTypeSupplier(computedTypes);
		for (int versionIndex = 0; versionIndex < versionsCount; versionIndex++) {
			int versionIndexF = versionIndex;
			if (versionIndexF == 0) {
				// Compute base types
				List<ComputedType> versionBaseTypes = computedClassConfig.get(versionIndexF).entrySet().stream()
						.map(e -> {
							var data = new LinkedHashMap<String, VersionedType>();
							e.getValue().getData().forEach((key, value) -> data.put(key, new VersionedType(value, versionIndexF)));
							return new ComputedBaseType(new VersionedType(e.getKey(), versionIndexF),
									e.getValue().stringRepresenter, data, computedTypeSupplier);
						}).collect(Collectors.toList());
				// Compute custom types
				customTypesData.forEach((name, data) -> versionBaseTypes.add(new ComputedCustomType(name,
						data.getJavaClassString(), data.serializer, computedTypeSupplier)));
				// Compute super types
				superTypesData.forEach((key, data) -> {
					List<VersionedType> subTypes = data.stream().map(x -> new VersionedType(x, versionIndexF)).toList();
					versionBaseTypes.add(new ComputedSuperType(new VersionedType(key, versionIndexF), subTypes, computedTypeSupplier));
				});
				// Compute nullable types
				computedClassConfig.values().stream()
						.flatMap(x -> x.values().stream())
						.flatMap(x -> x.getData().values().stream())
						.filter(x -> x.startsWith("-"))
						.map(nullableName -> new VersionedType(nullableName.substring(1), versionIndexF))
						.map(baseType -> new ComputedNullableType(baseType, computedTypeSupplier))
						.forEach(versionBaseTypes::add);
				// Compute array types
				computedClassConfig.values().stream()
						.flatMap(x -> x.values().stream())
						.flatMap(x -> x.getData().values().stream())
						.filter(x -> x.startsWith("§"))
						.map(nullableName -> new VersionedType(nullableName.substring(1), versionIndexF))
						.map(baseType -> new ComputedArrayType(baseType, computedTypeSupplier))
						.forEach(versionBaseTypes::add);
				// Compute native types
				versionBaseTypes.addAll(ComputedNativeType.get(computedTypeSupplier));

				computedTypes.put(versionIndexF,
						versionBaseTypes.stream().distinct().collect(Collectors.toMap(ComputedType::getName, identity())));
			} else {
				Set<String> changedTypes = computedTypes.get(versionIndexF - 1).values().stream()
						.filter(prevType -> prevType instanceof ComputedBaseType prevBaseType
								&& computedClassConfig.get(versionIndexF).get(prevBaseType.getName()).changed)
						.map(ComputedType::getName)
						.collect(Collectors.toSet());

				{
					boolean addedMoreTypes;
					do {
						var newChangedTypes = changedTypes
								.parallelStream()
								.flatMap(changedType -> computedTypes.get(versionIndexF - 1).get(changedType).getDependents())
								.map(ComputedType::getName)
								.distinct()
								.toList();
						addedMoreTypes = changedTypes.addAll(newChangedTypes);
					} while (addedMoreTypes);
				}

				Map<String, ComputedType> currentVersionComputedTypes = new HashMap<>();
				var versionChangeChecker = new VersionChangeChecker(changedTypes);
				computedTypes.get(versionIndexF - 1).forEach((name, type) -> {
					if (!changedTypes.contains(name)) {
						currentVersionComputedTypes.put(name, type);
					} else {
						if (type instanceof VersionedComputedType versionedComputedType) {
							ComputedType newType = versionedComputedType.withChangeAtVersion(versionIndexF, versionChangeChecker);
							currentVersionComputedTypes.put(name, newType);
						} else {
							throw new IllegalStateException();
						}
					}
				});
				computedTypes.put(versionIndexF, currentVersionComputedTypes);
			}
		}

		/*
		Example upgrade:

		V001======================================
		_Message                        v1
		 |__MessageForwardOrigin        v1
		 |__MessageText                 v1

		_MessageText

		_MessageForwardOrigin           v1
		 |__MessageForwardOriginChat    v1

		_MessageForwardOriginChat       v1
		 |__ChatEntityId                v1

		_UserId                         v1

		_ChatEntityId                   v1
		 |__UserId                      v1
		 |__SupergroupId                v1
		 |__BaseGroupId                 v1


		V002======================================
		* UserId changed
		==========================================
		_Message                        v2    *
		 |__MessageForwardOrigin        v2    *
		 |__MessageText                 v1

		_MessageText                    v1

		_MessageForwardOrigin           v2    *
		 |__MessageForwardOriginChat    v2    *

		_MessageForwardOriginChat       v2    *
		 |__ChatEntityId                v2    *

		_UserId                         v2    *

		_ChatEntityId                   v2    *
		 |__UserId                      v2    *
		 |__SupergroupId                v1
		 |__BaseGroupId                 v1
		 */

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
						Int2ObjectLinkedOpenHashMap::new
				));
		LongAdder unchangedTot = new LongAdder();
		LongAdder changedTot = new LongAdder();
		computedTypes.forEach((version, types) -> {
			System.out.println("Version: " + version);
			System.out.println("\tTypes: " + types.size());
			System.out.println("\tVersioned types: " + types.values().stream().filter(t -> (t instanceof VersionedComputedType)).count());
			var unchanged = types.values().stream().filter(t -> (t instanceof VersionedComputedType versionedComputedType && versionedComputedType.getVersion() < version)).count();
			var changed = types.values().stream().filter(t -> (t instanceof VersionedComputedType versionedComputedType && versionedComputedType.getVersion() == version)).count();
			unchangedTot.add(unchanged);
			changedTot.add(changed);
			System.out.println("\t\tUnchanged: " + unchanged + " (" + (unchanged * 100 / (changed + unchanged)) + "%)");
			System.out.println("\t\tChanged: " + changed + " (" + (changed * 100 / (changed + unchanged)) + "%)");
		});
		System.out.println("Result:");
		var unchanged = unchangedTot.sum();
		var changed = changedTot.sum();
		System.out.println("\tAvoided type versions: " + unchanged + " (" + (unchanged * 100 / (changed + unchanged)) + "%)");
		System.out.println("\tType versions: " + changed + " (" + (changed * 100 / (changed + unchanged)) + "%)");
		this.currentVersion = versionsCount - 1;
		this.superTypes = superTypesData;
		this.customTypes = customTypesData;
		this.computedTypes = computedTypes;
	}

	private String tryInsertAtIndex(LinkedHashMap<String, String> data, String key, String value, int index) {
		var before = new LinkedHashMap<String, String>();
		var after = new LinkedHashMap<String, String>();
		int i = 0;
		for (Entry<String, String> entry : data.entrySet()) {
			if (i < index) {
				before.put(entry.getKey(), entry.getValue());
			} else {
				after.put(entry.getKey(), entry.getValue());
			}
			i++;
		}
		data.clear();
		data.putAll(before);
		var prev = data.putIfAbsent(key, value);
		data.putAll(after);
		return prev;
	}

	private Optional<Entry<Integer, String>> removeAndGetIndex(LinkedHashMap<String, String> data, String find) {
		int foundIndex = -1;
		{
			int i = 0;
			for (Entry<String, String> entry : data.entrySet()) {
				if (entry.getKey().equals(find)) {
					foundIndex = i;
				}
				i++;
			}
		}
		if (foundIndex == -1) return Optional.empty();
		return Optional.of(Map.entry(foundIndex, requireNonNull(data.remove(find))));
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

	public Int2ObjectMap<Map<String, ComputedType>> getComputedTypes() {
		return computedTypes;
	}
}
