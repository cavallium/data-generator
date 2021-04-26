package it.cavallium.data.generator.nativedata;

import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Nullablelong implements Serializable, IGenericNullable {

	private static final long serialVersionUID = 1L;

	private final Long value;

	public Nullablelong(Long value) {
		this.value = value;
	}

	public static Nullablelong of(long value) {
		return new Nullablelong(value);
	}

	public static Nullablelong ofNullable(@Nullable Long value) {
		return new Nullablelong(value);
	}

	public static <T> Nullablelong empty() {
		return new Nullablelong(null);
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	public long get() {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	public long orElse(long defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Override
	public Long $getNullable() {
		return this.getNullable();
	}

	@Nullable
	public Long getNullable() {
		return value;
	}

	public long getNullable(long defaultValue) {
		return value == null ? defaultValue : value;
	}

	@NotNull
	@Override
	public Nullablelong clone() {
		if (value != null) {
			return Nullablelong.of(value);
		} else {
			return Nullablelong.empty();
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
		var that = (Nullablelong) o;
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
