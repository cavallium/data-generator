package it.cavallium.datagen.plugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public class UpgradeDataConfiguration implements TransformationConfiguration {

	public String transformClass;
	public String from;
	public String type;
	public String upgrader;
	public String upgraderInstance;
	@Nullable
	public List<String> contextParameters;

	@Override
	public String getTransformClass() {
		return transformClass;
	}

	@Override
	public String getTransformName() {
		return "upgrade-data";
	}

	public JInterfaceLocation getUpgraderLocation() {
		return JInterfaceLocation.parse(upgrader, upgraderInstance);
	}

	@NotNull
	public List<String> getContextParameters() {
		return Objects.requireNonNullElse(contextParameters, List.of());
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
				&& Objects.equals(type, that.type) && Objects.equals(upgrader, that.upgrader)
				&& Objects.equals(upgraderInstance, that.upgraderInstance)
				&& Objects.equals(contextParameters, that.contextParameters);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(transformClass);
		hash += ConfigUtils.hashCode(from);
		hash += ConfigUtils.hashCode(type);
		hash += ConfigUtils.hashCode(upgrader);
		hash += ConfigUtils.hashCode(upgraderInstance);
		hash += ConfigUtils.hashCode(contextParameters);
		return hash;
	}

	public UpgradeDataConfiguration copy() {
		var c = new UpgradeDataConfiguration();
		if (this.transformClass != null) c.transformClass = this.transformClass;
		if (this.from != null) c.from = this.from;
		if (this.type != null) c.type = this.type;
		if (this.upgrader != null) c.upgrader = this.upgrader;
		if (this.upgraderInstance != null) c.upgraderInstance = this.upgraderInstance;
		if (this.contextParameters != null) c.contextParameters = this.contextParameters;
		return c;
	}
}
