package it.cavallium.data.generator;

import java.util.Objects;

public class UpgradeDataConfiguration implements TransformationConfiguration {

	public String transformClass;
	public String from;
	public String upgrader;

	@Override
	public String getTransformClass() {
		return transformClass;
	}

	@Override
	public String getTransformName() {
		return "upgrade-data";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		UpgradeDataConfiguration that = (UpgradeDataConfiguration) o;
		return Objects.equals(transformClass, that.transformClass) && Objects.equals(from, that.from) && Objects.equals(
				upgrader,
				that.upgrader
		);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(transformClass);
		hash += ConfigUtils.hashCode(from);
		hash += ConfigUtils.hashCode(upgrader);
		return hash;
	}
}
