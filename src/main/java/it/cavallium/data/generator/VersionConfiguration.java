package it.cavallium.data.generator;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class VersionConfiguration {

	public DetailsConfiguration details;
	public Map<String, Set<String>> superTypes;
	public Map<String, CustomTypesConfiguration> customTypes;
	public Map<String, ClassConfiguration> classes;
	public List<VersionTransformation> transformations;
}
