package it.cavallium.data.generator.plugin;

import java.util.List;
import java.util.Objects;

public class ParsedVersion {

	public DetailsConfiguration details;
	public List<VersionTransformation> transformations;

	public ParsedVersion(VersionConfiguration versionConfiguration) {
		this.details = versionConfiguration.details;
		if (versionConfiguration.transformations != null) {
			this.transformations = versionConfiguration.transformations.stream().map(VersionTransformation::copy).toList();
		}
	}

	public ParsedVersion() {

	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		ParsedVersion that = (ParsedVersion) o;
		return Objects.equals(details, that.details) && Objects.equals(transformations, that.transformations);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(details);
		hash += ConfigUtils.hashCode(transformations);
		return hash;
	}
}
