package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeSpec.Builder;
import it.cavallium.buffer.RandomAccessDataInput;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.CodecReadState;
import it.cavallium.datagen.NotSerializableException;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.datagen.ReadSession;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedType;
import it.cavallium.datagen.plugin.ComputedTypeArray;
import it.cavallium.datagen.plugin.ComputedTypeArrayNative;
import it.cavallium.datagen.plugin.ComputedTypeBase;
import it.cavallium.datagen.plugin.ComputedTypeCustom;
import it.cavallium.datagen.plugin.ComputedTypeNative;
import it.cavallium.datagen.plugin.ComputedTypeNullable;
import it.cavallium.datagen.plugin.ComputedTypeNullableNative;
import it.cavallium.datagen.plugin.ComputedTypeSuper;
import it.cavallium.datagen.plugin.ComputedVersion;
import it.cavallium.datagen.plugin.GeneratedNameAllocator;
import it.cavallium.datagen.plugin.WireLayout;
import it.cavallium.datagen.nativedata.BinaryString;
import it.cavallium.datagen.nativedata.BinaryStringSerializer;
import it.cavallium.datagen.nativedata.Int52Serializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

public class GenSerializerBaseX extends ClassGenerator {
	private static final ClassName VECTOR_ARRAY_SUPPORT =
			ClassName.get("it.cavallium.datagen.vector", "VectorArraySupport");

	public GenSerializerBaseX(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().parallelStream().flatMap(this::generateVersionClasses);
	}

	private Stream<GeneratedClass> generateVersionClasses(ComputedVersion version) {
		return dataModel
				.getBaseTypesComputed(version)
				.filter(type -> type.getVersion().equals(version))
				.map(type -> generateTypeVersioned(version, type));
	}

	private GeneratedClass generateTypeVersioned(ComputedVersion version, ComputedTypeBase typeBase) {
		ClassName serializerClassName = typeBase.getJSerializerName(basePackageName);
		ClassName typeBaseClassName = typeBase.getJTypeName(basePackageName);

		var classBuilder = TypeSpec.classBuilder(serializerClassName.simpleName());

		classBuilder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		classBuilder.addSuperinterface(ParameterizedTypeName.get(ClassName.get(DataCodec.class), typeBaseClassName));

		generateSerialize(version, typeBase, classBuilder);

		generateRead(version, typeBase, classBuilder);

		generateSkip(typeBase, classBuilder);

		generateReadSession(typeBase, classBuilder);

		return new GeneratedClass(serializerClassName.packageName(), classBuilder);
	}

	private void generateSerialize(ComputedVersion version, ComputedTypeBase typeBase, Builder classBuilder) {
		var method = MethodSpec.methodBuilder("serialize");

		method.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		method.addParameter(ParameterSpec.builder(SafeDataOutput.class, "out").build());
		method.addParameter(ParameterSpec
				.builder(typeBase.getJTypeName(basePackageName), "data")
				.addAnnotation(NotNull.class)
				.build());

		if (generateOldSerializers || version.isCurrent()) {
			method.addStatement("$T.requireNonNull(data)", Objects.class);
			method.addCode("\n");

			typeBase.getData().forEach((fieldName, fieldType) ->
					emitSerializeField(method, typeBase, fieldName, fieldType));
		} else {
			method.addStatement("throw new $T()", NotSerializableException.class);
		}

		classBuilder.addMethod(method.build());
	}

	private void generateRead(ComputedVersion version, ComputedTypeBase typeBase, Builder classBuilder) {
		ClassName typeBaseClassName = typeBase.getJTypeName(basePackageName);
		classBuilder.addMethod(MethodSpec.methodBuilder("read")
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.returns(typeBaseClassName)
				.addAnnotation(NotNull.class)
				.addParameter(ParameterSpec.builder(SafeDataInput.class, "in").build())
				.addStatement("return readValue(in, in.decodeBudget().codecReadState())")
				.build());

		var method = MethodSpec.methodBuilder("readValue")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.returns(typeBaseClassName)
				.addAnnotation(NotNull.class)
				.addParameter(ParameterSpec.builder(SafeDataInput.class, "in").build())
				.addParameter(ParameterSpec.builder(CodecReadState.class, "codecState").build());
		method.addStatement("in.decodeBudget().enterStructure()")
				.beginControlFlow("try");

		for (var field : typeBase.getData().entrySet()) {
			emitReadField(method, typeBase, field.getKey(), field.getValue());
		}
		method.addStatement("return $T.unsafeOfOwned($L)", typeBaseClassName, readArguments(typeBase));
		method.nextControlFlow("finally")
				.addStatement("in.decodeBudget().exitStructure()")
				.endControlFlow();

		classBuilder.addMethod(method.build());
	}

