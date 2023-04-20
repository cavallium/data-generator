package it.cavallium.datagen.plugin;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import it.cavallium.datagen.nativedata.ArrayInt52Serializer;
import it.cavallium.datagen.nativedata.ArrayStringSerializer;
import it.cavallium.datagen.nativedata.ArraybooleanSerializer;
import it.cavallium.datagen.nativedata.ArraybyteSerializer;
import it.cavallium.datagen.nativedata.ArraycharSerializer;
import it.cavallium.datagen.nativedata.ArraydoubleSerializer;
import it.cavallium.datagen.nativedata.ArrayfloatSerializer;
import it.cavallium.datagen.nativedata.ArrayintSerializer;
import it.cavallium.datagen.nativedata.ArraylongSerializer;
import it.cavallium.datagen.nativedata.ArrayshortSerializer;
import it.cavallium.datagen.nativedata.Serializers;
import it.unimi.dsi.fastutil.booleans.BooleanList;
import it.unimi.dsi.fastutil.bytes.ByteList;
import it.unimi.dsi.fastutil.chars.CharList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.floats.FloatList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class ComputedTypeArrayNative implements ComputedTypeArray {

	private final String baseType;

	private ComputedTypeNative computedChild;
	private final ComputedTypeSupplier computedTypeSupplier;

	public ComputedTypeArrayNative(String baseType, ComputedTypeSupplier computedTypeSupplier) {
		this.baseType = baseType;
		this.computedTypeSupplier = computedTypeSupplier;
	}

	public ComputedType getBase() {
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

		ComputedTypeArrayNative that = (ComputedTypeArrayNative) o;

		return Objects.equals(baseType, that.baseType);
	}

	@Override
	public int hashCode() {
		return baseType != null ? baseType.hashCode() : 0;
	}

	@Override
	public String getName() {
		return "§" + baseType;
	}

	@Override
	public TypeName getJTypeName(String basePackageName) {
		return switch (baseType) {
			case "boolean" -> ClassName.get(BooleanList.class);
			case "byte" -> ClassName.get(ByteList.class);
			case "short" -> ClassName.get(ShortList.class);
			case "char" -> ClassName.get(CharList.class);
			case "int" -> ClassName.get(IntList.class);
			case "long" -> ClassName.get(LongList.class);
			case "float" -> ClassName.get(FloatList.class);
			case "double" -> ClassName.get(DoubleList.class);
			default -> ParameterizedTypeName.get(ClassName.get(List.class),
					computedTypeSupplier.get(baseType).getJTypeName(basePackageName));
		};
	}

	@Override
	public ClassName getJSerializerName(String basePackageName) {
		return switch (baseType) {
			case "boolean" -> ClassName.get(ArraybooleanSerializer.class);
			case "byte" -> ClassName.get(ArraybyteSerializer.class);
			case "short" -> ClassName.get(ArrayshortSerializer.class);
			case "char" -> ClassName.get(ArraycharSerializer.class);
			case "int" -> ClassName.get(ArrayintSerializer.class);
			case "long" -> ClassName.get(ArraylongSerializer.class);
			case "float" -> ClassName.get(ArrayfloatSerializer.class);
			case "double" -> ClassName.get(ArraydoubleSerializer.class);
			case "String" -> ClassName.get(ArrayStringSerializer.class);
			case "Int52" -> ClassName.get(ArrayInt52Serializer.class);
			default -> throw new UnsupportedOperationException();
		};
	}

	@Override
	public FieldLocation getJSerializerInstance(String basePackageName) {
		var className = ClassName.get(Serializers.class);
		var serializerFieldName = "Array" + baseType + "SerializerInstance";
		return new FieldLocation(className, serializerFieldName);
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
		return Stream.of(child());
	}

	@Override
	public Stream<ComputedType> getDependents() {
		return computedTypeSupplier.getDependents(getName());
	}

	@Override
	public String toString() {
		return baseType + "[]";
	}
}
