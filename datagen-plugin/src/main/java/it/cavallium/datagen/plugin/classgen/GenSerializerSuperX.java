package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeSpec.Builder;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.CodecReadState;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.datagen.NotSerializableException;
import it.cavallium.datagen.ReadSession;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedType;
import it.cavallium.datagen.plugin.ComputedTypeSuper;
import it.cavallium.datagen.plugin.ComputedVersion;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.util.Objects;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.jetbrains.annotations.NotNull;

public class GenSerializerSuperX extends ClassGenerator {

	public GenSerializerSuperX(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().parallelStream().flatMap(this::generateVersionClasses);
	}

	private Stream<GeneratedClass> generateVersionClasses(ComputedVersion version) {
		return dataModel
				.getSuperTypesComputed(version)
				.filter(type -> type.getVersion().equals(version))
				.map(type -> generateTypeVersioned(version, type));
	}

	private GeneratedClass generateTypeVersioned(ComputedVersion version, ComputedTypeSuper typeSuper) {
		ClassName serializerClassName = typeSuper.getJSerializerName(basePackageName);
		ClassName typeSuperClassName = typeSuper.getJTypeName(basePackageName);

		var classBuilder = TypeSpec.classBuilder(serializerClassName.simpleName());

		classBuilder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		classBuilder.addSuperinterface(ParameterizedTypeName.get(ClassName.get(DataCodec.class), typeSuperClassName));

		generateCheckIdValidity(version, typeSuper, classBuilder);

		generateSerialize(version, typeSuper, classBuilder);

		generateRead(version, typeSuper, classBuilder);

		generateSkip(typeSuper, classBuilder);

		generateReadSession(typeSuper, classBuilder);

		return new GeneratedClass(serializerClassName.packageName(), classBuilder);
	}

	private void generateCheckIdValidity(ComputedVersion version, ComputedTypeSuper typeSuper, Builder classBuilder) {
		int max = typeSuper.subTypes().size();
		var method = MethodSpec.methodBuilder("checkIdValidity");
		method.addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
		method.addParameter(ParameterSpec.builder(int.class, "id").build());

		method.beginControlFlow("if (id < 0 || id >= $L)", max);
		method.addStatement("throw new $T($S + id)", MalformedDataException.class,
				"Invalid union discriminator: ");
		method.endControlFlow();

		classBuilder.addMethod(method.build());
	}

	private void generateSerialize(ComputedVersion version, ComputedTypeSuper typeSuper, Builder classBuilder) {
		var method = MethodSpec.methodBuilder("serialize");

		method.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		method.addParameter(ParameterSpec.builder(SafeDataOutput.class, "out").build());
		method.addParameter(ParameterSpec
				.builder(typeSuper.getJTypeName(basePackageName), "data")
				.addAnnotation(NotNull.class)
				.build());

		if (generateOldSerializers || version.isCurrent()) {
			method.addStatement("$T.requireNonNull(data)", Objects.class);
			method.addStatement("int id = data.getMetaId$$$N()", typeSuper.getName());
			method.addStatement("out.writeByte(id)");
			method.beginControlFlow("switch (id)");

			var subTypes = typeSuper.subTypes().toArray(ComputedType[]::new);
			int max = subTypes.length;
			for (int i = 0; i < max; i++) {
				var subType = subTypes[i];
				var subSerializerInstance = subType.getJSerializerInstance(basePackageName);
				method.addStatement("case $L -> $T.$N.serialize(out, ($T) data)",
						i,
						subSerializerInstance.className(),
						subSerializerInstance.fieldName(),
						subType.getJTypeName(basePackageName)
				);
			}
			method.beginControlFlow("default ->");
			method.addStatement("checkIdValidity(id)");
			method.addComment("Not reachable:");
			method.addStatement("throw new $T()", IllegalStateException.class);
			method.endControlFlow();
			method.endControlFlow();
		} else {
			method.addStatement("throw new $T()", NotSerializableException.class);
		}

		classBuilder.addMethod(method.build());
	}

	private void generateRead(ComputedVersion version, ComputedTypeSuper typeSuper, Builder classBuilder) {
		ClassName typeSuperClassName = typeSuper.getJTypeName(basePackageName);
		classBuilder.addMethod(MethodSpec.methodBuilder("read")
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.returns(typeSuperClassName)
				.addAnnotation(NotNull.class)
				.addParameter(ParameterSpec.builder(SafeDataInput.class, "in").build())
				.addStatement("return readValue(in, in.decodeBudget().codecReadState())")
				.build());
		var method = MethodSpec.methodBuilder("readValue")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.returns(typeSuperClassName)
				.addAnnotation(NotNull.class)
				.addParameter(ParameterSpec.builder(SafeDataInput.class, "in").build())
				.addParameter(ParameterSpec.builder(CodecReadState.class, "codecState").build());
		method.addStatement("in.decodeBudget().enterStructure()")
				.beginControlFlow("try");

		method.addStatement("int id = in.readUnsignedByte()");
		method.beginControlFlow("return switch (id)");

		var subTypes = typeSuper.subTypes().toArray(ComputedType[]::new);
		int max = subTypes.length;
		for (int i = 0; i < max; i++) {
			var subType = subTypes[i];
			var subSerializerInstance = subType.getJSerializerInstance(basePackageName);
			method.addStatement("case $L -> ($T) $T.readValue(in, codecState)",
					i,
					subType.getJTypeName(basePackageName),
					subType.getJSerializerName(basePackageName)
			);
		}
		method.beginControlFlow("default ->");
		method.addStatement("checkIdValidity(id)");
		method.addComment("Not reachable:");
		method.addStatement("throw new $T()", IllegalStateException.class);
		method.endControlFlow();
		method.addCode("$<};");
		method.nextControlFlow("finally")
				.addStatement("in.decodeBudget().exitStructure()")
				.endControlFlow();

		classBuilder.addMethod(method.build());
	}

	private void generateSkip(ComputedTypeSuper typeSuper, Builder classBuilder) {
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
				.beginControlFlow("try")
				.addStatement("int id = in.readUnsignedByte()")
				.beginControlFlow("switch (id)");

		var subTypes = typeSuper.subTypes().toArray(ComputedType[]::new);
		for (int i = 0; i < subTypes.length; i++) {
			method.addStatement("case $L -> $T.skipValue(in, codecState)", i,
					subTypes[i].getJSerializerName(basePackageName));
		}
		method.beginControlFlow("default ->")
				.addStatement("checkIdValidity(id)")
				.endControlFlow()
				.endControlFlow()
				.nextControlFlow("finally")
				.addStatement("in.decodeBudget().exitStructure()")
				.endControlFlow();
		classBuilder.addMethod(method.build());
	}

	private void generateReadSession(ComputedTypeSuper typeSuper, Builder classBuilder) {
		ClassName valueType = typeSuper.getJTypeName(basePackageName);
		ClassName serializerType = typeSuper.getJSerializerName(basePackageName);
		classBuilder.addMethod(MethodSpec.methodBuilder("newReadSession")
				.addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.returns(ParameterizedTypeName.get(ClassName.get(ReadSession.class), valueType))
				.addStatement("return new Session()")
				.build());
		classBuilder.addType(TypeSpec.classBuilder("Session")
				.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
				.superclass(ParameterizedTypeName.get(ClassName.get(ReadSession.class), valueType))
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
}