	private void emitSerializeField(MethodSpec.Builder method, ComputedTypeBase owner,
			String fieldName, ComputedType fieldType) {
		if (fieldType instanceof ComputedTypeNullable nullable) {
			emitSerializeNullable(method, owner, fieldName, nullable);
			return;
		}
		if (fieldType.isPrimitive()) {
			method.addStatement("out.write$N(data.$N())", StringUtils.capitalize(fieldType.getName()), fieldName);
			return;
		}
		var codec = fieldType.getJSerializerInstance(basePackageName);
		method.addStatement("$T.$N.serialize(out, data.$N$L)", codec.className(), codec.fieldName(), fieldName,
				fieldType instanceof ComputedTypeArray ? "UnsafeArray()" : "()");
	}

	private void emitSerializeNullable(MethodSpec.Builder method,
			ComputedTypeBase owner,
			String fieldName,
			ComputedTypeNullable nullable) {
		ComputedType base = nullable.getBase();
		if (WireLayout.of(nullable) == WireLayout.INT52_HIGH_BIT_SENTINEL) {
			method.beginControlFlow("if (data.has$N())", StringUtils.capitalize(fieldName))
					.addStatement("$T.serializeValue(out, data.$N())", Int52Serializer.class, fieldName)
					.nextControlFlow("else")
					.addStatement("out.writeByte(0x80)")
					.endControlFlow();
			return;
		}
		String presentLocal = wireLocalName(owner, fieldName, "present");
		if (WireLayout.of(nullable) == WireLayout.BOOLEAN_TAGGED_SHORT_STRING
				&& base.getJTypeName(basePackageName).equals(ClassName.get(BinaryString.class))) {
			method.beginControlFlow("if (data.has$N())", StringUtils.capitalize(fieldName))
					.addStatement("$T.validateShort(data.$N())", BinaryStringSerializer.class, fieldName)
					.endControlFlow();
		}
		method.addStatement("boolean $N = data.has$N()", presentLocal,
				StringUtils.capitalize(fieldName))
				.addStatement("out.writeBoolean($N)", presentLocal)
				.beginControlFlow("if ($N)", presentLocal);
		if (base.isPrimitive()) {
			method.addStatement("out.write$N(data.$N())", StringUtils.capitalize(base.getName()), fieldName);
		} else if (base instanceof ComputedTypeNative nativeType && nativeType.getName().equals("String")) {
			if (base.getJTypeName(basePackageName).equals(ClassName.get(BinaryString.class))) {
				method.addStatement("$T.writeShort(out, data.$N())", BinaryStringSerializer.class, fieldName);
			} else {
				method.addStatement("out.writeShortText(data.$N(), $T.UTF_8)", fieldName, StandardCharsets.class);
			}
		} else {
			var codec = base.getJSerializerInstance(basePackageName);
			method.addStatement("$T.$N.serialize(out, data.$N())", codec.className(), codec.fieldName(), fieldName);
		}
		method.endControlFlow();
	}

