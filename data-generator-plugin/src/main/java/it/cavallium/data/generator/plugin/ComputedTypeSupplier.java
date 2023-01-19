package it.cavallium.data.generator.plugin;

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

	public ComputedTypeSupplier(Int2ObjectMap<Map<String, ComputedType>> computedTypeMap) {
		this.computedTypeMap = computedTypeMap;
	}

	public ComputedType get(VersionedType type) {
		var computedType = computedTypeMap.get(type.version()).get(type.type());
		if (computedType == null) {
			throw new IllegalStateException("Type " + type + " does not exist");
		}
		return computedType;
	}

	public Stream<ComputedType> getDependencies(VersionedType type) {
		return computedTypeMap.get(type.version()).get(type.type()).getDependencies();
	}

	public Stream<ComputedType> getDependents(VersionedType type) {
		synchronized (computedTypeDependentsCacheMap) {
			return computedTypeDependentsCacheMap
					.computeIfAbsent(type.version(), x -> new HashMap<>())
					.computeIfAbsent(type.type(),
							typeName -> computedTypeMap.get(type.version()).values().stream().filter(computedType ->
									computedType.getDependencies().anyMatch(y -> Objects.equals(y.getName(), typeName))).toList())
					.stream();
		}
	}
}
