package it.cavallium.data.generator.plugin;

import java.util.Objects;

public class DetailsConfiguration {

	public String changelog;

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		DetailsConfiguration that = (DetailsConfiguration) o;
		return Objects.equals(changelog, that.changelog);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(changelog);
		return hash;
	}
}
