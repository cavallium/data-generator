package it.cavallium.data.generator.nativedata;

import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Nullableshort implements Serializable, IGenericNullable {

	private static final long serialVersionUID = 1L;

	private final Short value;

	public Nullableshort(Short value) {
		this.value = value;
	}

	public static Nullableshort of(short value) {
		return new Nullableshort(value);
	}

	public static Nullableshort ofNullable(@Nullable Short value) {
		return new Nullableshort(value);
	}

	public static <T> Nullableshort empty() {
		return new Nullableshort(null);
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	public short get() {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	public short orElse(short defaultValue) {
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
	public Short getNullable() {
		return value;
	}

	public short getNullable(short defaultValue) {
		return value == null ? defaultValue : value;
	}

	@NotNull
	@Override
	public Nullableshort clone() {
		if (value != null) {
			return Nullableshort.of(value);
		} else {
			return Nullableshort.empty();
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
		var that = (Nullableshort) o;
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
