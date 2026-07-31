package it.cavallium.datagen.plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Configuration for one generated, version-aware projection reader. */
public final class ProjectionConfiguration {

	/** Base record whose serialized payload is read. */
	public String sourceType;

	/** Ordered result component name to source field path mapping. */
	public LinkedHashMap<String, String> fields;

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (object == null || getClass() != object.getClass()) {
			return false;
		}
		ProjectionConfiguration that = (ProjectionConfiguration) object;
		return Objects.equals(sourceType, that.sourceType) && entriesEqual(fields, that.fields);
	}

	private static <K, V> boolean entriesEqual(LinkedHashMap<K, V> first, LinkedHashMap<K, V> second) {
		if (first == second) return true;
		if (first == null || second == null) return false;
		return List.copyOf(first.entrySet()).equals(List.copyOf(second.entrySet()));
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(sourceType);
		hash += fields == null ? 0 : List.copyOf(fields.entrySet()).hashCode();
		return hash;
	}
}
