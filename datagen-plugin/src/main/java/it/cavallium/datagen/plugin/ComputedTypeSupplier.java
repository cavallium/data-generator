package it.cavallium.datagen.plugin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public class ComputedTypeSupplier {

	private final Int2ObjectMap<Map<String, ComputedType>> computedTypeMap;
	private final Int2ObjectMap<Map<String, List<ComputedType>>> computedTypeDependentsCacheMap = new Int2ObjectOpenHashMap<>();
	private final ComputedVersion currentVersion;

	public ComputedTypeSupplier(Int2ObjectMap<Map<String, ComputedType>> computedTypeMap, ComputedVersion currentVersion) {
		this.computedTypeMap = computedTypeMap;
		this.currentVersion = currentVersion;
	}

	public ComputedType get(VersionedType type) {
		var computedType = computedTypeMap.get(type.version().getVersion()).get(type.type());
		if (computedType == null) {
			throw new IllegalStateException("Type " + type + " does not exist");
		}
		return computedType;
	}

	public ComputedType get(String type) {
		return get(new VersionedType(type, currentVersion));
	}

	public Stream<ComputedType> getDependencies(VersionedType type) {
		return computedTypeMap.get(type.version().getVersion()).get(type.type()).getDependencies();
	}

	public Stream<ComputedType> getDependents(VersionedType type) {
		synchronized (computedTypeDependentsCacheMap) {
			return computedTypeDependentsCacheMap
					.computeIfAbsent(type.version().getVersion(), x -> new HashMap<>())
					.computeIfAbsent(type.type(),
							typeName -> Objects.requireNonNull(computedTypeMap.get(type.version().getVersion()), () -> "Version " + type.version() + " does not exist")
									.values().stream().filter(computedType ->
											computedType.getDependencies().anyMatch(y -> Objects.equals(y.getName(), typeName))).toList())
					.stream();
		}
	}

	/**
	 * Get dependents from the current version
	 */
	public Stream<ComputedType> getDependents(String type) {
		return getDependents(new VersionedType(type, currentVersion));
	}
}
