package it.cavallium.datagen.plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Introduces a record type in the containing schema version. */
public final class NewTypeConfiguration implements TransformationConfiguration {

	public String name;
	public String stringRepresenter;
	public LinkedHashMap<String, String> data;

	@Override
	public String getTransformClass() {
		return name;
	}

	@Override
	public String getTransformName() {
		return "new-type";
	}

	public ClassConfiguration asClassConfiguration() {
		var configuration = new ClassConfiguration();
		configuration.stringRepresenter = stringRepresenter;
		configuration.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
		return configuration;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		NewTypeConfiguration that = (NewTypeConfiguration) o;
		return Objects.equals(name, that.name)
				&& Objects.equals(stringRepresenter, that.stringRepresenter)
				&& entriesEqual(data, that.data);
	}

	private static <K, V> boolean entriesEqual(LinkedHashMap<K, V> left, LinkedHashMap<K, V> right) {
		if (left == right) return true;
		if (left == null || right == null) return false;
		return List.copyOf(left.entrySet()).equals(List.copyOf(right.entrySet()));
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(name);
		hash += ConfigUtils.hashCode(stringRepresenter);
		hash += data == null ? 0 : List.copyOf(data.entrySet()).hashCode();
		return hash;
	}

	public NewTypeConfiguration copy() {
		var copy = new NewTypeConfiguration();
		copy.name = name;
		copy.stringRepresenter = stringRepresenter;
		copy.data = data == null ? null : new LinkedHashMap<>(data);
		return copy;
	}
}
