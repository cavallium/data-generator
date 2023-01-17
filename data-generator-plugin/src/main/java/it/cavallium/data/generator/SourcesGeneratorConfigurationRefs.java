package it.cavallium.data.generator;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class SourcesGeneratorConfigurationRefs {
	public Map<String, Map<String, Set<String>>> superTypes;
	public Map<String, Map<String, CustomTypesConfiguration>> customTypes;
	public Map<String, Map<String, ClassConfiguration>> classes;
	public Map<String, List<VersionTransformation>> transformations;

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		SourcesGeneratorConfigurationRefs that = (SourcesGeneratorConfigurationRefs) o;
		return Objects.equals(superTypes, that.superTypes) && Objects.equals(customTypes, that.customTypes)
				&& Objects.equals(classes, that.classes) && Objects.equals(transformations, that.transformations);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(superTypes);
		hash += ConfigUtils.hashCode(customTypes);
		hash += ConfigUtils.hashCode(classes);
		hash += ConfigUtils.hashCode(transformations);
		return hash;
	}
}
