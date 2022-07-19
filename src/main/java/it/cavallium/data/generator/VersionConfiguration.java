package it.cavallium.data.generator;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class VersionConfiguration {

	public DetailsConfiguration details;
	public Map<String, Set<String>> superTypes;
	public Map<String, CustomTypesConfiguration> customTypes;
	public Map<String, ClassConfiguration> classes;
	public List<VersionTransformation> transformations;

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		VersionConfiguration that = (VersionConfiguration) o;
		return Objects.equals(details, that.details) && Objects.equals(superTypes, that.superTypes) && Objects.equals(
				customTypes,
				that.customTypes
		) && Objects.equals(classes, that.classes) && Objects.equals(transformations, that.transformations);
	}

	@Override
	public int hashCode() {
		return Objects.hash(details, superTypes, customTypes, classes, transformations);
	}
}
