package it.cavallium.data.generator.plugin;

import static it.cavallium.data.generator.plugin.DataModel.fixType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ParsedInterface {

	public Set<String> extendInterfaces = new HashSet<>();
	public Map<String, String> commonData = new HashMap<>();
	public Map<String, String> commonGetters = new HashMap<>();

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		ParsedInterface that = (ParsedInterface) o;
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

	public ParsedInterface(InterfaceDataConfiguration value) {
		if (value.extendInterfaces != null) this.extendInterfaces = value.extendInterfaces;
		if (value.commonData != null) {
			this.commonData = value.commonData;
			this.commonData.replaceAll((k, v) -> fixType(v));
		}
		if (value.commonGetters != null) {
			this.commonGetters = value.commonGetters;
			this.commonGetters.replaceAll((k, v) -> fixType(v));
		}
	}
}
