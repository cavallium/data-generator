package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeSpec.Builder;
import it.cavallium.datagen.DataSerializer;
import it.cavallium.datagen.NotSerializableException;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedTypeBase;
import it.cavallium.datagen.plugin.ComputedVersion;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.util.Objects;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

public class GenSerializerBaseX extends ClassGenerator {

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

		classBuilder.addSuperinterface(ParameterizedTypeName.get(ClassName.get(DataSerializer.class), typeBaseClassName));

		generateSerialize(version, typeBase, classBuilder);

		generateDeserialize(version, typeBase, classBuilder);

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

			typeBase.getData().forEach((fieldName, fieldType) -> {
				if (fieldType.isPrimitive()) {
					method.addStatement("out.write$N(data.$N())", StringUtils.capitalize(fieldType.getName()), fieldName);
				} else {
					var serializerInstance = fieldType.getJSerializerInstance(basePackageName);
					method.addStatement("$T.$N.serialize(out, data.$N())",
							serializerInstance.className(),
							serializerInstance.fieldName(),
							fieldName
					);
				}
			});
		} else {
			method.addStatement("throw new $T()", NotSerializableException.class);
		}

		classBuilder.addMethod(method.build());
	}

	private void generateDeserialize(ComputedVersion version, ComputedTypeBase typeBase, Builder classBuilder) {
		var method = MethodSpec.methodBuilder("deserialize");

		method.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		ClassName typeBaseClassName = typeBase.getJTypeName(basePackageName);
		method.returns(typeBaseClassName);
		method.addAnnotation(NotNull.class);

		method.addParameter(ParameterSpec.builder(SafeDataInput.class, "in").build());

		method.addCode("return new $T(\n$>", typeBaseClassName);
		typeBase.getData().entrySet().stream().flatMap(entry -> {
			var fieldType = entry.getValue();
			if (fieldType.isPrimitive()) {
				return Stream.of(CodeBlock.of(",\n"), CodeBlock.of("in.read$N()", StringUtils.capitalize(fieldType.getName())));
			} else {
				var serializerInstance = fieldType.getJSerializerInstance(basePackageName);
				return Stream.of(CodeBlock.of(",\n"), CodeBlock.of("$T.$N.deserialize(in)",
						serializerInstance.className(),
						serializerInstance.fieldName()
				));
			}
		}).skip(1).forEach(method::addCode);
		method.addCode("$<\n");
		method.addStatement(")");

		classBuilder.addMethod(method.build());
	}
}
