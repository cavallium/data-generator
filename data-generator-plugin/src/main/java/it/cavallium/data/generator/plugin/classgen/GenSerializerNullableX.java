package it.cavallium.data.generator.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import it.cavallium.data.generator.DataSerializer;
import it.cavallium.data.generator.plugin.ClassGenerator;
import it.cavallium.data.generator.plugin.ComputedTypeNullable;
import it.cavallium.data.generator.plugin.ComputedTypeNullableFixed;
import it.cavallium.data.generator.plugin.ComputedTypeNullableVersioned;
import it.cavallium.data.generator.plugin.ComputedVersion;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.NotSerializableException;
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
		var typeNullableClassName = typeNullable.getJTypeName(basePackageName);

		var classBuilder = TypeSpec.classBuilder(serializerClassName.simpleName());

		classBuilder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		classBuilder.addSuperinterface(ParameterizedTypeName.get(ClassName.get(DataSerializer.class), typeNullableClassName));

		generateSerialize(version, typeNullable, classBuilder);

		generateDeserialize(version, typeNullable, classBuilder);

		return new GeneratedClass(serializerClassName.packageName(), classBuilder);
	}

	private void generateSerialize(ComputedVersion version, ComputedTypeNullable typeNullable, Builder classBuilder) {
		var method = MethodSpec.methodBuilder("serialize");

		var base = typeNullable.getBase();
		var baseTypeName = base.getJTypeName(basePackageName);
		var baseSerializerInstance = base.getJSerializerInstance(basePackageName);

		method.addModifiers(Modifier.PUBLIC, Modifier.FINAL);
		method.addException(IOException.class);

		method.addParameter(ParameterSpec.builder(DataOutput.class, "out").build());
		method.addParameter(ParameterSpec
				.builder(typeNullable.getJTypeName(basePackageName), "data")
				.addAnnotation(NotNull.class)
				.build());

		if (version.isCurrent()) {
			method.addStatement("$T.requireNonNull(data)", Objects.class);
			method.addCode("\n");
			method.addStatement("boolean notEmpty = !data.isEmpty()");
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

	private void generateDeserialize(ComputedVersion version, ComputedTypeNullable typeNullable, Builder classBuilder) {
		var method = MethodSpec.methodBuilder("deserialize");

		var base = typeNullable.getBase();
		var baseTypeName = base.getJTypeName(basePackageName);
		var baseSerializerInstance = base.getJSerializerInstance(basePackageName);

		method.addModifiers(Modifier.PUBLIC, Modifier.FINAL);
		method.addException(IOException.class);

		var typeNullableClassName = typeNullable.getJTypeName(basePackageName);
		method.returns(typeNullableClassName);
		method.addAnnotation(NotNull.class);

		method.addParameter(ParameterSpec.builder(DataInput.class, "in").build());

		method.addStatement("return in.readBoolean() ? new $T(($T) $T.$N.deserialize(in)) : $T.empty()",
				typeNullableClassName,
				baseTypeName,
				baseSerializerInstance.className(),
				baseSerializerInstance.fieldName(),
				typeNullableClassName
		);

		classBuilder.addMethod(method.build());
	}
}
