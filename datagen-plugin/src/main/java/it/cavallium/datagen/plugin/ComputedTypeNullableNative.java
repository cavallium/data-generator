package it.cavallium.datagen.plugin;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import it.cavallium.datagen.nativedata.NullableInt52;
import it.cavallium.datagen.nativedata.NullableInt52Serializer;
import it.cavallium.datagen.nativedata.NullableString;
import it.cavallium.datagen.nativedata.NullableStringSerializer;
import it.cavallium.datagen.nativedata.Nullableboolean;
import it.cavallium.datagen.nativedata.NullablebooleanSerializer;
import it.cavallium.datagen.nativedata.Nullablebyte;
import it.cavallium.datagen.nativedata.NullablebyteSerializer;
import it.cavallium.datagen.nativedata.Nullablechar;
import it.cavallium.datagen.nativedata.NullablecharSerializer;
import it.cavallium.datagen.nativedata.Nullabledouble;
import it.cavallium.datagen.nativedata.NullabledoubleSerializer;
import it.cavallium.datagen.nativedata.Nullablefloat;
import it.cavallium.datagen.nativedata.NullablefloatSerializer;
import it.cavallium.datagen.nativedata.Nullableint;
import it.cavallium.datagen.nativedata.NullableintSerializer;
import it.cavallium.datagen.nativedata.Nullablelong;
import it.cavallium.datagen.nativedata.NullablelongSerializer;
import it.cavallium.datagen.nativedata.Nullableshort;
import it.cavallium.datagen.nativedata.NullableshortSerializer;
import it.cavallium.datagen.nativedata.Serializers;
import java.util.Objects;
import java.util.stream.Stream;

public final class ComputedTypeNullableNative implements ComputedTypeNullable {

	private final String baseType;
	private final ComputedVersion latestVersion;

	private ComputedTypeNative computedChild;
	private final ComputedTypeSupplier computedTypeSupplier;

	public ComputedTypeNullableNative(String baseType, ComputedVersion latestVersion, ComputedTypeSupplier computedTypeSupplier) {
		this.baseType = baseType;
		this.latestVersion = latestVersion;
		this.computedTypeSupplier = computedTypeSupplier;
	}

	public ComputedTypeNative getBase() {
		return child();
	}

	public ComputedTypeNative child() {
		synchronized (this) {
			if (computedChild == null) {
				var computedChild = computedTypeSupplier.get(baseType);
				if (computedChild instanceof ComputedTypeNative computedTypeNative) {
					this.computedChild = computedTypeNative;
				} else {
					throw new IllegalStateException();
				}
			}
		}
		return computedChild;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		ComputedTypeNullableNative that = (ComputedTypeNullableNative) o;

		return Objects.equals(baseType, that.baseType);
	}

	@Override
	public int hashCode() {
		return baseType != null ? baseType.hashCode() : 0;
	}

	@Override
	public String getName() {
		return "-" + baseType;
	}

	@Override
	public TypeName getJTypeName(String basePackageName) {
		return switch (baseType) {
			case "boolean" -> ClassName.get(Nullableboolean.class);
			case "byte" -> ClassName.get(Nullablebyte.class);
			case "short" -> ClassName.get(Nullableshort.class);
			case "char" -> ClassName.get(Nullablechar.class);
			case "int" -> ClassName.get(Nullableint.class);
			case "long" -> ClassName.get(Nullablelong.class);
			case "float" -> ClassName.get(Nullablefloat.class);
			case "double" -> ClassName.get(Nullabledouble.class);
			case "String" -> ClassName.get(NullableString.class);
			case "Int52" -> ClassName.get(NullableInt52.class);
			default -> ClassName.get(latestVersion.getDataNullablesPackage(basePackageName), "Nullable" + baseType);
		};
	}

	@Override
	public TypeName getJTypeNameGeneric(String basePackageName) {
		return getJTypeName(basePackageName);
	}

	@Override
	public ClassName getJSerializerName(String basePackageName) {
		return switch (baseType) {
			case "boolean" -> ClassName.get(NullablebooleanSerializer.class);
			case "byte" -> ClassName.get(NullablebyteSerializer.class);
			case "short" -> ClassName.get(NullableshortSerializer.class);
			case "char" -> ClassName.get(NullablecharSerializer.class);
			case "int" -> ClassName.get(NullableintSerializer.class);
			case "long" -> ClassName.get(NullablelongSerializer.class);
			case "float" -> ClassName.get(NullablefloatSerializer.class);
			case "double" -> ClassName.get(NullabledoubleSerializer.class);
			case "String" -> ClassName.get(NullableStringSerializer.class);
			case "Int52" -> ClassName.get(NullableInt52Serializer.class);
			default -> throw new UnsupportedOperationException();
		};
	}

	@Override
	public FieldLocation getJSerializerInstance(String basePackageName) {
		var className = switch (baseType) {
			case "boolean", "byte", "short", "char", "int", "long", "float", "double", "String", "Int52" ->
					ClassName.get(Serializers.class);
			default -> throw new UnsupportedOperationException();
		};
		var serializerFieldName = "Nullable" + baseType + "SerializerInstance";
		return new FieldLocation(className, serializerFieldName);
	}

	@Override
	public TypeName getJUpgraderName(String basePackageName) {
		throw new UnsupportedOperationException("Type " + baseType + " is a native type, so it doesn't have a upgrader");
	}

	@Override
	public FieldLocation getJUpgraderInstance(String basePackageName) {
		throw new UnsupportedOperationException("Type " + baseType + " is a native type, so it doesn't have a upgrader");
	}

	@Override
	public Stream<ComputedType> getDependencies() {
		return Stream.of(child());
	}

	@Override
	public Stream<ComputedType> getDependents() {
		return computedTypeSupplier.getDependents(getName());
	}

	@Override
	public String toString() {
		return "-" + baseType;
	}
}
