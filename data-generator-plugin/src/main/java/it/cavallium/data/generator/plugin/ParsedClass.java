package it.cavallium.data.generator.plugin;

import static it.cavallium.data.generator.plugin.DataModel.fixType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ParsedClass {

	public String stringRepresenter;

	public LinkedHashMap<String, String> data;
	public List<TransformationConfiguration> differentThanPrev;
	public boolean differentThanNext;

	public ParsedClass(ClassConfiguration baseTypesData) {
		this.stringRepresenter = baseTypesData.stringRepresenter;
		if (baseTypesData.data != null) {
			this.data = baseTypesData.data.entrySet().stream()
					.map(e -> Map.entry(e.getKey(), fixType(e.getValue())))
					.collect(Collectors.toMap(Entry::getKey, Entry::getValue, (a, b) -> {
						throw new IllegalStateException();
					}, LinkedHashMap::new));
		}
	}

	public ParsedClass() {

	}

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
		ParsedClass that = (ParsedClass) o;
		return Objects.equals(stringRepresenter, that.stringRepresenter) && Objects.equals(data, that.data);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(stringRepresenter);
		hash += ConfigUtils.hashCode(data);
		return hash;
	}

	public ParsedClass copy() {
		var cc = new ParsedClass();
		if (this.stringRepresenter != null) cc.stringRepresenter = this.stringRepresenter;
		cc.data = new LinkedHashMap<>(data);
		cc.differentThanNext = differentThanNext;
		cc.differentThanPrev = differentThanPrev;
		return cc;
	}

	public void addDifferentThanPrev(TransformationConfiguration transformation) {
		if (differentThanPrev == null) {
			differentThanPrev = new ArrayList<>();
		}
		differentThanPrev.add(transformation);
	}
}
