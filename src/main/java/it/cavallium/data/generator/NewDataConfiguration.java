package it.cavallium.data.generator;

import java.util.Objects;

public class NewDataConfiguration implements TransformationConfiguration {

	public String transformClass;
	public String to;
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
		return Objects.equals(transformClass, that.transformClass) && Objects.equals(to, that.to) && Objects.equals(
				initializer,
				that.initializer
		);
	}

	@Override
	public int hashCode() {
		return Objects.hash(transformClass, to, initializer);
	}
}
