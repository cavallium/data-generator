package it.cavallium.datagen;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
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

	default <U, TN> @NotNull TN map(
			@NotNull Function<@NotNull T, @NotNull U> value,
			@NotNull Function<@Nullable U, @NotNull TN> nullableConstructor) {
		var nullable = getNullable();
		if (nullable != null) {
			var val = value.apply(nullable);
			Objects.requireNonNull(val, "Mapped value must not be null");
			return nullableConstructor.apply(val);
		} else {
			return nullableConstructor.apply(null);
		}
	}

	default <U, TN extends TypedNullable<U>> @NotNull TN mapNullable(
			@NotNull Function<@NotNull T, @NotNull TN> value,
			@NotNull Supplier<@NotNull TN> emptyNullableConstructor) {
		var nullable = getNullable();
		if (nullable != null) {
			var val = value.apply(nullable);
			Objects.requireNonNull(val, "Mapped value must not be null");
			return val;
		} else {
			return emptyNullableConstructor.get();
		}
	}

	default <U> @NotNull Optional<U> map(@NotNull Function<@NotNull T, @NotNull U> value) {
		var nullable = getNullable();
		if (nullable != null) {
			var val = value.apply(nullable);
			return Optional.of(val);
		} else {
			return Optional.empty();
		}
	}

	@SuppressWarnings("unchecked")
	default <TN extends TypedNullable<T>> @NotNull TN filter(
			@NotNull Predicate<@NotNull T> value,
			@NotNull Supplier<TN> nullableConstructor) {
		var nullable = getNullable();
		if (nullable != null) {
			var filter = value.test(nullable);
			if (!filter) {
				return nullableConstructor.get();
			}
		}
		return (TN) this;
	}

	default @NotNull Optional<T> filter(@NotNull Predicate<@NotNull T> value) {
		var nullable = getNullable();
		if (nullable != null) {
			var filter = value.test(nullable);
			if (!filter) {
				return Optional.empty();
			}
		}
		return Optional.ofNullable(nullable);
	}

	default @NotNull Optional<T> toOptional() {
		var nullable = getNullable();
		return Optional.ofNullable(nullable);
	}

	default @NotNull Stream<T> stream() {
		var nullable = getNullable();
		return Stream.ofNullable(nullable);
	}
}
