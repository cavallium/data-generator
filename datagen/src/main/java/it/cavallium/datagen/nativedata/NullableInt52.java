package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.NativeNullable;
import it.cavallium.datagen.TypedNullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class NullableInt52 implements Serializable, INullable, TypedNullable<Int52> {

	@Serial
	private static final long serialVersionUID = 1L;
	private static final NullableInt52 NULL = new NullableInt52(null);

	private final Int52 value;

	public NullableInt52(Int52 value) {
		this.value = value;
	}

	public static NullableInt52 of(@NotNull Int52 value) {
		return new NullableInt52(value);
	}

	public static NullableInt52 ofNullable(@Nullable Int52 value) {
		if (value == null) {
			return NULL;
		} else {
			return new NullableInt52(value);
		}
	}

	public static NullableInt52 ofNullableNumber(@Nullable Number value) {
		if (value == null) {
			return NULL;
		} else {
			return new NullableInt52(Int52.fromLong(value.longValue()));
		}
	}

	public static NullableInt52 empty() {
		return NULL;
	}

	@Override
	public boolean isEmpty() {
		return value == null;
	}

	@Override
	public boolean isPresent() {
		return value != null;
	}

	@Override
	@NotNull
	public Int52 get() {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	@Override
	public @NotNull NullableInt52 or(@NotNull NativeNullable<? extends Int52> fallback) {
		if (value == null) {
			if (fallback.getClass() == NullableInt52.class) {
				return (NullableInt52) fallback;
			} else {
				return ofNullable(fallback.getNullable());
			}
		} else {
			return this;
		}
	}

	@NotNull
	public NullableInt52 or(NullableInt52 fallback) {
		if (value == null) {
			return fallback;
		} else {
			return this;
		}
	}


	@Nullable
	public Int52 getNullable() {
		return value;
	}

	@Override
	@Nullable
	public Int52 getNullable(Int52 defaultValue) {
		return value == null ? defaultValue : value;
	}

	@NotNull
	@Override
	public NullableInt52 clone() {
		if (value != null) {
			return NullableInt52.of(value);
		} else {
			return NullableInt52.empty();
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
		var that = (NullableInt52) o;
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
