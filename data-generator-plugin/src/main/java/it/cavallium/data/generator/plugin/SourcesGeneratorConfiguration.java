package it.cavallium.data.generator.plugin;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class SourcesGeneratorConfiguration {
	public String currentVersion;
	public Map<String, InterfaceDataConfiguration> interfacesData;
	public Map<String, ClassConfiguration> baseTypesData;
	public Map<String, Set<String>> superTypesData;
	public Map<String, CustomTypesConfiguration> customTypesData;
	public Map<String, VersionConfiguration> versions;

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		SourcesGeneratorConfiguration that = (SourcesGeneratorConfiguration) o;
		return Objects.equals(currentVersion, that.currentVersion) && Objects.equals(interfacesData, that.interfacesData)
				&& Objects.equals(baseTypesData, that.baseTypesData) && Objects.equals(superTypesData, that.superTypesData)
				&& Objects.equals(customTypesData, that.customTypesData) && Objects.equals(versions, that.versions);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(currentVersion);
		hash += ConfigUtils.hashCode(interfacesData);
		hash += ConfigUtils.hashCode(superTypesData);
		hash += ConfigUtils.hashCode(customTypesData);
		hash += ConfigUtils.hashCode(versions);
		return hash;
	}

	public DataModel buildDataModel() {
		return new DataModel(hashCode(),
				currentVersion,
				Objects.requireNonNullElse(interfacesData, Map.of()),
				Objects.requireNonNullElse(baseTypesData, Map.of()),
				Objects.requireNonNullElse(superTypesData, Map.of()),
				Objects.requireNonNullElse(customTypesData, Map.of()),
				Objects.requireNonNullElse(versions, Map.of())
		);
	}
}
