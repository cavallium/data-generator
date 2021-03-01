package it.cavallium.data.generator.nativedata;

import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Nullableboolean implements Serializable, IGenericNullable {

	private static final long serialVersionUID = 1L;

	private final Boolean value;

	public Nullableboolean(Boolean value) {
		this.value = value;
	}

	public static Nullableboolean of(boolean value) {
		return new Nullableboolean(value);
	}

	public static Nullableboolean ofNullable(@Nullable Boolean value) {
		return new Nullableboolean(value);
	}

	public static <T> Nullableboolean empty() {
		return new Nullableboolean(null);
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	public boolean get() {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	public boolean orElse(boolean defaultValue) {
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
	public Boolean getNullable() {
		return value;
	}

	public boolean getNullable(boolean defaultValue) {
		return value == null ? defaultValue : value;
	}

	@NotNull
	@Override
	public Nullableboolean clone() {
		if (value != null) {
			return Nullableboolean.of(value);
		} else {
			return Nullableboolean.empty();
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
		Nullableboolean that = (Nullableboolean) o;
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
