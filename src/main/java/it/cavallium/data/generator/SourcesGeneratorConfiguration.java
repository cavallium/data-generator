package it.cavallium.data.generator;

import java.util.Map;

public class SourcesGeneratorConfiguration {
	public String currentVersion;
	public Map<String, InterfaceDataConfiguration> interfacesData;
	public Map<String, VersionConfiguration> versions;
	public SourcesGeneratorConfigurationRefs refs;

}
