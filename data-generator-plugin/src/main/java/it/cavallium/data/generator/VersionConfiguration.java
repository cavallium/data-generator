package it.cavallium.data.generator;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class VersionConfiguration {

	public String previousVersion;
	public DetailsConfiguration details;
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
		return Objects.equals(previousVersion, that.previousVersion) && Objects.equals(details, that.details)
				&& Objects.equals(transformations, that.transformations);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(previousVersion);
		hash += ConfigUtils.hashCode(details);
		hash += ConfigUtils.hashCode(transformations);
		return hash;
	}
}
