package it.cavallium.data.generator;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface NativeNullable<T> {

	boolean isEmpty();

	default boolean isPresent() {
		return !isEmpty();
	}

	@NotNull
	T orElse(@NotNull T defaultValue);

	@NotNull NativeNullable<? extends T> or(@NotNull NativeNullable<? extends T> fallback);

	@Nullable
	T getNullable();

	@Nullable
	T getNullable(@Nullable T defaultValue);
}
