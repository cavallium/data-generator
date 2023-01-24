package it.cavallium.data.generator.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;
import io.soabase.recordbuilder.core.RecordBuilder;
import it.cavallium.data.generator.plugin.ClassGenerator;
import it.cavallium.data.generator.plugin.ComputedTypeBase;
import it.cavallium.data.generator.plugin.ComputedVersion;
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

		classBuilder.addModifiers(Modifier.PUBLIC);

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

		base.getData().forEach((fieldName, fieldType) -> {
			var fieldTypeName = fieldType.getJTypeName(basePackageName);

			var param = ParameterSpec
					.builder(fieldTypeName, fieldName)
					.addAnnotation(NotNull.class)
					.build();

			ofMethod.addParameter(param);
			classBuilder.addRecordComponent(param);

			var setter = MethodSpec
					.methodBuilder("set" + StringUtils.capitalize(fieldName))
					.addModifiers(Modifier.PUBLIC)
					.addParameter(ParameterSpec.builder(fieldTypeName, fieldName).addAnnotation(NotNull.class).build())
					.addAnnotation(NotNull.class)
					.returns(type);

			if (!fieldTypeName.isPrimitive()) {
				setter.addStatement("$T.requireNonNull($N)", Objects.class, fieldName);
			}
			if (fieldTypeName.isPrimitive() || !deepCheckBeforeCreatingNewEqualInstances) {
				setter.addCode("return $N == this.$N ? this : new $T(", fieldName, fieldName, type);
			} else {
				setter.addCode("return $T.equals($N, this.$N) ? this : new $T(", Objects.class, fieldName, fieldName, type);
			}
			setter.addCode(String.join(", ", base.getData().keySet()));
			setter.addStatement(")");

			classBuilder.addMethod(setter.build());
		});

		classBuilder.addMethod(MethodSpec
				.methodBuilder("getBaseType$")
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.addAnnotation(Override.class)
				.returns(baseTypeClass)
				.addStatement("return $T.$N", baseTypeClass, base.getName())
				.build());

		ofMethod.addCode("return new $T(", type);
		ofMethod.addCode(String.join(", ", base.getData().keySet()));
		ofMethod.addStatement(")");
		ofMethod.returns(type);
		if (version.isCurrent()) {
			classBuilder.addMethod(ofMethod.build());
		}

		final String stringRepresenter = base.getStringRepresenter();
		if (stringRepresenter != null && !stringRepresenter.isBlank()) {
			var toStringMethod = MethodSpec.methodBuilder("toString");
			toStringMethod.addModifiers(Modifier.PUBLIC);
			toStringMethod.addAnnotation(Override.class);
			toStringMethod.returns(String.class);
			toStringMethod.addStatement("return " + stringRepresenter + "(this)");
			classBuilder.addMethod(toStringMethod.build());
		}

		return new GeneratedClass(type.packageName(), classBuilder);
	}
}
