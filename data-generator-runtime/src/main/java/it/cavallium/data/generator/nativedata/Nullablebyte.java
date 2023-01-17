package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.NativeNullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Nullablebyte implements Serializable, IGenericNullable, NativeNullable<Byte> {

	@Serial
	private static final long serialVersionUID = 1L;
	private static final Nullablebyte NULL = new Nullablebyte(null);

	private final Byte value;

	public Nullablebyte(Byte value) {
		this.value = value;
	}

	public static Nullablebyte of(byte value) {
		return new Nullablebyte(value);
	}

	public static Nullablebyte ofNullable(@Nullable Byte value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullablebyte(value);
		}
	}

	public static Nullablebyte ofNullableNumber(@Nullable Number value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullablebyte(value.byteValue());
		}
	}

	public static Nullablebyte empty() {
		return NULL;
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	@Override
	public @NotNull Byte orElse(@NotNull Byte defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Override
	public @NotNull Nullablebyte or(@NotNull NativeNullable<? extends Byte> fallback) {
		if (value == null) {
			if (fallback.getClass() == Nullablebyte.class) {
				return (Nullablebyte) fallback;
			} else {
				return ofNullable(fallback.getNullable());
			}
		} else {
			return this;
		}
	}

	@NotNull
	public Nullablebyte or(Nullablebyte fallback) {
		if (value == null) {
			return fallback;
		} else {
			return this;
		}
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

	@Override
	public @Nullable Byte getNullable(@Nullable Byte defaultValue) {
		return value != null ? value : defaultValue;
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
		return value == null ? 0 : value.hashCode();
	}

	@Override
	public String toString() {
		if (value == null) return "null";
		return "" + value;
	}
}
