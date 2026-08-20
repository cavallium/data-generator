package it.cavallium.datagen.plugin;

import java.util.Objects;

/** Appends one alternative to a wire-discriminated union in the containing schema version. */
public final class NewSubtypeConfiguration implements TransformationConfiguration {

	public String superType;
	public String subtype;

	@Override
	public String getTransformClass() {
		return superType;
	}

	@Override
	public String getTransformName() {
		return "new-subtype";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		NewSubtypeConfiguration that = (NewSubtypeConfiguration) o;
		return Objects.equals(superType, that.superType) && Objects.equals(subtype, that.subtype);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(superType);
		hash += ConfigUtils.hashCode(subtype);
		return hash;
	}

	public NewSubtypeConfiguration copy() {
		var copy = new NewSubtypeConfiguration();
		copy.superType = superType;
		copy.subtype = subtype;
		return copy;
	}
}
