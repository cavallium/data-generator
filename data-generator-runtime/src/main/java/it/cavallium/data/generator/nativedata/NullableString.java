package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.NativeNullable;
import it.cavallium.data.generator.TypedNullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class NullableString implements Serializable, INullable, TypedNullable<String> {

	@Serial
	private static final long serialVersionUID = 1L;
	private static final NullableString NULL = new NullableString(null);

	private final String value;

	public NullableString(String value) {
		this.value = value;
	}

	@SuppressWarnings("ConstantConditions")
	public static NullableString of(@NotNull String value) {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return new NullableString(value);
		}
	}

	public static NullableString ofNullable(@Nullable String value) {
		if (value == null) {
			return NULL;
		} else {
			return new NullableString(value);
		}
	}

	public static NullableString ofNullableBlank(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return NULL;
		}
		return new NullableString(value);
	}

	public static NullableString empty() {
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

	public boolean isContentful() {
		return value != null && !value.isBlank();
	}

	public boolean isBlank() {
		return value == null || value.isBlank();
	}

	@Override
	@NotNull
	public String get() {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	@Override
	public @NotNull NullableString or(@NotNull NativeNullable<? extends String> fallback) {
		if (value == null) {
			if (fallback.getClass() == NullableString.class) {
				return (NullableString) fallback;
			} else {
				return ofNullable(fallback.getNullable());
			}
		} else {
			return this;
		}
	}

	@NotNull
	public NullableString or(NullableString fallback) {
		if (value == null) {
			return fallback;
		} else {
			return this;
		}
	}

	public @NotNull NullableString orIfBlank(@NotNull NativeNullable<? extends String> fallback) {
		if (isBlank()) {
			if (fallback.getClass() == NullableString.class) {
				return (NullableString) fallback;
			} else {
				return ofNullable(fallback.getNullable());
			}
		} else {
			return this;
		}
	}

	@NotNull
	public NullableString orIfBlank(NullableString fallback) {
		if (isBlank()) {
			return fallback;
		} else {
			return this;
		}
	}

	@Nullable
	public String getNullable() {
		return value;
	}

	@Override
	@Nullable
	public String getNullable(String defaultValue) {
		return value == null ? defaultValue : value;
	}

	@NotNull
	@Override
	public NullableString clone() {
		if (value != null) {
			return NullableString.of(value);
		} else {
			return NullableString.empty();
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
		var that = (NullableString) o;
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