	private void emitReadField(MethodSpec.Builder method, ComputedTypeBase owner,
			String fieldName, ComputedType fieldType) {
		if (fieldType instanceof ComputedTypeNullable nullable) {
			emitReadNullable(method, owner, fieldName, nullable);
			return;
		}
		TypeName javaType = fieldType.getJTypeName(basePackageName);
		String valueLocal = readLocalName(owner, fieldName);
		if (vectorKernels && fieldType instanceof ComputedTypeArray array
				&& array.getBase() instanceof ComputedTypeNative nativeType
				&& vectorArrayMethod(nativeType.getName()) != null) {
			method.addStatement("final $T $N = $T.$N(in)", javaType, valueLocal, VECTOR_ARRAY_SUPPORT,
					vectorArrayMethod(nativeType.getName()));
			return;
		}
		if (fieldType.isPrimitive()) {
			method.addStatement("final $T $N = in.read$N()", javaType, valueLocal,
					StringUtils.capitalize(fieldType.getName()));
		} else if (fieldType instanceof ComputedTypeCustom custom) {
			method.addStatement("final $T $N = ($T) $L.read(in)", javaType, valueLocal, javaType,
					customSession(custom, "codecState"));
		} else if (hasGeneratedStateHelper(fieldType)) {
			method.addStatement("final $T $N = ($T) $T.readValue(in, codecState)", javaType, valueLocal,
					javaType, fieldType.getJSerializerName(basePackageName));
		} else {
			var codec = fieldType.getJSerializerInstance(basePackageName);
			method.addStatement("final $T $N = ($T) $T.$N.read(in)", javaType, valueLocal, javaType,
					codec.className(), codec.fieldName());
		}
	}

	private String vectorArrayMethod(String nativeName) {
		return switch (nativeName) {
			case "boolean" -> "readBooleanArray";
			case "byte" -> "readByteArray";
			case "short" -> "readShortArray";
			case "char" -> "readCharArray";
			case "int" -> "readIntArray";
			case "long" -> "readLongArray";
			case "float" -> "readFloatArray";
			case "double" -> "readDoubleArray";
			case "Int52" -> "readInt52Array";
			default -> null;
		};
	}

	private void emitReadNullable(MethodSpec.Builder method,
			ComputedTypeBase owner,
			String fieldName,
			ComputedTypeNullable nullable) {
		ComputedType base = nullable.getBase();
		TypeName valueType = base.getJTypeName(basePackageName);
		String valueLocal = readLocalName(owner, fieldName);
		String presentLocal = wireLocalName(owner, fieldName, "present");
		String firstLocal = wireLocalName(owner, fieldName, "first");
		NullableWireEmitter.emitPresence(method, nullable, CodeBlock.of("in"), presentLocal, firstLocal);
		if (base instanceof ComputedTypeCustom custom && custom.getFixedSize() != null) {
			String randomInput = wireLocalName(owner, fieldName, "randomInput");
			String valueStart = wireLocalName(owner, fieldName, "valueStart");
			method.addStatement("final $T $N", valueType, valueLocal)
					.beginControlFlow("if ($N)", presentLocal)
					.beginControlFlow("if (in instanceof $T $N)", RandomAccessDataInput.class, randomInput)
					.addStatement("final int $N = $N.reserve($L)", valueStart, randomInput, custom.getFixedSize())
					.addStatement("$N = ($T) $L.readReserved($N, $N, $L)", valueLocal, valueType,
							customSession(custom, "codecState"), randomInput, valueStart, custom.getFixedSize())
					.nextControlFlow("else")
					.addStatement("$N = ($T) $L.read(in)", valueLocal, valueType,
							customSession(custom, "codecState"))
					.endControlFlow()
					.nextControlFlow("else")
					.addStatement("$N = null", valueLocal)
					.endControlFlow();
			return;
		}
		CodeBlock ordinaryValue;
		if (valueType.isPrimitive()) {
			ordinaryValue = CodeBlock.of("in.read$N()", StringUtils.capitalize(base.getName()));
			CodeBlock value = NullableWireEmitter.valueExpression(nullable, binaryStrings, CodeBlock.of("in"),
					firstLocal, ordinaryValue);
			method.addStatement("final $T $N = $N ? ($T) $L : $L", valueType, valueLocal,
					presentLocal, valueType, value, primitiveDefault(valueType));
			return;
		}
		if (base instanceof ComputedTypeNative) {
			ordinaryValue = CodeBlock.of("in.read$N()", StringUtils.capitalize(base.getName()));
		} else if (base instanceof ComputedTypeCustom custom) {
			ordinaryValue = CodeBlock.of("($T) $L.read(in)", valueType,
					customSession(custom, "codecState"));
		} else if (hasGeneratedStateHelper(base)) {
			ordinaryValue = CodeBlock.of("($T) $T.readValue(in, codecState)", valueType,
					base.getJSerializerName(basePackageName));
		} else {
			var codec = base.getJSerializerInstance(basePackageName);
			ordinaryValue = CodeBlock.of("($T) $T.$N.read(in)", valueType, codec.className(), codec.fieldName());
		}
		CodeBlock value = NullableWireEmitter.valueExpression(nullable, binaryStrings, CodeBlock.of("in"),
				firstLocal, ordinaryValue);
		method.addStatement("final $T $N = $N ? ($T) $L : null", valueType, valueLocal,
				presentLocal, valueType, value);
	}

