package it.cavallium.data.generator.nativedata;

import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Nullablefloat implements Serializable, IGenericNullable {

	private static final long serialVersionUID = 1L;

	private final Float value;

	public Nullablefloat(Float value) {
		this.value = value;
	}

	public static Nullablefloat of(float value) {
		return new Nullablefloat(value);
	}

	public static Nullablefloat ofNullable(@Nullable Float value) {
		return new Nullablefloat(value);
	}

	public static <T> Nullablefloat empty() {
		return new Nullablefloat(null);
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	public float get() {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	public float orElse(float defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Override
	public Object $getNullable() {
		return this.getNullable();
	}

	@Nullable
	public Float getNullable() {
		return value;
	}

	public float getNullable(float defaultValue) {
		return value == null ? defaultValue : value;
	}

	@NotNull
	@Override
	public Nullablefloat clone() {
		if (value != null) {
			return Nullablefloat.of(value);
		} else {
			return Nullablefloat.empty();
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		var that = (Nullablefloat) o;
		return Objects.equals(value, that.value);
	}

	@Override
	public int hashCode() {
		return Objects.hash(value);
	}

	@Override
	public String toString() {
		if (value == null) return "null";
		return "" + value;
	}
}
