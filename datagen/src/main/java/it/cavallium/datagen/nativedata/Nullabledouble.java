package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.NativeNullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Nullabledouble implements Serializable, INullable, NativeNullable<Double> {

	@Serial
	private static final long serialVersionUID = 1L;
	private static final Nullabledouble NULL = new Nullabledouble(null);

	private final Double value;

	public Nullabledouble(Double value) {
		this.value = value;
	}

	public static Nullabledouble of(double value) {
		return new Nullabledouble(value);
	}

	public static Nullabledouble ofNullable(@Nullable Double value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullabledouble(value);
		}
	}

	public static Nullabledouble ofNullableNumber(@Nullable Number value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullabledouble(value.doubleValue());
		}
	}

	public static Nullabledouble empty() {
		return NULL;
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	@Override
	public @NotNull Double orElse(@NotNull Double defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Override
	public @NotNull Nullabledouble or(@NotNull NativeNullable<? extends Double> fallback) {
		if (value == null) {
			if (fallback.getClass() == Nullabledouble.class) {
				return (Nullabledouble) fallback;
			} else {
				return ofNullable(fallback.getNullable());
			}
		} else {
			return this;
		}
	}

	@NotNull
	public Nullabledouble or(Nullabledouble fallback) {
		if (value == null) {
			return fallback;
		} else {
			return this;
		}
	}

	public double get() {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	public double orElse(double defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Nullable
	public Double getNullable() {
		return value;
	}

	@Override
	public @Nullable Double getNullable(@Nullable Double defaultValue) {
		return value == null ? defaultValue : value;
	}

	public double getNullable(double defaultValue) {
		return value == null ? defaultValue : value;
	}

	@NotNull
	@Override
	public Nullabledouble clone() {
		if (value != null) {
			return Nullabledouble.of(value);
		} else {
			return Nullabledouble.empty();
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
		var that = (Nullabledouble) o;
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
