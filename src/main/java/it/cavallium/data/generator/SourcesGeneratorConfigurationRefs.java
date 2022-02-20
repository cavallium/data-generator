package it.cavallium.data.generator;

import it.cavallium.data.generator.SourcesGeneratorConfiguration.ClassConfiguration;
import it.cavallium.data.generator.SourcesGeneratorConfiguration.CustomTypesConfiguration;
import it.cavallium.data.generator.SourcesGeneratorConfiguration.VersionTransformation;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SourcesGeneratorConfigurationRefs {
	public Map<String, Set<String>> superTypes;
	public Map<String, CustomTypesConfiguration> customTypes;
	public Map<String, ClassConfiguration> classes;
	public List<VersionTransformation> transformations;
}
