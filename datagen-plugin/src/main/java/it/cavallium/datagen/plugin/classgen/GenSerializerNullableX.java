package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeSpec.Builder;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.CodecReadState;
import it.cavallium.datagen.NotSerializableException;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.datagen.ReadSession;
import it.cavallium.buffer.RandomAccessDataInput;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedTypeCustom;
import it.cavallium.datagen.plugin.ComputedTypeNullable;
import it.cavallium.datagen.plugin.ComputedTypeNullableFixed;
import it.cavallium.datagen.plugin.ComputedTypeNullableVersioned;
import it.cavallium.datagen.plugin.ComputedVersion;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.util.Objects;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.jetbrains.annotations.NotNull;

public class GenSerializerNullableX extends ClassGenerator {

	public GenSerializerNullableX(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().parallelStream().flatMap(this::generateVersionClasses);
	}

	private Stream<GeneratedClass> generateVersionClasses(ComputedVersion version) {
		return dataModel
				.getNullableTypesComputed(version)
				.filter(type -> (
						(type instanceof ComputedTypeNullableVersioned versioned && versioned.getVersion().equals(version))
								|| type instanceof ComputedTypeNullableFixed))
				.map(type -> generateTypeVersioned(version, type));
	}

	private GeneratedClass generateTypeVersioned(ComputedVersion version, ComputedTypeNullable typeNullable) {
		ClassName serializerClassName = typeNullable.getJSerializerName(basePackageName);
		var typeNullableClassName = typeNullable.getJTypeNameGeneric(basePackageName);

		var classBuilder = TypeSpec.classBuilder(serializerClassName.simpleName());

		classBuilder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		classBuilder.addSuperinterface(ParameterizedTypeName.get(ClassName.get(DataCodec.class), typeNullableClassName));

		generateSerialize(version, typeNullable, classBuilder);

		generateRead(version, typeNullable, classBuilder);

		generateSkip(typeNullable, classBuilder);

		generateReadSession(typeNullable, classBuilder);

		return new GeneratedClass(serializerClassName.packageName(), classBuilder);
	}

	private void generateSerialize(ComputedVersion version, ComputedTypeNullable typeNullable, Builder classBuilder) {
		var method = MethodSpec.methodBuilder("serialize");

		var base = typeNullable.getBase();
		var baseTypeName = base.getJTypeName(basePackageName);
		var baseSerializerInstance = base.getJSerializerInstance(basePackageName);

		method.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		method.addParameter(ParameterSpec.builder(SafeDataOutput.class, "out").build());
		method.addParameter(ParameterSpec
				.builder(typeNullable.getJTypeNameGeneric(basePackageName), "data")
				.addAnnotation(NotNull.class)
				.build());

		if (generateOldSerializers || version.isCurrent()) {
			method.addStatement("$T.requireNonNull(data)", Objects.class);
			method.addCode("\n");
			method.addStatement("boolean notEmpty = data.getNullable() != null");
			method.addStatement("out.writeBoolean(notEmpty)");
			method.beginControlFlow("if (notEmpty)");
			method.addStatement("$T.$N.serialize(out, ($T) data.getNullable())",
					baseSerializerInstance.className(),
					baseSerializerInstance.fieldName(),
					baseTypeName
			);
			method.endControlFlow();
		} else {
			method.addStatement("throw new $T()", NotSerializableException.class);
		}

		classBuilder.addMethod(method.build());
	}

