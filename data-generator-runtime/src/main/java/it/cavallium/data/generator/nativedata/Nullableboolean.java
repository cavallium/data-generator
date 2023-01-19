package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.NativeNullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Nullableboolean implements Serializable, INullable, NativeNullable<Boolean> {

	@Serial
	private static final long serialVersionUID = 1L;
	private static final Nullableboolean NULL = new Nullableboolean(null);

	private final Boolean value;

	public Nullableboolean(Boolean value) {
		this.value = value;
	}

	public static Nullableboolean of(boolean value) {
		return new Nullableboolean(value);
	}

	public static Nullableboolean ofNullable(@Nullable Boolean value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullableboolean(value);
		}
	}

	public static Nullableboolean empty() {
		return NULL;
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	@Override
	public @NotNull Boolean orElse(@NotNull Boolean defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Override
	public @NotNull Nullableboolean or(@NotNull NativeNullable<? extends Boolean> fallback) {
		if (value == null) {
			if (fallback.getClass() == Nullableboolean.class) {
				return (Nullableboolean) fallback;
			} else {
				return ofNullable(fallback.getNullable());
			}
		} else {
			return this;
		}
	}

	@NotNull
	public Nullableboolean or(Nullableboolean fallback) {
		if (value == null) {
			return fallback;
		} else {
			return this;
		}
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
	public Boolean $getNullable() {
		return this.getNullable();
	}

	@Nullable
	public Boolean getNullable() {
		return value;
	}

	@Override
	public @Nullable Boolean getNullable(@Nullable Boolean defaultValue) {
		return value != null ? value : defaultValue;
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
		return value == null ? 0 : value.hashCode();
	}

	@Override
	public String toString() {
		if (value == null) return "null";
		return "" + value;
	}

}
