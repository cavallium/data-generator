package it.cavallium.data.generator;

import java.util.Objects;

public class RemoveDataConfiguration implements TransformationConfiguration {

	public String transformClass;
	public String from;

	@Override
	public String getTransformClass() {
		return transformClass;
	}

	@Override
	public String getTransformName() {
		return "remove-data";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		RemoveDataConfiguration that = (RemoveDataConfiguration) o;
		return Objects.equals(transformClass, that.transformClass) && Objects.equals(from, that.from);
	}

	@Override
	public int hashCode() {
		return Objects.hash(transformClass, from);
	}
}