	private CodeBlock readArguments(ComputedTypeBase typeBase) {
		CodeBlock.Builder result = CodeBlock.builder();
		int index = 0;
		for (var field : typeBase.getData().entrySet()) {
			if (index++ != 0) result.add(", ");
			if (field.getValue() instanceof ComputedTypeNullable nullable
					&& nullable.getBase().getJTypeName(basePackageName).isPrimitive()) {
				result.add("$N, $N", wireLocalName(typeBase, field.getKey(), "present"),
						readLocalName(typeBase, field.getKey()));
			} else {
				result.add("$N", readLocalName(typeBase, field.getKey()));
			}
		}
		return result.build();
	}

	private static String wireLocalName(ComputedTypeBase owner, String field, String role) {
		SerializerNames names = serializerNames(owner);
		return switch (role) {
			case "present" -> requireGeneratedName(names.presences(), owner, field, role);
			case "first" -> requireGeneratedName(names.firstBytes(), owner, field, role);
			case "randomInput" -> requireGeneratedName(names.randomInputs(), owner, field, role);
			case "valueStart" -> requireGeneratedName(names.valueStarts(), owner, field, role);
			default -> throw new IllegalArgumentException("Unknown generated-local role: " + role);
		};
	}

	private static String readLocalName(ComputedTypeBase owner, String field) {
		return requireGeneratedName(serializerNames(owner).values(), owner, field, "value");
	}

	private static String requireGeneratedName(Map<String, String> names,
			ComputedTypeBase owner,
			String field,
			String role) {
		String name = names.get(field);
		if (name == null) {
			throw new IllegalArgumentException("Unknown " + role + " field " + field + " of " + owner.getName());
		}
		return name;
	}

	private static SerializerNames serializerNames(ComputedTypeBase owner) {
		var allocator = new GeneratedNameAllocator(owner.getData().keySet(),
				java.util.List.of("in", "out", "data", "codecState", "input", "result", "size", "index"));
		var values = new LinkedHashMap<String, String>();
		var presences = new LinkedHashMap<String, String>();
		var firstBytes = new LinkedHashMap<String, String>();
		var randomInputs = new LinkedHashMap<String, String>();
		var valueStarts = new LinkedHashMap<String, String>();
		for (var entry : owner.getData().entrySet()) {
			String field = entry.getKey();
			values.put(field, allocator.allocate("value$" + field));
			if (entry.getValue() instanceof ComputedTypeNullable) {
				presences.put(field, allocator.allocate("present$" + field));
				firstBytes.put(field, allocator.allocate("first$" + field));
				randomInputs.put(field, allocator.allocate("randomInput$" + field));
				valueStarts.put(field, allocator.allocate("valueStart$" + field));
			}
		}
		return new SerializerNames(Map.copyOf(values), Map.copyOf(presences), Map.copyOf(firstBytes),
				Map.copyOf(randomInputs), Map.copyOf(valueStarts));
	}

	private record SerializerNames(Map<String, String> values,
			Map<String, String> presences,
			Map<String, String> firstBytes,
			Map<String, String> randomInputs,
			Map<String, String> valueStarts) { }

