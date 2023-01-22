package it.cavallium.data.generator.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import it.cavallium.data.generator.DataSerializer;
import it.cavallium.data.generator.plugin.ClassGenerator;
import it.cavallium.data.generator.plugin.ComputedTypeBase;
import it.cavallium.data.generator.plugin.ComputedVersion;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.NotSerializableException;
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
		method.addException(IOException.class);

		method.addParameter(ParameterSpec.builder(DataOutput.class, "out").build());
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
		method.addException(IOException.class);

		ClassName typeBaseClassName = typeBase.getJTypeName(basePackageName);
		method.returns(typeBaseClassName);
		method.addAnnotation(NotNull.class);

		method.addParameter(ParameterSpec.builder(DataInput.class, "in").build());

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
