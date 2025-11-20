package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.TypeSpec;
import io.soabase.recordbuilder.core.RecordBuilder;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedTypeBase;
import it.cavallium.datagen.plugin.ComputedVersion;
import java.util.Objects;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

public class GenDataBaseX extends ClassGenerator {

	public GenDataBaseX(ClassGeneratorParams params) {
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

	private GeneratedClass generateTypeVersioned(ComputedVersion version, ComputedTypeBase base) {
		var type = (ClassName) base.getJTypeName(basePackageName);
		var classBuilder = TypeSpec.recordBuilder(type.simpleName());
		var classConstructorBuilder = MethodSpec.constructorBuilder();
		classBuilder.addModifiers(Modifier.PUBLIC);

		var builderClassBuilder = TypeSpec.classBuilder(base.getJBuilderName(basePackageName));
		builderClassBuilder.addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
		var builderPrivateConstructorBuilder = MethodSpec.constructorBuilder()
				.addModifiers(Modifier.PRIVATE)
				.addParameter(ParameterSpec.builder(type, "source").build());

		if (useRecordBuilders && base.getVersion().isCurrent()) {
			classBuilder.addAnnotation(RecordBuilder.class);
		}

		var baseTypeClass = ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType");

		dataModel.getTypeSameVersions(base).forEach(v -> {
			var iTypeClass = ClassName.get(v.getPackage(basePackageName), "IBaseType");
			classBuilder.addSuperinterface(iTypeClass);
		});

		dataModel.getSuperTypesOf(base, true).forEach(superType -> {
			classBuilder.addSuperinterface(superType.getJTypeName(basePackageName));
			if (superType.getVersion().isCurrent()) {
				builderClassBuilder.addSuperinterface(superType.getJBuilderName(basePackageName));
			}
		});

		dataModel.getSuperTypesOf(base, false).forEach(superType -> {
			classBuilder.addMethod(MethodSpec
					.methodBuilder("getMetaId$" + superType.getName())
					.addModifiers(Modifier.PUBLIC)
					.addAnnotation(Override.class)
					.returns(int.class)
					.addStatement("return " + superType.subTypes().indexOf(base))
					.build());
		});

		var ofMethod = MethodSpec
				.methodBuilder("of")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC);

		var builderMethod = MethodSpec
				.methodBuilder("builder")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(NotNull.class)
				.returns(base.getJBuilderName(basePackageName));

		var buildMethod = MethodSpec
				.methodBuilder("build")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(NotNull.class);

		var buildIfChangedMethod = MethodSpec
				.methodBuilder("buildIfChanged")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(NotNull.class)
				.addParameter(type, "original");

		var builderToStringMethod = MethodSpec
				.methodBuilder("toString")
				.addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC)
				.returns(String.class);
		builderToStringMethod.addCode("return \"$N.Builder[", base.getName());

		buildIfChangedMethod.addCode("return ");

		boolean[] isFirstParameter = new boolean[] {true};

