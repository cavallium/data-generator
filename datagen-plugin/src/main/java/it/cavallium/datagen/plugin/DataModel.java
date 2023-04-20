package it.cavallium.datagen.plugin;

import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

import it.cavallium.datagen.plugin.ComputedType.VersionedComputedType;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import java.util.ArrayList;
import java.util.Comparator;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataModel {

	private static final Logger logger = LoggerFactory.getLogger(DataModel.class);

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

	private final ComputedVersion currentVersion;
	private final int hash;
	private final Map<String, ParsedInterface> interfacesData;
	private final Int2ObjectMap<ComputedVersion> versions;
	private final Map<String, Set<String>> superTypes;
	private final Map<String, CustomTypesConfiguration> customTypes;
	private final Int2ObjectMap<Map<String, ComputedType>> computedTypes;
	private final Map<VersionedType, VersionedType> versionedTypePrevVersion;
	private final Map<VersionedType, VersionedType> versionedTypeNextVersion;
	private final Int2ObjectMap<Map<String, List<TransformationConfiguration>>> baseTypeDataChanges;

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

		// Check if the first version has no transformations
		rawVersions
				.values()
				.stream()
				.filter(v -> v.previousVersion == null && v.transformations != null && !v.transformations.isEmpty())
				.forEach(v -> {
					throw new IllegalArgumentException("First version must not have any transformation");
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
		int versionsCount = 0;
		Int2ObjectMap<String> versionToName = new Int2ObjectLinkedOpenHashMap<>();
		Object2IntMap<String> nameToVersion = new Object2IntOpenHashMap<>();
		{
			String lastVersion = null;
			String nextVersion = rootVersion;
			while (nextVersion != null) {
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

		// Compute all types, excluding nullables and arrays
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

		record RawVersionedType(String type, int version) {}

		// Compute the numeric versions map
		Int2ObjectMap<ParsedVersion> parsedVersions = new Int2ObjectLinkedOpenHashMap<>();
		rawVersions.forEach((k, v) -> parsedVersions.put(nameToVersion.getInt(k), new ParsedVersion(v)));

		Int2ObjectMap<Map<String, ParsedClass>> computedClassConfig = new Int2ObjectLinkedOpenHashMap<>();
		for (int versionIndex = 0; versionIndex < versionsCount; versionIndex++) {
			if (versionIndex == 0) {
				computedClassConfig.put(0, baseTypesData.entrySet().stream()
						.map(e -> Map.entry(e.getKey(), new ParsedClass(e.getValue())))
						.collect(Collectors.toMap(Entry::getKey, Entry::getValue))
				);
			} else {
				var version = parsedVersions.get(versionIndex);
				Map<String, ParsedClass> prevVersionConfiguration
						= requireNonNull(computedClassConfig.get(versionIndex - 1));
				Map<String, ParsedClass> newVersionConfiguration = prevVersionConfiguration.entrySet().stream()
						.map(entry -> {
							var parsedClass = entry.getValue().copy();
							parsedClass.differentThanPrev = null;
							parsedClass.differentThanNext = false;
							return Map.entry(entry.getKey(), parsedClass);
						})
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
							transformClass.addDifferentThanPrev(transformation);
							var definition = removeAndGetIndex(transformClass.data, t.from);
							if (definition.isEmpty()) {
								throw new IllegalArgumentException(transformCoordinate + " refers to an unknown field: " + t.from);
							}
							String prevDef;
							if (t.index != null) {
								prevDef = tryInsertAtIndex(transformClass.data,
										t.to,
										definition.get().getValue(),
										t.index
								);
							} else {
								prevDef = tryInsertAtIndex(transformClass.data,
										t.to,
										definition.get().getValue(),
										definition.get().getKey()
								);
							}
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
							transformClass.addDifferentThanPrev(transformation);
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
							transformClass.addDifferentThanPrev(transformation);
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
							transformClass.addDifferentThanPrev(transformation);
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

		// Compute the versions
		var computedVersions = parsedVersions
				.int2ObjectEntrySet()
				.stream()
				.collect(Collectors.toMap(Int2ObjectMap.Entry::getIntKey,
						e -> new ComputedVersion(e.getValue(),
								e.getIntKey(),
								e.getIntKey() == latestVersion,
								versionToName.get(e.getIntKey())
						),
						(a, b) -> {
							throw new IllegalStateException();
						},
						Int2ObjectLinkedOpenHashMap::new
				));

		// Compute the types
		Object2IntMap<String> currentOrNewerTypeVersion = new Object2IntOpenHashMap<>();
		Int2ObjectMap<Map<String, ComputedType>> computedTypes = new Int2ObjectLinkedOpenHashMap<>();
		Int2ObjectMap<Map<String, ComputedType>> randomComputedTypes = new Int2ObjectOpenHashMap<>();
		Int2ObjectMap<Map<String, List<TransformationConfiguration>>> baseTypeDataChanges = new Int2ObjectOpenHashMap<>();
		ComputedTypeSupplier computedTypeSupplier = new ComputedTypeSupplier(randomComputedTypes, computedVersions.get(latestVersion));
		{
			for (int versionIndex = 0; versionIndex <= latestVersion; versionIndex++) {
				var versionChanges = new HashMap<String, List<TransformationConfiguration>>();
				baseTypeDataChanges.put(versionIndex, versionChanges);
				computedClassConfig.get(versionIndex).forEach((dataType, data) -> {
					versionChanges.put(dataType, Objects.requireNonNullElse(data.differentThanPrev, List.of()));
				});
			}
			for (int versionNumber = latestVersion - 1; versionNumber >= 0; versionNumber--) {
				var version = computedClassConfig.get(versionNumber);
				computedClassConfig.get(versionNumber + 1).forEach((type, typeConfig) -> {
					if (typeConfig.differentThanPrev != null) {
						version.get(type).differentThanNext = true;
					}
				});
			}
			for (int versionIndex = latestVersion; versionIndex >= 0; versionIndex--) {
				int versionIndexF = versionIndex;
				var version = computedVersions.get(versionIndexF);
				if (versionIndexF == latestVersion) {
					// Compute base types
					List<ComputedType> versionBaseTypes = computedClassConfig.get(versionIndexF).entrySet().stream()
							.map(e -> {
								var data = new LinkedHashMap<String, VersionedType>();
								e.getValue().getData().forEach((key, value) -> data.put(key, new VersionedType(value, version)));
								return new ComputedTypeBase(new VersionedType(e.getKey(), version),
										e.getValue().stringRepresenter, data, computedTypeSupplier);
							}).collect(Collectors.toList());
					// Compute custom types
					customTypesData.forEach((name, data) -> versionBaseTypes.add(new ComputedTypeCustom(name,
							data.getJavaClassString(), data.serializer, computedTypeSupplier, computedVersions.get(latestVersion))));
					// Compute super types
					superTypesData.forEach((key, data) -> {
						List<VersionedType> subTypes = data.stream().map(x -> new VersionedType(x, version)).toList();
						versionBaseTypes.add(new ComputedTypeSuper(new VersionedType(key, version), subTypes, computedTypeSupplier));
					});
					// Compute nullable types
					{
						var nullableRawTypes = computedClassConfig.values().stream()
								.flatMap(x -> x.values().stream())
								.flatMap(x -> x.getData().values().stream())
								.filter(x -> x.startsWith("-"))
								.map(nullableName -> nullableName.substring(1))
								.toList();
						// Compute nullable versioned types
						nullableRawTypes.stream()
								.filter(key -> superTypesData.containsKey(key) || baseTypesData.containsKey(key))
								.map(nullableName -> new VersionedType(nullableName, version))
								.map(baseType -> new ComputedTypeNullableVersioned(baseType, computedTypeSupplier))
								.forEach(versionBaseTypes::add);
						// Compute nullable other types
						nullableRawTypes.stream()
								.filter(customTypesData::containsKey)
								.map(baseType -> new ComputedTypeNullableFixed(baseType, computedVersions.get(latestVersion), computedTypeSupplier))
								.forEach(versionBaseTypes::add);
						// Compute nullable native types
						nullableRawTypes.stream()
								.filter(NATIVE_TYPES::contains)
								.map(baseType ->
										new ComputedTypeNullableNative(baseType, computedVersions.get(latestVersion), computedTypeSupplier))
								.forEach(versionBaseTypes::add);
					}
					// Compute array types
					{
						var arrayRawTypes = computedClassConfig.values().stream()
								.flatMap(x -> x.values().stream())
								.flatMap(x -> x.getData().values().stream())
								.filter(x -> x.startsWith("§"))
								.map(nullableName -> nullableName.substring(1))
								.toList();
						// Compute array versioned types
						arrayRawTypes.stream()
								.filter(key -> superTypesData.containsKey(key) || baseTypesData.containsKey(key))
								.map(nullableName -> new VersionedType(nullableName, version))
								.map(baseType -> new ComputedTypeArrayVersioned(baseType, computedTypeSupplier))
								.forEach(versionBaseTypes::add);
						// Compute array other types
						arrayRawTypes.stream()
								.filter(customTypesData::containsKey)
								.map(type -> new ComputedTypeArrayFixed(type, computedVersions.get(latestVersion), computedTypeSupplier))
								.forEach(versionBaseTypes::add);
						// Compute array native types
						arrayRawTypes.stream()
								.filter(NATIVE_TYPES::contains)
								.map(baseType ->
										new ComputedTypeArrayNative(baseType, computedTypeSupplier))
								.forEach(versionBaseTypes::add);
					}
					// Compute native types
					versionBaseTypes.addAll(ComputedTypeNative.get(computedTypeSupplier));

					var allLatestTypes = versionBaseTypes.stream().distinct().collect(Collectors.toMap(ComputedType::getName, identity()));

					allLatestTypes.forEach((typeName, type) -> currentOrNewerTypeVersion.put(typeName, latestVersion));

					randomComputedTypes.put(versionIndexF, allLatestTypes);
				} else {
					Set<String> changedTypes = randomComputedTypes.get(versionIndexF + 1).values().stream()
							.filter(prevType -> prevType instanceof ComputedTypeBase prevBaseType
									&& computedClassConfig.get(versionIndexF).get(prevBaseType.getName()).differentThanNext)
							.map(ComputedType::getName)
							.collect(Collectors.toSet());

					{
						boolean addedMoreTypes;
						do {
							var newChangedTypes = changedTypes
									.parallelStream()
									.flatMap(changedType -> randomComputedTypes.get(versionIndexF + 1).get(changedType).getDependents())
									.map(ComputedType::getName)
									.distinct()
									.toList();
							addedMoreTypes = changedTypes.addAll(newChangedTypes);
						} while (addedMoreTypes);
					}

					for (String changedType : changedTypes) {
						currentOrNewerTypeVersion.put(changedType, versionIndexF);
					}

					Map<String, ComputedType> currentVersionComputedTypes = new HashMap<>();
					var versionChangeChecker = new VersionChangeChecker(changedTypes, versionIndexF, latestVersion);
					randomComputedTypes.get(versionIndexF + 1).forEach((computedTypeName, computedType) -> {
						if (!changedTypes.contains(computedTypeName)) {
							currentVersionComputedTypes.put(computedTypeName, computedType);
						} else {
							if (computedType instanceof VersionedComputedType versionedComputedType) {
								ParsedClass parsedClass = computedClassConfig.get(versionIndexF).get(computedTypeName);
								LinkedHashMap<String, VersionedType> parsedFields;
								if (parsedClass != null) {
									parsedFields = parsedClass.data
											.entrySet()
											.stream()
											.collect(Collectors.toMap(Entry::getKey, e -> {
												var fieldTypeName = e.getValue();
												return new VersionedType(fieldTypeName, computedVersions.get(currentOrNewerTypeVersion.getInt(fieldTypeName)));
											}, (a, b) -> {
												throw new IllegalStateException();
											}, LinkedHashMap::new));
								} else {
									parsedFields = new LinkedHashMap<>();
								}
								ComputedType olderComputedType = versionedComputedType.withChangeAtVersion(version,
										versionChangeChecker,
										parsedFields
								);
								currentVersionComputedTypes.put(computedTypeName, olderComputedType);
							} else {
								throw new IllegalStateException();
							}
						}
					});
					randomComputedTypes.put(versionIndexF, currentVersionComputedTypes);
				}
			}
			for (int i = 0; i < versionsCount; i++) {
				computedTypes.put(i, Objects.requireNonNull(randomComputedTypes.get(i)));
			}
		}
		// All types, including arrays, nullables, primitives, etc
		var allComputedTypes = computedTypes.values().stream().flatMap(x -> x.values().stream()).distinct().toList();
		// Compute the upgrade paths
		Map<VersionedType, VersionedType> versionedTypeNextVersion = new HashMap<>();
		Map<VersionedType, VersionedType> versionedTypePrevVersion = new HashMap<>();
		Map<String, List<VersionedType>> versionedTypeVersions = allComputedTypes
				.stream()
				.filter(x -> x instanceof VersionedComputedType)
				.map(x -> (VersionedComputedType) x)
				.collect(Collectors.groupingBy(ComputedType::getName))
				.entrySet()
				.stream()
				.collect(Collectors.toMap(Entry::getKey, e -> e
						.getValue()
						.stream()
						.sorted(Comparator.comparingInt(x -> x.getVersion().getVersion()))
						.map(x -> new VersionedType(e.getKey(), x.getVersion()))
						.toList()));
		versionedTypeVersions.forEach((type, versionsList) -> {
			VersionedType prev = null;
			for (VersionedType versionedType : versionsList) {
				if (prev != null) {
					versionedTypePrevVersion.put(versionedType, prev);
				}
				prev = versionedType;
			}
			prev = null;
			for (int i = versionsList.size() - 1; i >= 0; i--) {
				var versionedType = versionsList.get(i);
				if (prev != null) {
					versionedTypeNextVersion.put(versionedType, prev);
				}
				prev = versionedType;
			}
		});

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

		this.versions = computedVersions;
		this.interfacesData = interfacesData.entrySet().stream()
				.map(e -> Map.entry(e.getKey(), new ParsedInterface(e.getValue())))
				.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
		LongAdder unchangedTot = new LongAdder();
		LongAdder changedTot = new LongAdder();
		computedTypes.forEach((version, types) -> {
			logger.debug("Version: {}", version);
			logger.debug("\tTypes: {}", types.size());
			logger.debug("\tVersioned types: {}", types.values().stream().filter(t -> (t instanceof VersionedComputedType)).count());
			var unchanged = types.values().stream().filter(t -> (t instanceof VersionedComputedType versionedComputedType
					&& versionedComputedType.getVersion().getVersion() != version)).count();
			var changed = types.values().stream().filter(t -> (t instanceof VersionedComputedType versionedComputedType
					&& versionedComputedType.getVersion().getVersion() == version)).count();
			unchangedTot.add(unchanged);
			changedTot.add(changed);
			logger.debug("\t\tUnchanged: {} ({}%)", unchanged, (unchanged * 100 / Math.max(changed + unchanged, 1)));
			logger.debug("\t\tChanged: {} ({}%)", changed, (changed * 100 / Math.max(changed + unchanged, 1)));
		});
		logger.debug("Result:");
		var unchanged = unchangedTot.sum();
		var changed = changedTot.sum();
		logger.debug("\tAvoided type versions: {} ({}%)", unchanged, (unchanged * 100 / (changed + unchanged)));
		logger.debug("\tType versions: {} ({}%)", changed, (changed * 100 / (changed + unchanged)));
		this.currentVersion = computedVersions.get(versionsCount - 1);
		this.superTypes = superTypesData;
		this.customTypes = customTypesData;
		this.computedTypes = computedTypes;
		this.versionedTypePrevVersion = versionedTypePrevVersion;
		this.versionedTypeNextVersion = versionedTypeNextVersion;
		this.baseTypeDataChanges = baseTypeDataChanges;
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
		return currentVersion.getVersion();
	}

	public ComputedVersion getCurrentVersion() {
		return currentVersion;
	}

	@Deprecated
	public Map<String, Set<String>> getSuperTypesRaw() {
		return this.superTypes;
	}

	public Stream<ComputedTypeSuper> getSuperTypesComputed() {
		return getSuperTypesComputed(currentVersion);
	}

	public Stream<ComputedTypeSuper> getSuperTypesComputed(ComputedVersion version) {
		return this.computedTypes.get(version.getVersion()).values().stream()
				.filter(t -> t instanceof ComputedTypeSuper).map(t -> (ComputedTypeSuper) t);
	}

	public Stream<ComputedTypeBase> getBaseTypesComputed() {
		return getBaseTypesComputed(currentVersion);
	}

	public Stream<ComputedTypeBase> getBaseTypesComputed(ComputedVersion version) {
		return this.computedTypes.get(version.getVersion()).values().stream()
				.filter(t -> t instanceof ComputedTypeBase).map(t -> (ComputedTypeBase) t);
	}

	public Stream<ComputedTypeArray> getArrayTypesComputed(ComputedVersion version) {
		return this.computedTypes.get(version.getVersion()).values().stream()
				.filter(t -> t instanceof ComputedTypeArray).map(t -> (ComputedTypeArray) t);
	}

	public Stream<ComputedTypeNullable> getNullableTypesComputed(ComputedVersion version) {
		return this.computedTypes.get(version.getVersion()).values().stream()
				.filter(t -> t instanceof ComputedTypeNullable).map(t -> (ComputedTypeNullable) t);
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

	@Deprecated
	public Map<String, CustomTypesConfiguration> getCustomTypes() {
		return customTypes;
	}

	public Int2ObjectMap<Map<String, ComputedType>> getComputedTypes() {
		return computedTypes;
	}

	public Map<String, ComputedType> getComputedTypes(ComputedVersion version) {
		return computedTypes.get(version.getVersion());
	}

	public VersionedType getNextVersion(VersionedType type) {
		return versionedTypeNextVersion.get(type);
	}

	public VersionedType getPrevVersion(VersionedType type) {
		return versionedTypePrevVersion.get(type);
	}

	public <T extends ComputedType> T getNextVersion(T type) {
		if (type instanceof VersionedComputedType versionedComputedType) {
			var result = versionedTypeNextVersion.get(new VersionedType(versionedComputedType.getName(), versionedComputedType.getVersion()));
			if (result == null) {
				return null;
			}
			//noinspection unchecked
			return (T) this.computedTypes.get(result.version().getVersion()).get(result.type());
		} else {
			return null;
		}
	}

	public <T extends ComputedType> T getPrevVersion(T type) {
		if (type instanceof VersionedComputedType versionedComputedType) {
			var result = versionedTypePrevVersion.get(new VersionedType(versionedComputedType.getName(), versionedComputedType.getVersion()));
			if (result == null) {
				return null;
			}
			//noinspection unchecked
			return (T) this.computedTypes.get(result.version().getVersion()).get(result.type());
		} else {
			return null;
		}
	}

	public boolean isTypeForVersion(ComputedVersion versionConfiguration, String key) {
		var type = getComputedTypes(versionConfiguration).get(key);
		return type instanceof VersionedComputedType versionedComputedType
				&& versionedComputedType.getVersion().getVersion() == versionConfiguration.getVersion();
	}

	public ComputedVersion getTypeFirstSameVersion(VersionedComputedType type) {
		var prevVersion = getPrevVersion(type);
		if (prevVersion != null) {
			return versions.get(prevVersion.getVersion().getVersion() + 1);
		} else {
			return versions.get(0);
		}
	}

	public Stream<ComputedVersion> getTypeSameVersions(VersionedComputedType type) {
		var initialVersion = getTypeFirstSameVersion(type);
		var lastVersion = type.getVersion();
		return getVersionRange(initialVersion, lastVersion);
	}

	@Deprecated
	public ComputedVersion getVersion(int version) {
		return Objects.requireNonNull(versions.get(version));
	}

	public ComputedVersion getVersion(VersionedComputedType versionedComputedType) {
		return Objects.requireNonNull(versions.get(versionedComputedType.getVersion().getVersion()));
	}

	public Stream<ComputedVersion> getVersionRange(ComputedVersion initialVersionInclusive,
			ComputedVersion lastVersionInclusive) {
		if (initialVersionInclusive.getVersion() > lastVersionInclusive.getVersion()) {
			throw new IllegalArgumentException();
		}
		return IntStream
				.rangeClosed(initialVersionInclusive.getVersion(), lastVersionInclusive.getVersion())
				.mapToObj(versions::get);
	}

	public String getVersionPackage(ComputedVersion version, String basePackageName) {
		return version.getPackage(basePackageName);
	}

	public String getVersionDataPackage(ComputedVersion version, String basePackageName) {
		return version.getDataPackage(basePackageName);
	}

	@Deprecated
	public String getVersionDataPackage(VersionedComputedType type, String basePackageName) {
		return type.getVersion().getDataPackage(basePackageName);
	}

	public String getRootPackage(String basePackageName) {
		return joinPackage(basePackageName, "");
	}

	public static String joinPackage(String basePackageName, String packageName) {
		if (basePackageName.isBlank()) {
			basePackageName = "org.generated";
		}
		if (packageName.isBlank()) {
			return basePackageName;
		} else {
			return basePackageName + "." + packageName;
		}
	}

	/**
	 * @param includeMulti Includes all used super type versions
	 */
	public Stream<ComputedTypeSuper> getSuperTypesOf(VersionedComputedType baseType, boolean includeMulti) {
		ComputedVersion lowestBaseVersion = getTypeFirstSameVersion(baseType);
		if (lowestBaseVersion == null) {
			return Stream.of();
		}
		return getSuperTypesComputed(baseType.getVersion())
				.filter(type -> type.subTypes().contains(baseType))
				.mapMulti((superType, cons) -> {
					if (includeMulti) {
						while (superType != null) {
							ComputedVersion lowestSuperVersion = Objects.requireNonNull(getTypeFirstSameVersion(superType));
							if (lowestSuperVersion.compareTo(lowestBaseVersion) >= 0) {
								cons.accept(superType);
							}
							superType = getPrevVersion(superType);
						}
					} else {
						cons.accept(superType);
					}
				});
	}

	public Stream<ComputedTypeSuper> getExtendsInterfaces(ComputedTypeSuper superType) {
		if (superType.getVersion().isCurrent()) {
			var interfaces = interfacesData.get(superType.getName());
			if (interfaces != null) {
				return interfaces.extendInterfaces.stream()
						.map(name -> (ComputedTypeSuper) this.computedTypes.get(currentVersion.getVersion()).get(name));
			}
		}
		return Stream.of();
	}

	public Stream<Entry<String, ComputedType>> getCommonInterfaceGetters(ComputedTypeSuper superType) {
		if (superType.getVersion().isCurrent()) {
			var interfaces = interfacesData.get(superType.getName());
			if (interfaces != null) {
				return interfaces.commonGetters.entrySet().stream().map(x ->
						Map.entry(x.getKey(), this.computedTypes.get(currentVersion.getVersion()).get(x.getValue())));
			}
		}
		return Stream.of();
	}

	public Stream<Entry<String, ComputedType>> getCommonInterfaceData(ComputedTypeSuper superType) {
		if (superType.getVersion().isCurrent()) {
			var interfaces = interfacesData.get(superType.getName());
			if (interfaces != null) {
				return interfaces.commonData.entrySet().stream().map(x ->
						Map.entry(x.getKey(), this.computedTypes.get(currentVersion.getVersion()).get(x.getValue())));
			}
		}
		return Stream.of();
	}

	public List<TransformationConfiguration> getChanges(ComputedTypeBase nextType) {
		var prev = getPrevVersion(nextType);
		if (prev == null) {
			return List.of();
		}
		return baseTypeDataChanges.get(prev.getVersion().getVersion() + 1).get(nextType.getName());
	}
}
