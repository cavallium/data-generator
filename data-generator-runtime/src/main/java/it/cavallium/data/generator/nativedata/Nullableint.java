package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.NativeNullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Nullableint implements Serializable, INullable, NativeNullable<Integer> {

	@Serial
	private static final long serialVersionUID = 1L;
	private static final Nullableint NULL = new Nullableint(null);

	private final Integer value;

	public Nullableint(Integer value) {
		this.value = value;
	}

	public static Nullableint of(int value) {
		return new Nullableint(value);
	}

	public static Nullableint ofNullable(@Nullable Integer value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullableint(value);
		}
	}

	public static Nullableint ofNullableNumber(@Nullable Number value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullableint(value.intValue());
		}
	}

	public static Nullableint empty() {
		return NULL;
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	@Override
	public @NotNull Integer orElse(@NotNull Integer defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Override
	public @NotNull Nullableint or(@NotNull NativeNullable<? extends Integer> fallback) {
		if (value == null) {
			if (fallback.getClass() == Nullableint.class) {
				return (Nullableint) fallback;
			} else {
				return ofNullable(fallback.getNullable());
			}
		} else {
			return this;
		}
	}

	@NotNull
	public Nullableint or(Nullableint fallback) {
		if (value == null) {
			return fallback;
		} else {
			return this;
		}
	}

	public int get() {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	public int orElse(int defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Nullable
	public Integer getNullable() {
		return value;
	}

	@Override
	public @Nullable Integer getNullable(@Nullable Integer defaultValue) {
		return value == null ? defaultValue : value;
	}

	public int getNullable(int defaultValue) {
		return value == null ? defaultValue : value;
	}

	@NotNull
	@Override
	public Nullableint clone() {
		if (value != null) {
			return Nullableint.of(value);
		} else {
			return Nullableint.empty();
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
		var that = (Nullableint) o;
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