	private void generateSkip(ComputedTypeBase typeBase, Builder classBuilder) {
		classBuilder.addMethod(MethodSpec.methodBuilder("skip")
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.addParameter(SafeDataInput.class, "in")
				.addStatement("skipValue(in, in.decodeBudget().codecReadState())")
				.build());

		var method = MethodSpec.methodBuilder("skipValue")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.addParameter(SafeDataInput.class, "in")
				.addParameter(CodecReadState.class, "codecState")
				.addStatement("in.decodeBudget().enterStructure()")
				.beginControlFlow("try");

		int pendingFixed = 0;
		for (ComputedType fieldType : typeBase.getData().values()) {
			Integer fixedSize = fixedLeafSize(fieldType);
			if (fixedSize != null) {
				pendingFixed = Math.addExact(pendingFixed, fixedSize);
				continue;
			}
			if (pendingFixed != 0) {
				method.addStatement("$T.skipBytes(in, $L)", ProjectionReadSupport.class, pendingFixed);
				pendingFixed = 0;
			}
			if (fieldType instanceof ComputedTypeCustom custom) {
				method.addStatement("$L.skip(in)", customSession(custom, "codecState"));
			} else if (hasGeneratedStateHelper(fieldType)) {
				method.addStatement("$T.skipValue(in, codecState)",
						fieldType.getJSerializerName(basePackageName));
			} else {
				var codec = fieldType.getJSerializerInstance(basePackageName);
				method.addStatement("$T.$N.skip(in)", codec.className(), codec.fieldName());
			}
		}
		if (pendingFixed != 0) {
			method.addStatement("$T.skipBytes(in, $L)", ProjectionReadSupport.class, pendingFixed);
		}
		method.nextControlFlow("finally")
				.addStatement("in.decodeBudget().exitStructure()")
				.endControlFlow();

		classBuilder.addMethod(method.build());
	}

	private void generateReadSession(ComputedTypeBase typeBase, Builder classBuilder) {
		ClassName valueType = typeBase.getJTypeName(basePackageName);
		ClassName sessionType = ClassName.bestGuess("Session");
		classBuilder.addMethod(MethodSpec.methodBuilder("newReadSession")
				.addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.returns(ParameterizedTypeName.get(ClassName.get(ReadSession.class), valueType))
				.addStatement("return new $T()", sessionType)
				.build());
		classBuilder.addType(TypeSpec.classBuilder(sessionType.simpleName())
				.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
				.superclass(ParameterizedTypeName.get(ClassName.get(ReadSession.class), valueType))
				.addMethod(MethodSpec.methodBuilder("decode")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PROTECTED)
						.returns(valueType)
						.addParameter(SafeDataInput.class, "input")
						.addStatement("return readValue(input, input.decodeBudget().codecReadState())")
						.build())
				.addMethod(MethodSpec.methodBuilder("skipValue")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PROTECTED)
						.addParameter(SafeDataInput.class, "input")
						.addStatement("$T.skipValue(input, input.decodeBudget().codecReadState())",
								typeBase.getJSerializerName(basePackageName))
						.build())
				.addMethod(MethodSpec.methodBuilder("clearTransientState")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PROTECTED)
						.addComment("All mutable custom state belongs to the input lane's CodecReadState.")
						.build())
				.build());
	}

	private CodeBlock customSession(ComputedTypeCustom custom, String stateName) {
		var codec = custom.getJSerializerInstance(basePackageName);
		return CodeBlock.of("$N.session($S, $T.$N)", stateName, custom.getName(),
				codec.className(), codec.fieldName());
	}

	private static boolean hasGeneratedStateHelper(ComputedType type) {
		if (type instanceof ComputedTypeBase || type instanceof ComputedTypeSuper) return true;
		if (type instanceof ComputedTypeArray) return !(type instanceof ComputedTypeArrayNative);
		return type instanceof ComputedTypeNullable && !(type instanceof ComputedTypeNullableNative);
	}

	private static Integer fixedLeafSize(ComputedType type) {
		if (type.isPrimitive()) {
			return switch (type.getName()) {
				case "boolean", "byte" -> 1;
				case "short", "char" -> 2;
				case "int", "float" -> 4;
				case "long", "double" -> 8;
				default -> throw new IllegalStateException(type.getName());
			};
		}
		return type instanceof ComputedTypeCustom custom ? custom.getFixedSize() : null;
	}

	private static CodeBlock primitiveDefault(TypeName type) {
		return switch (type.toString()) {
			case "boolean" -> CodeBlock.of("false");
			case "byte" -> CodeBlock.of("(byte) 0");
			case "short" -> CodeBlock.of("(short) 0");
			case "char" -> CodeBlock.of("(char) 0");
			case "int" -> CodeBlock.of("0");
			case "long" -> CodeBlock.of("0L");
			case "float" -> CodeBlock.of("0.0f");
			case "double" -> CodeBlock.of("0.0d");
			default -> throw new IllegalArgumentException("Not a primitive: " + type);
		};
	}
}
