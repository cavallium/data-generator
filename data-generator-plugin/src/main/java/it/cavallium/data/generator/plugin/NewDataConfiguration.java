package it.cavallium.data.generator.plugin;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public class NewDataConfiguration implements TransformationConfiguration {

	public String transformClass;
	public String to;
	public String type;
	public String initializer;
	@Nullable
	public Integer index;

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
				&& Objects.equals(type, that.type) && Objects.equals(initializer, that.initializer)
				&& Objects.equals(index, that.index);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(transformClass);
		hash += ConfigUtils.hashCode(to);
		hash += ConfigUtils.hashCode(type);
		hash += ConfigUtils.hashCode(initializer);
		hash += ConfigUtils.hashCode(index);
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
		if (this.index != null) c.index = this.index;
		return c;
	}
}
