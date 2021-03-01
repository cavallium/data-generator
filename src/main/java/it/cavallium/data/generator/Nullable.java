package it.cavallium.data.generator;

public class Nullable<T> {

	private static final long serialVersionUID = 1L;

	private final T value;

	public Nullable(T value) {
		this.value = value;
	}

	public static <T> Nullable<T> of(T value) {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return new Nullable<>(value);
		}
	}

	public static <T> Nullable<T> ofNullable(T value) {
		return new Nullable<>(value);
	}

	public static <T> Nullable<T> empty() {
		return new Nullable<>(null);
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
	}

	@org.jetbrains.annotations.NotNull
	public T get() {
		if (value == null) {
			throw new NullPointerException();
		} else {
			return value;
		}
	}

	@org.jetbrains.annotations.Nullable
	public T getNullable() {
		return value;
	}
}
