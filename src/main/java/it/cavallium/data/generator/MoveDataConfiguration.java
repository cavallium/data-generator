package it.cavallium.data.generator;

import java.util.Objects;

public class MoveDataConfiguration implements TransformationConfiguration {

	public String transformClass;
	public String from;
	public String to;

	@Override
	public String getTransformClass() {
		return transformClass;
	}

	@Override
	public String getTransformName() {
		return "move-data";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		MoveDataConfiguration that = (MoveDataConfiguration) o;
		return Objects.equals(transformClass, that.transformClass) && Objects.equals(from, that.from) && Objects.equals(to,
				that.to
		);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(transformClass);
		hash += ConfigUtils.hashCode(from);
		hash += ConfigUtils.hashCode(to);
		return hash;
	}
}
