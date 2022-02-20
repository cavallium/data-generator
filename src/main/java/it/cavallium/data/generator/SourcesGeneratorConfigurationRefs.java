package it.cavallium.data.generator;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class SourcesGeneratorConfigurationRefs {
	public Map<String, Map<String, Set<String>>> superTypes;
	public Map<String, Map<String, CustomTypesConfiguration>> customTypes;
	public Map<String, Map<String, ClassConfiguration>> classes;
	public Map<String, List<VersionTransformation>> transformations;
}
