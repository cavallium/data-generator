package it.cavallium.data.generator.plugin;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import it.cavallium.data.generator.nativedata.Int52Serializer;
import it.cavallium.data.generator.nativedata.Serializers;
import it.cavallium.data.generator.nativedata.StringSerializer;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public final class ComputedTypeNative implements ComputedType {

	private static final Set<String> PRIMITIVE_TYPES = Set.of("boolean", "short", "char", "int", "long", "float", "double", "byte");

	private final String type;
	private final ComputedTypeSupplier computedTypeSupplier;
	private boolean primitive;

	public ComputedTypeNative(String type, ComputedTypeSupplier computedTypeSupplier) {
		this.type = type;
		this.computedTypeSupplier = computedTypeSupplier;
		this.primitive = PRIMITIVE_TYPES.contains(type);
	}

	public String getName() {
		return type;
	}

	@Override
	public TypeName getJTypeName(String basePackageName) {
		return switch (type) {
			case "String" -> ClassName.get(String.class);
			case "boolean" -> TypeName.BOOLEAN;
			case "short" -> TypeName.SHORT;
			case "char" -> TypeName.CHAR;
			case "int" -> TypeName.INT;
			case "long" -> TypeName.LONG;
			case "float" -> TypeName.FLOAT;
			case "double" -> TypeName.DOUBLE;
			case "byte" -> TypeName.BYTE;
			case "Int52" -> ClassName.get(it.cavallium.data.generator.nativedata.Int52.class);
			default -> throw new UnsupportedOperationException(type + " is not a known native type");
		};
	}

	@Override
	public TypeName getJSerializerName(String basePackageName) {
		return switch (type) {
			case "String" -> ClassName.get(StringSerializer.class);
			case "boolean", "byte", "short", "char", "int", "long", "float", "double" ->
					throw new UnsupportedOperationException("Type " + type
							+ " is a native type, so it doesn't have a serializer");
			case "Int52" -> ClassName.get(Int52Serializer.class);
			default -> throw new UnsupportedOperationException(type + " is not a known native type");
		};
	}

	@Override
	public FieldLocation getJSerializerInstance(String basePackageName) {
		return new FieldLocation(ClassName.get(Serializers.class), type + "SerializerInstance");
	}

	@Override
	public TypeName getJUpgraderName(String basePackageName) {
		throw new UnsupportedOperationException("Type " + type + " is a native type, so it doesn't have a upgrader");
	}

	@Override
	public FieldLocation getJUpgraderInstance(String basePackageName) {
		throw new UnsupportedOperationException("Type " + type + " is a native type, so it doesn't have a upgrader");
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		it.cavallium.data.generator.plugin.ComputedTypeNative that = (it.cavallium.data.generator.plugin.ComputedTypeNative) o;

		return Objects.equals(type, that.type);
	}

	@Override
	public int hashCode() {
		return type != null ? type.hashCode() : 0;
	}

	@Override
	public Stream<ComputedType> getDependencies() {
		return Stream.of();
	}

	@Override
	public Stream<ComputedType> getDependents() {
		return computedTypeSupplier.getDependents(getName());
	}

	public static List<it.cavallium.data.generator.plugin.ComputedTypeNative> get(ComputedTypeSupplier computedTypeSupplier) {
		return Stream
				.of("String", "boolean", "short", "char", "int", "long", "float", "double", "byte", "Int52")
				.map(name -> new it.cavallium.data.generator.plugin.ComputedTypeNative(name, computedTypeSupplier))
				.toList();
	}

	@Override
	public String toString() {
		return type + " (native)";
	}

	public boolean isPrimitive() {
		return primitive;
	}
}
