package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.NativeNullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Nullablelong implements Serializable, INullable, NativeNullable<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	private static final Nullablelong NULL = new Nullablelong(null);

	private final Long value;

	public Nullablelong(Long value) {
		this.value = value;
	}

	public static Nullablelong of(long value) {
		return new Nullablelong(value);
	}

	public static Nullablelong ofNullable(@Nullable Long value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullablelong(value);
		}
	}

	public static Nullablelong ofNullableNumber(@Nullable Number value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullablelong(value.longValue());
		}
	}

	public static Nullablelong empty() {
		return NULL;
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	@Override
	public @NotNull Long orElse(@NotNull Long defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Override
	public @NotNull Nullablelong or(@NotNull NativeNullable<? extends Long> fallback) {
		if (value == null) {
			if (fallback.getClass() == Nullablelong.class) {
				return (Nullablelong) fallback;
			} else {
				return ofNullable(fallback.getNullable());
			}
		} else {
			return this;
		}
	}

	@NotNull
	public Nullablelong or(Nullablelong fallback) {
		if (value == null) {
			return fallback;
		} else {
			return this;
		}
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

	@Override
	public @Nullable Long getNullable(@Nullable Long defaultValue) {
		return value == null ? defaultValue : value;
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
		return value == null ? 0 : value.hashCode();
	}

	@Override
	public String toString() {
		if (value == null) return "null";
		return "" + value;
	}
}
