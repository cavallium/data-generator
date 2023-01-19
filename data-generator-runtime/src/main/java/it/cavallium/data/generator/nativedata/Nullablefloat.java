package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.NativeNullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Nullablefloat implements Serializable, INullable, NativeNullable<Float> {

	@Serial
	private static final long serialVersionUID = 1L;
	private static final Nullablefloat NULL = new Nullablefloat(null);

	private final Float value;

	public Nullablefloat(Float value) {
		this.value = value;
	}

	public static Nullablefloat of(float value) {
		return new Nullablefloat(value);
	}

	public static Nullablefloat ofNullable(@Nullable Float value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullablefloat(value);
		}
	}

	public static Nullablefloat ofNullableNumber(@Nullable Number value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullablefloat(value.floatValue());
		}
	}

	public static Nullablefloat empty() {
		return NULL;
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	@Override
	public @NotNull Float orElse(@NotNull Float defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Override
	public @NotNull Nullablefloat or(@NotNull NativeNullable<? extends Float> fallback) {
		if (value == null) {
			if (fallback.getClass() == Nullablefloat.class) {
				return (Nullablefloat) fallback;
			} else {
				return ofNullable(fallback.getNullable());
			}
		} else {
			return this;
		}
	}

	@NotNull
	public Nullablefloat or(Nullablefloat fallback) {
		if (value == null) {
			return fallback;
		} else {
			return this;
		}
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

	@Override
	public @Nullable Float getNullable(@Nullable Float defaultValue) {
		return value == null ? defaultValue : value;
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
		return value == null ? 0 : value.hashCode();
	}

	@Override
	public String toString() {
		if (value == null) return "null";
		return "" + value;
	}
}
