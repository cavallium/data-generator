package it.cavallium.datagen.plugin;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import java.util.Objects;
import java.util.stream.Stream;

public final class ComputedTypeCustom implements ComputedType {

	private final String type;
	private final ComputedVersion latestVersion;
	private final String javaClass;
	private final String serializer;
	private final ComputedTypeSupplier computedTypeSupplier;
	private final TypeName typeName;

	public ComputedTypeCustom(String type,
			String javaClass,
			String serializer,
			ComputedTypeSupplier computedTypeSupplier,
			ComputedVersion latestVersion) {
		this.type = type;
		this.latestVersion = latestVersion;
		this.javaClass = javaClass;
		this.serializer = serializer;
		this.computedTypeSupplier = computedTypeSupplier;
		{
			int indexOfGeneric;
			if ((indexOfGeneric = javaClass.indexOf("<")) == -1) {
				this.typeName = ClassName.bestGuess(javaClass.trim());
			} else {
				var rawTypesArray = javaClass.substring(indexOfGeneric + 1, javaClass.length() - 1).split(",");
				var genericsResult = new TypeName[rawTypesArray.length];
				int i = 0;
				for (String rawType : rawTypesArray) {
					genericsResult[i] = ClassName.bestGuess(rawType.trim());
					i++;
				}
				var base = ClassName.bestGuess(javaClass.substring(0, indexOfGeneric).trim());
				this.typeName = ParameterizedTypeName.get(base, genericsResult);
			}
		}
	}

	public String getType() {
		return type;
	}

	public String getJavaClass() {
		return javaClass;
	}

	public String getSerializer() {
		return serializer;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		ComputedTypeCustom that = (ComputedTypeCustom) o;

		if (!Objects.equals(type, that.type)) {
			return false;
		}
		if (!Objects.equals(javaClass, that.javaClass)) {
			return false;
		}
		return Objects.equals(serializer, that.serializer);
	}

	@Override
	public int hashCode() {
		int result = type != null ? type.hashCode() : 0;
		result = 31 * result + (javaClass != null ? javaClass.hashCode() : 0);
		result = 31 * result + (serializer != null ? serializer.hashCode() : 0);
		return result;
	}

	@Override
	public String getName() {
		return type;
	}

	@Override
	public TypeName getJTypeName(String basePackageName) {
		return typeName;
	}

	@Override
	public TypeName getJSerializerName(String basePackageName) {
		return ClassName.bestGuess(serializer);
	}

	@Override
	public FieldLocation getJSerializerInstance(String basePackageName) {
		return new FieldLocation(ClassName.get(latestVersion.getPackage(basePackageName), "Version"), type + "SerializerInstance");
	}

	@Override
	public TypeName getJUpgraderName(String basePackageName) {
		throw new UnsupportedOperationException("Not upgradable");
	}

	@Override
	public FieldLocation getJUpgraderInstance(String basePackageName) {
		throw new UnsupportedOperationException("Not upgradable");
	}

	@Override
	public Stream<ComputedType> getDependencies() {
		return Stream.of();
	}

	@Override
	public Stream<ComputedType> getDependents() {
		return computedTypeSupplier.getDependents(type);
	}

	@Override
	public String toString() {
		return type + " (custom)";
	}
}
