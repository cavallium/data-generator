package it.cavallium.datagen.plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class InterfaceDataConfiguration {

	public final Set<String> extendInterfaces = new HashSet<>();
	public final Map<String, String> commonData = new HashMap<>();
	public final Map<String, String> commonGetters = new HashMap<>();

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		InterfaceDataConfiguration that = (InterfaceDataConfiguration) o;
		return Objects.equals(extendInterfaces, that.extendInterfaces) && Objects.equals(commonData, that.commonData)
				&& Objects.equals(commonGetters, that.commonGetters);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(extendInterfaces);
		hash += ConfigUtils.hashCode(commonData);
		hash += ConfigUtils.hashCode(commonGetters);
		return hash;
	}
}
