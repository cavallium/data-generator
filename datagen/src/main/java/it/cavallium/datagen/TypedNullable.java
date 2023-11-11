package it.cavallium.datagen;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface TypedNullable<T> extends NativeNullable<T> {

	@NotNull
	default T get() throws NullPointerException {
		var value = getNullable();
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	@Override
	default @Nullable T getNullable(@Nullable T defaultValue) {
		var value = getNullable();
		return value == null ? defaultValue : value;
	}

	@Override
	default @NotNull T orElse(@NotNull T defaultValue) {
		var value = getNullable();
		if (value == null) {
			Objects.requireNonNull(defaultValue, "default value must not be null");
			return defaultValue;
		} else {
			return value;
		}
	}

	@Override
	default @NotNull NativeNullable<? extends T> or(@NotNull NativeNullable<? extends T> fallback) {
		var value = getNullable();
		if (value == null) {
			return fallback;
		} else {
			return this;
		}
	}

	@Override
	default boolean isPresent() {
		return getNullable() != null;
	}

	@Override
	default boolean isEmpty() {
		return getNullable() == null;
	}
}
