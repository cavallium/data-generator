package it.cavallium.data.generator.plugin;

import java.util.Objects;

public class UpgradeDataConfiguration implements TransformationConfiguration {

	public String transformClass;
	public String from;
	public String type;
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
		return Objects.equals(transformClass, that.transformClass) && Objects.equals(from, that.from)
				&& Objects.equals(type, that.type) && Objects.equals(upgrader, that.upgrader);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(transformClass);
		hash += ConfigUtils.hashCode(from);
		hash += ConfigUtils.hashCode(type);
		hash += ConfigUtils.hashCode(upgrader);
		return hash;
	}

	@SuppressWarnings("MethodDoesntCallSuperMethod")
	@Override
	public UpgradeDataConfiguration clone() {
		var c = new UpgradeDataConfiguration();
		if (this.transformClass != null) c.transformClass = this.transformClass;
		if (this.from != null) c.from = this.from;
		if (this.type != null) c.type = this.type;
		if (this.upgrader != null) c.upgrader = this.upgrader;
		return c;
	}
}
