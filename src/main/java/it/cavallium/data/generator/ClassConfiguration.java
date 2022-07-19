package it.cavallium.data.generator;

import java.util.LinkedHashMap;
import java.util.Objects;

public class ClassConfiguration {

	public String stringRepresenter;

	public LinkedHashMap<String, String> data;

	public String getStringRepresenter() {
		return stringRepresenter;
	}

	public LinkedHashMap<String, String> getData() {
		return data;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		ClassConfiguration that = (ClassConfiguration) o;
		return Objects.equals(stringRepresenter, that.stringRepresenter) && Objects.equals(data, that.data);
	}

	@Override
	public int hashCode() {
		return Objects.hash(stringRepresenter, data);
	}
}