	private void generateRead(ComputedVersion version, ComputedTypeNullable typeNullable, Builder classBuilder) {
		var base = typeNullable.getBase();
		var baseTypeName = base.getJTypeName(basePackageName);
		var typeNullableClassName = typeNullable.getJTypeName(basePackageName);
		classBuilder.addMethod(MethodSpec.methodBuilder("read")
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.returns(typeNullableClassName)
				.addAnnotation(NotNull.class)
				.addParameter(ParameterSpec.builder(SafeDataInput.class, "in").build())
				.addStatement("return readValue(in, in.decodeBudget().codecReadState())")
				.build());
		var method = MethodSpec.methodBuilder("readValue")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.returns(typeNullableClassName)
				.addAnnotation(NotNull.class)
				.addParameter(ParameterSpec.builder(SafeDataInput.class, "in").build())
				.addParameter(ParameterSpec.builder(CodecReadState.class, "codecState").build());
		method.addStatement("in.decodeBudget().enterStructure()")
				.beginControlFlow("try");
		NullableWireEmitter.emitPresence(method, typeNullable, CodeBlock.of("in"), "present", "first");
		method.beginControlFlow("if (!present)")
				.addStatement("return $T.empty()", typeNullableClassName)
				.endControlFlow();

		if (base instanceof ComputedTypeCustom custom) {
			if (custom.getFixedSize() != null) {
				method.beginControlFlow("if (in instanceof $T randomInput)", RandomAccessDataInput.class)
						.addStatement("int valueStart = randomInput.reserve($L)", custom.getFixedSize())
						.addStatement("return $T.of(($T) $L.readReserved(randomInput, valueStart, $L))",
								typeNullableClassName, baseTypeName,
								customSession(custom, "codecState"), custom.getFixedSize())
						.endControlFlow();
			}
			method.addStatement("return $T.of(($T) $L.read(in))", typeNullableClassName, baseTypeName,
					customSession(custom, "codecState"));
		} else {
			CodeBlock value = NullableWireEmitter.valueExpression(typeNullable, binaryStrings, CodeBlock.of("in"),
					"first", CodeBlock.of("$T.readValue(in, codecState)",
							base.getJSerializerName(basePackageName)));
			method.addStatement("return $T.of(($T) $L)", typeNullableClassName, baseTypeName, value);
		}
		method.nextControlFlow("finally")
				.addStatement("in.decodeBudget().exitStructure()")
				.endControlFlow();

		classBuilder.addMethod(method.build());
	}

	private void generateSkip(ComputedTypeNullable typeNullable, Builder classBuilder) {
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
		CodeBlock ordinarySkip;
		if (typeNullable.getBase() instanceof ComputedTypeCustom custom) {
			if (custom.getFixedSize() != null) {
				ordinarySkip = CodeBlock.of("$T.skipBytes(in, $L)", ProjectionReadSupport.class,
						custom.getFixedSize());
			} else {
				ordinarySkip = CodeBlock.of("$L.skip(in)", customSession(custom, "codecState"));
			}
		} else {
			ordinarySkip = CodeBlock.of("$T.skipValue(in, codecState)",
					typeNullable.getBase().getJSerializerName(basePackageName));
		}
		NullableWireEmitter.emitSkip(method, typeNullable, CodeBlock.of("in"), "present", "first",
				ordinarySkip);
		method.nextControlFlow("finally")
				.addStatement("in.decodeBudget().exitStructure()")
				.endControlFlow();
		classBuilder.addMethod(method.build());
	}

	private void generateReadSession(ComputedTypeNullable typeNullable, Builder classBuilder) {
		var valueType = typeNullable.getJTypeName(basePackageName);
		var sessionValueType = typeNullable.getJTypeNameGeneric(basePackageName);
		ClassName serializerType = typeNullable.getJSerializerName(basePackageName);
		classBuilder.addMethod(MethodSpec.methodBuilder("newReadSession")
				.addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.returns(ParameterizedTypeName.get(ClassName.get(ReadSession.class), sessionValueType))
				.addStatement("return new Session()")
				.build());
		classBuilder.addType(TypeSpec.classBuilder("Session")
				.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
				.superclass(ParameterizedTypeName.get(ClassName.get(ReadSession.class), sessionValueType))
				.addMethod(MethodSpec.methodBuilder("decode")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PROTECTED)
						.returns(valueType)
						.addParameter(SafeDataInput.class, "input")
						.addStatement("return $T.readValue(input, input.decodeBudget().codecReadState())",
								serializerType)
						.build())
				.addMethod(MethodSpec.methodBuilder("skipValue")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PROTECTED)
						.addParameter(SafeDataInput.class, "input")
						.addStatement("$T.skipValue(input, input.decodeBudget().codecReadState())", serializerType)
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
}
