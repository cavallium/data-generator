package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.NativeNullable;
import it.cavallium.datagen.TypedNullable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class NullableBinaryString implements Serializable, INullable, TypedNullable<BinaryString> {

	@Serial
	private static final long serialVersionUID = 1L;
	private static final NullableBinaryString NULL = new NullableBinaryString(null);

	private final BinaryString value;

	public NullableBinaryString(BinaryString value) {
		this.value = value;
	}

	@SuppressWarnings("ConstantConditions")
	public static NullableBinaryString of(@NotNull BinaryString value) {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return new NullableBinaryString(value);
		}
	}

	public static NullableBinaryString ofNullable(@Nullable BinaryString value) {
		if (value == null) {
			return NULL;
		} else {
			return new NullableBinaryString(value);
		}
	}

	public static NullableBinaryString empty() {
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
		return value != null && !isBlank();
	}

	public boolean isBlank() {
		return value == null || value.sizeBytes() == 0;
	}

	@Override
	@NotNull
	public BinaryString get() {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	@Override
	public @NotNull NullableBinaryString or(@NotNull NativeNullable<? extends BinaryString> fallback) {
		if (value == null) {
			if (fallback.getClass() == NullableBinaryString.class) {
				return (NullableBinaryString) fallback;
			} else {
				return ofNullable(fallback.getNullable());
			}
		} else {
			return this;
		}
	}

	@NotNull
	public NullableBinaryString or(NullableBinaryString fallback) {
		if (value == null) {
			return fallback;
		} else {
			return this;
		}
	}

	public @NotNull NullableBinaryString orIfBlank(@NotNull NativeNullable<? extends BinaryString> fallback) {
		if (isBlank()) {
			if (fallback.getClass() == NullableBinaryString.class) {
				return (NullableBinaryString) fallback;
			} else {
				return ofNullable(fallback.getNullable());
			}
		} else {
			return this;
		}
	}

	@NotNull
	public NullableBinaryString orIfBlank(NullableBinaryString fallback) {
		if (isBlank()) {
			return fallback;
		} else {
			return this;
		}
	}

	@Nullable
	public BinaryString getNullable() {
		return value;
	}

	@Override
	@Nullable
	public BinaryString getNullable(BinaryString defaultValue) {
		return value == null ? defaultValue : value;
	}

	@NotNull
	@Override
	public NullableBinaryString clone() {
		if (value != null) {
			return NullableBinaryString.of(value);
		} else {
			return NullableBinaryString.empty();
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
		var that = (NullableBinaryString) o;
		return Objects.equals(value, that.value);
	}

	@Override
	public int hashCode() {
		return value == null ? 0 : value.hashCode();
	}

	@Override
	public String toString() {
		if (value == null) return "null";
		return new String(value.data(), StandardCharsets.UTF_8);
	}
}
