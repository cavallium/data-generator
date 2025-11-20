package it.cavallium.datagen.plugin;

import static it.cavallium.datagen.plugin.DataModel.joinPackage;

import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public class ComputedVersion implements Comparable<ComputedVersion> {

	private final String name;
	private final int version;
	private final boolean current;
	public final DetailsConfiguration details;
	public final List<VersionTransformation> transformations;

	public ComputedVersion(ParsedVersion value, int version, boolean current, String versionName) {
		this.details = value.details;
		this.transformations = value.transformations;
		this.name = versionName;
		this.version = version;
		this.current = current;
	}

	public int getVersion() {
		return version;
	}

	public String getName() {
		return name;
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
		return Objects.equals(version, that.version);
	}

	@Override
	public int hashCode() {
		return ConfigUtils.hashCode(version);
	}

	public String getPackage(String basePackageName) {
		if (current) {
			return joinPackage(basePackageName, "current");
		} else {
			return joinPackage(basePackageName, "v" + version);
		}
	}

	public String getDataPackage(String basePackageName) {
		return joinPackage(getPackage(basePackageName), "data");
	}

	public String getUpgradersPackage(String basePackageName) {
		return joinPackage(getPackage(basePackageName), "upgraders");
	}

	public String getSerializersPackage(String basePackageName) {
		return joinPackage(getPackage(basePackageName), "serializers");
	}

	public String getDataNullablesPackage(String basePackageName) {
		return joinPackage(getDataPackage(basePackageName), "nullables");
	}

	public String getVersionVarName() {
		return "V" + version;
	}

	public String getVersionShortInt() {
		return Integer.toString(version);
	}

	public boolean isCurrent() {
		return current;
	}

	@Override
	public int compareTo(@NotNull ComputedVersion o) {
		return Integer.compare(version, o.version);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(version);
		sb.append(" (");
		sb.append(name);
		if (current) {
			sb.append(", current");
		}
		sb.append(")");
		return sb.toString();
	}
}
