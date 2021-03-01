package it.cavallium.data.generator.nativedata;

import java.io.Serializable;
import java.util.Objects;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@EqualsAndHashCode
@ToString
public class Nullablechar implements Serializable, IGenericNullable {

	private static final long serialVersionUID = 1L;

	private final Character value;

	public Nullablechar(Character value) {
		this.value = value;
	}

	public static Nullablechar of(char value) {
		return new Nullablechar(value);
	}

	public static Nullablechar ofNullable(@Nullable Character value) {
		return new Nullablechar(value);
	}

	public static <T> Nullablechar empty() {
		return new Nullablechar(null);
	}

	public boolean isEmpty() {
		return value == null;
	}

	public boolean isPresent() {
		return value != null;
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

	@Override
	public Object $getNullable() {
		return this.getNullable();
	}

	@Nullable
	public Character getNullable() {
		return value;
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
		return Objects.hash(value);
	}

	@Override
	public String toString() {
		if (value == null) return "null";
		return "" + value;
	}
}
