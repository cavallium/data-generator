package it.cavallium.data.generator.nativedata;

import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class NullableInt52 implements Serializable, IGenericNullable {

	private static final long serialVersionUID = 1L;

	private final Int52 value;

	public NullableInt52(Int52 value) {
		this.value = value;
	}

	public static NullableInt52 of(@NotNull Int52 value) {
		return new NullableInt52(value);
	}

	public static NullableInt52 ofNullable(@Nullable Int52 value) {
		return new NullableInt52(value);
	}

	public static <T> NullableInt52 empty() {
		return new NullableInt52(null);
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	@NotNull
	public Int52 get() {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	public Int52 orElse(Int52 defaultValue) {
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
	public Int52 getNullable() {
		return value;
	}

	@Nullable
	public Int52 getNullable(Int52 defaultValue) {
		return value == null ? defaultValue : value;
	}

	@NotNull
	@Override
	public NullableInt52 clone() {
		if (value != null) {
			return NullableInt52.of(value);
		} else {
			return NullableInt52.empty();
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
		var that = (NullableInt52) o;
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
