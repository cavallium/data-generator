package it.cavallium.data.generator;

import java.util.Objects;

public class NewDataConfiguration implements TransformationConfiguration {

	public String transformClass;
	public String to;
	public String type;
	public String initializer;

	@Override
	public String getTransformClass() {
		return transformClass;
	}

	@Override
	public String getTransformName() {
		return "new-data";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		NewDataConfiguration that = (NewDataConfiguration) o;
		return Objects.equals(transformClass, that.transformClass) && Objects.equals(to, that.to)
				&& Objects.equals(type, that.type) && Objects.equals(initializer, that.initializer);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(transformClass);
		hash += ConfigUtils.hashCode(to);
		hash += ConfigUtils.hashCode(type);
		hash += ConfigUtils.hashCode(initializer);
		return hash;
	}

	@SuppressWarnings("MethodDoesntCallSuperMethod")
	@Override
	public NewDataConfiguration clone() {
		var c = new NewDataConfiguration();
		if (this.transformClass != null) c.transformClass = this.transformClass;
		if (this.initializer != null) c.initializer = this.initializer;
		if (this.to != null) c.to = this.to;
		if (this.type != null) c.type = this.type;
		return c;
	}
}
