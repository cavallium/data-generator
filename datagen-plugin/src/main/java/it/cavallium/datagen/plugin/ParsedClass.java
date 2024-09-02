package it.cavallium.datagen.plugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static it.cavallium.datagen.plugin.DataModel.fixType;
import static it.cavallium.datagen.plugin.DataModel.joinPackage;
import static java.util.Objects.requireNonNull;

import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ParsedClass {

	public record InputFieldInfo(@NotNull String typeName, @NotNull List<String> contextParams) {}

	public record FieldInfo(@NotNull String typeName, @NotNull LinkedHashMap<String, String> contextFieldsData) {}

	public String stringRepresenter;

	public LinkedHashMap<String, FieldInfo> data;
	public List<TransformationConfiguration> differentThanPrev;
	public boolean differentThanNext;

	public ParsedClass(ClassConfiguration baseTypesData) {
		this.stringRepresenter = baseTypesData.stringRepresenter;
		if (baseTypesData.data != null) {
			this.data = baseTypesData.data.entrySet().stream()
					.map(e -> Map.entry(e.getKey(), fixType(e.getValue())))
					.collect(Collectors.toMap(Entry::getKey,
							v -> new FieldInfo(v.getValue(),
							new LinkedHashMap<>(0)),
							(a, b) -> {throw new IllegalStateException();},
							LinkedHashMap::new));
		}
	}

	public ParsedClass() {

	}

	public String getStringRepresenter() {
		return stringRepresenter;
	}

	public LinkedHashMap<String, FieldInfo> getData() {
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

	public static final class NoContextParameterException extends RuntimeException {
		private final String contextParameter;

		public NoContextParameterException(String contextParameter) {
			this.contextParameter = contextParameter;
		}

		public String getContextParameter() {
			return contextParameter;
		}
	}

	private static LinkedHashMap<String, String> computeContextParametersTypes(
			LinkedHashMap<String, ParsedClass.FieldInfo> data, @NotNull List<String> contextParameters)
			throws NoContextParameterException {
		return contextParameters.stream().collect(Collectors.toMap(Function.identity(), param -> {
			var type = data.get(param);
			if (type == null) {
				throw new NoContextParameterException(param);
			}
			return type.typeName();
		}, (c, a) -> {
			throw new IllegalStateException("Unreachable");
		}, LinkedHashMap::new));
	}

	public FieldInfo insert(@Nullable Integer index, String to, InputFieldInfo fieldInfo) throws NoContextParameterException {
		var value = convertFieldInfo(fieldInfo);
		if (index == null) {
			return data.putIfAbsent(to, value);
		} else {
			return tryInsertAtIndex(data, to, value, index);
		}
	}

	public Optional<Entry<Integer, FieldInfo>> remove(String find) {
		int foundIndex = -1;
		{
			int i = 0;
			for (var entry : data.entrySet()) {
				if (entry.getKey().equals(find)) {
					foundIndex = i;
				}
				i++;
			}
		}
		if (foundIndex == -1) return Optional.empty();
		return Optional.of(Map.entry(foundIndex, requireNonNull(data.remove(find))));
	}

	public FieldInfo replace(String from, InputFieldInfo fieldInfo) {
		return data.replace(from, convertFieldInfo(fieldInfo));
	}

	private FieldInfo convertFieldInfo(InputFieldInfo fieldInfo) {
		return new FieldInfo(fieldInfo.typeName, computeContextParametersTypes(data, fieldInfo.contextParams));
	}

	private static ParsedClass.FieldInfo tryInsertAtIndex(LinkedHashMap<String, ParsedClass.FieldInfo> data, String key,
														  FieldInfo value, int index) {
		var before = new LinkedHashMap<String, ParsedClass.FieldInfo>(index);
		var after = new LinkedHashMap<String, ParsedClass.FieldInfo>(data.size() - index);
		int i = 0;
		for (Entry<String, ParsedClass.FieldInfo> entry : data.entrySet()) {
			if (i < index) {
				before.put(entry.getKey(), entry.getValue());
			} else {
				after.put(entry.getKey(), entry.getValue());
			}
			i++;
		}
		data.clear();
		data.putAll(before);
		var prev = data.putIfAbsent(key, value);
		data.putAll(after);
		return prev;
	}
}