		base.getData().forEach((fieldName, fieldType) -> {
			var fieldTypeName = fieldType.getJTypeNameGeneric(basePackageName);

			var param = ParameterSpec
					.builder(fieldTypeName, fieldName)
					.addAnnotation(NotNull.class)
					.build();

			ofMethod.addParameter(param);
			classConstructorBuilder.addParameter(param);
			builderClassBuilder.addField(FieldSpec.builder(fieldTypeName, fieldName, Modifier.PRIVATE).build());
			builderPrivateConstructorBuilder.addStatement("this.$N = source.$N", fieldName, fieldName);
			if (isFirstParameter[0]) {
				isFirstParameter[0] = false;
			} else {
				builderToStringMethod.addCode(", ");
				buildIfChangedMethod.addCode(" && ");
			}
			builderToStringMethod.addCode("$N=\" + $N + \"", fieldName, fieldName);

			var setter = MethodSpec
					.methodBuilder("set" + StringUtils.capitalize(fieldName))
					.addModifiers(Modifier.PUBLIC)
					.addParameter(ParameterSpec.builder(fieldTypeName, fieldName).addAnnotation(NotNull.class).build())
					.addAnnotation(NotNull.class)
					.returns(type);
			var builderSetter = setter.build().toBuilder();
			builderSetter.returns(ClassName.get(type.packageName(), type.simpleName(), "Builder"));

			if (!fieldTypeName.isPrimitive()) {
				setter.addStatement("$T.requireNonNull($N)", Objects.class, fieldName);
				builderSetter.addStatement("$T.requireNonNull($N)", Objects.class, fieldName);
				buildMethod.addStatement("$T.requireNonNull($N)", Objects.class, fieldName);
			}
			setter.addCode("return ");
			buildIfChangedMethod.addCode(getCompareFunctionCodeBlock(fieldTypeName.isPrimitive(), deepCheckBeforeCreatingNewEqualInstances, fieldName, "original"));
			setter.addCode(getCompareFunctionCodeBlock(fieldTypeName.isPrimitive(), deepCheckBeforeCreatingNewEqualInstances, fieldName, "this"));
			setter.addCode(" ? this : new $T(", type);
			setter.addCode(String.join(", ", base.getData().keySet()));
			setter.addStatement(")");

			builderSetter.addStatement("this.$N = $N", fieldName, fieldName);
			builderSetter.addStatement("return this");

			classBuilder.addMethod(setter.build());
			builderClassBuilder.addMethod(builderSetter.build());
		});

		if (isFirstParameter[0]) {
			buildIfChangedMethod.addStatement("original");
		} else {
			buildIfChangedMethod.addStatement(" ? original : this.build()");
		}

		classBuilder.recordConstructor(classConstructorBuilder.build());

		builderClassBuilder.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).build());
		builderClassBuilder.addMethod(builderPrivateConstructorBuilder.build());

		classBuilder.addMethod(MethodSpec
				.methodBuilder("getBaseType$")
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.addAnnotation(Override.class)
				.returns(baseTypeClass)
				.addStatement("return $T.$N", baseTypeClass, base.getName())
				.build());

		ofMethod.addCode("return new $T(", type);
		buildMethod.addCode("return new $T(", type);
		ofMethod.addCode(String.join(", ", base.getData().keySet()));
		buildMethod.addCode(String.join(", ", base.getData().keySet()));
		ofMethod.addStatement(")");
		buildMethod.addStatement(")");
		ofMethod.returns(type);
		buildMethod.returns(type);
		buildIfChangedMethod.returns(type);
		if (version.isCurrent()) {
			classBuilder.addMethod(ofMethod.build());
		}

		builderMethod.addStatement("return new $T(this)", base.getJBuilderName(basePackageName));

		builderClassBuilder.addMethod(buildMethod.build());
		builderClassBuilder.addMethod(buildIfChangedMethod.build());

		builderToStringMethod.addStatement("]\"");
		builderClassBuilder.addMethod(builderToStringMethod.build());

		final String stringRepresenter = base.getStringRepresenter();
		if (version.isCurrent() && stringRepresenter != null && !stringRepresenter.isBlank()) {
			var toStringMethod = MethodSpec.methodBuilder("toString");
			toStringMethod.addModifiers(Modifier.PUBLIC);
			toStringMethod.addAnnotation(Override.class);
			toStringMethod.returns(String.class);
			toStringMethod.addStatement("return " + stringRepresenter + "(this)");
			classBuilder.addMethod(toStringMethod.build());
		}

		if (version.isCurrent()) {
			classBuilder.addMethod(builderMethod.build());
			classBuilder.addType(builderClassBuilder.build());
		}

		return new GeneratedClass(type.packageName(), classBuilder);
	}

	private CodeBlock getCompareFunctionCodeBlock(boolean primitive,
			boolean deepCheckBeforeCreatingNewEqualInstances,
			String fieldName,
			String otherClassFieldName) {
		if (primitive || !deepCheckBeforeCreatingNewEqualInstances) {
			return CodeBlock.builder().add("$N == $L.$N", fieldName, otherClassFieldName, fieldName).build();
		} else {
			return CodeBlock.builder().add("$T.equals($N, $L.$N)", Objects.class, fieldName, otherClassFieldName, fieldName).build();
		}
	}
}
