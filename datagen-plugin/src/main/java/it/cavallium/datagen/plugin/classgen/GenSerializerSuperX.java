package it.cavallium.datagen.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import it.cavallium.datagen.DataSerializer;
import it.cavallium.datagen.NotSerializableException;
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

		classBuilder.addSuperinterface(ParameterizedTypeName.get(ClassName.get(DataSerializer.class), typeSuperClassName));

		generateCheckIdValidity(version, typeSuper, classBuilder);

		generateSerialize(version, typeSuper, classBuilder);

		generateDeserialize(version, typeSuper, classBuilder);

		return new GeneratedClass(serializerClassName.packageName(), classBuilder);
	}

	private void generateCheckIdValidity(ComputedVersion version, ComputedTypeSuper typeSuper, Builder classBuilder) {
		int max = typeSuper.subTypes().size();
		var method = MethodSpec.methodBuilder("checkIdValidity");
		method.addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
		method.addParameter(ParameterSpec.builder(int.class, "id").build());

		method.beginControlFlow("if (id < 0 || id >= $L)", max);
		method.addStatement("throw new $T(id)", IndexOutOfBoundsException.class);
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

	private void generateDeserialize(ComputedVersion version, ComputedTypeSuper typeSuper, Builder classBuilder) {
		var method = MethodSpec.methodBuilder("deserialize");

		method.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		ClassName typeSuperClassName = typeSuper.getJTypeName(basePackageName);
		method.returns(typeSuperClassName);
		method.addAnnotation(NotNull.class);

		method.addParameter(ParameterSpec.builder(SafeDataInput.class, "in").build());

		method.addStatement("int id = in.readUnsignedByte()");
		method.beginControlFlow("return switch (id)");

		var subTypes = typeSuper.subTypes().toArray(ComputedType[]::new);
		int max = subTypes.length;
		for (int i = 0; i < max; i++) {
			var subType = subTypes[i];
			var subSerializerInstance = subType.getJSerializerInstance(basePackageName);
			method.addStatement("case $L -> ($T) $T.$N.deserialize(in)",
					i,
					subType.getJTypeName(basePackageName),
					subSerializerInstance.className(),
					subSerializerInstance.fieldName()
			);
		}
		method.beginControlFlow("default ->");
		method.addStatement("checkIdValidity(id)");
		method.addComment("Not reachable:");
		method.addStatement("throw new $T()", IllegalStateException.class);
		method.endControlFlow();
		method.addCode("$<};");

		classBuilder.addMethod(method.build());
	}
}
