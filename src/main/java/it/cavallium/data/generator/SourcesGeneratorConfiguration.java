package it.cavallium.data.generator;

import java.util.Map;
import java.util.Objects;

public class SourcesGeneratorConfiguration {
	public String currentVersion;
	public Map<String, InterfaceDataConfiguration> interfacesData;
	public Map<String, VersionConfiguration> versions;
	public SourcesGeneratorConfigurationRefs refs;

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
				&& Objects.equals(versions, that.versions) && Objects.equals(refs, that.refs);
	}

	@Override
	public int hashCode() {
		return Objects.hash(currentVersion, interfacesData, versions, refs);
	}
}
