package it.cavallium.data.generator.plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ComputedVersion {

	private final String name;
	private final Map<String, ParsedClass> classMap;
	private final int version;
	private final boolean current;
	public DetailsConfiguration details;
	public List<VersionTransformation> transformations;

	public ComputedVersion(ParsedVersion value, int version, boolean current, String versionName, Map<String, ParsedClass> classMap) {
		this.details = value.details;
		this.transformations = value.transformations;
		this.version = version;
		this.current = current;
		this.name = versionName;
		this.classMap = classMap;
	}

	public int getVersion() {
		return version;
	}

	public String getName() {
		return name;
	}

	public Map<String, ParsedClass> getClassMap() {
		return classMap;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		ComputedVersion that = (ComputedVersion) o;
		return Objects.equals(details, that.details)
				&& Objects.equals(transformations, that.transformations);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(details);
		hash += ConfigUtils.hashCode(transformations);
		return hash;
	}

	public String getPackage(String basePackageName) {
		if (current) {
			return joinPackage(basePackageName, "current");
		} else {
			return joinPackage(basePackageName, "v" + getVersionCompleteInt());
		}
	}

	public String getVersionVarName() {
		return "V" + version;
	}

	private String getVersionCompleteInt() {
		return Integer.toString(version);
	}

	private String joinPackage(String basePackageName, String packageName) {
		if (basePackageName.isBlank()) {
			basePackageName = "org.generated";
		}
		if (packageName.isBlank()) {
			return basePackageName;
		} else {
			return basePackageName + "." + packageName;
		}
	}

	public boolean isCurrent() {
		return current;
	}
}
