package it.cavallium.data.generator.nativedata;

import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Nullablebyte implements Serializable, IGenericNullable {

	private static final long serialVersionUID = 1L;

	private final Byte value;

	public Nullablebyte(Byte value) {
		this.value = value;
	}

	public static Nullablebyte of(byte value) {
		return new Nullablebyte(value);
	}

	public static Nullablebyte ofNullable(@Nullable Byte value) {
		return new Nullablebyte(value);
	}

	public static <T> Nullablebyte empty() {
		return new Nullablebyte(null);
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	public byte get() {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	public byte orElse(byte defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Override
	public Byte $getNullable() {
		return this.getNullable();
	}

	@Nullable
	public Byte getNullable() {
		return value;
	}

	public byte getNullable(byte defaultValue) {
		return value == null ? defaultValue : value;
	}

	@NotNull
	@Override
	public Nullablebyte clone() {
		if (value != null) {
			return Nullablebyte.of(value);
		} else {
			return Nullablebyte.empty();
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
		Nullablebyte that = (Nullablebyte) o;
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
