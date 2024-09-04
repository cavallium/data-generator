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

	private void generateDeserialize(ComputedVersion version, ComputedTypeNullable typeNullable, Builder classBuilder) {
		var method = MethodSpec.methodBuilder("deserialize");

		var base = typeNullable.getBase();
		var baseTypeName = base.getJTypeName(basePackageName);
		var baseSerializerInstance = base.getJSerializerInstance(basePackageName);

		method.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		var typeNullableClassName = typeNullable.getJTypeName(basePackageName);
		method.returns(typeNullableClassName);
		method.addAnnotation(NotNull.class);

		method.addParameter(ParameterSpec.builder(SafeDataInput.class, "in").build());

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
