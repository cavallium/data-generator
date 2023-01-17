package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.NativeNullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Nullableshort implements Serializable, IGenericNullable, NativeNullable<Short> {

	@Serial
	private static final long serialVersionUID = 1L;
	private static final Nullableshort NULL = new Nullableshort(null);

	private final Short value;

	public Nullableshort(Short value) {
		this.value = value;
	}

	public static Nullableshort of(short value) {
		return new Nullableshort(value);
	}

	public static Nullableshort ofNullable(@Nullable Short value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullableshort(value);
		}
	}

	public static Nullableshort ofNullableNumber(@Nullable Number value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullableshort(value.shortValue());
		}
	}

	public static Nullableshort empty() {
		return NULL;
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	@Override
	public @NotNull Short orElse(@NotNull Short defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Override
	public @NotNull Nullableshort or(@NotNull NativeNullable<? extends Short> fallback) {
		if (value == null) {
			if (fallback.getClass() == Nullableshort.class) {
				return (Nullableshort) fallback;
			} else {
				return ofNullable(fallback.getNullable());
			}
		} else {
			return this;
		}
	}

	@NotNull
	public Nullableshort or(Nullableshort fallback) {
		if (value == null) {
			return fallback;
		} else {
			return this;
		}
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
	public Short $getNullable() {
		return this.getNullable();
	}

	@Nullable
	public Short getNullable() {
		return value;
	}

	@Override
	public @Nullable Short getNullable(@Nullable Short defaultValue) {
		return value == null ? defaultValue : value;
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
		return value == null ? 0 : value.hashCode();
	}

	@Override
	public String toString() {
		if (value == null) return "null";
		return "" + value;
	}
}
