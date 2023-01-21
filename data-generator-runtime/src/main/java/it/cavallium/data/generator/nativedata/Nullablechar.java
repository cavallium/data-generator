package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.NativeNullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Nullablechar implements Serializable, INullable, NativeNullable<Character> {

	@Serial
	private static final long serialVersionUID = 1L;
	private static final Nullablechar NULL = new Nullablechar(null);

	private final Character value;

	public Nullablechar(Character value) {
		this.value = value;
	}

	public static Nullablechar of(char value) {
		return new Nullablechar(value);
	}

	public static Nullablechar ofNullable(@Nullable Character value) {
		if (value == null) {
			return NULL;
		} else {
			return new Nullablechar(value);
		}
	}

	public static Nullablechar empty() {
		return NULL;
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	@Override
	public @NotNull Character orElse(@NotNull Character defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Override
	public @NotNull Nullablechar or(@NotNull NativeNullable<? extends Character> fallback) {
		if (value == null) {
			if (fallback.getClass() == Nullablechar.class) {
				return (Nullablechar) fallback;
			} else {
				return ofNullable(fallback.getNullable());
			}
		} else {
			return this;
		}
	}

	@NotNull
	public Nullablechar or(Nullablechar fallback) {
		if (value == null) {
			return fallback;
		} else {
			return this;
		}
	}

	public char get() {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	public char orElse(char defaultValue) {
		if (value == null) {
			return defaultValue;
		} else {
			return value;
		}
	}

	@Nullable
	public Character getNullable() {
		return value;
	}

	@Override
	public @Nullable Character getNullable(@Nullable Character defaultValue) {
		return value != null ? value : defaultValue;
	}

	public char getNullable(char defaultValue) {
		return value == null ? defaultValue : value;
	}

	@NotNull
	@Override
	public Nullablechar clone() {
		if (value != null) {
			return Nullablechar.of(value);
		} else {
			return Nullablechar.empty();
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
		var that = (Nullablechar) o;
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
