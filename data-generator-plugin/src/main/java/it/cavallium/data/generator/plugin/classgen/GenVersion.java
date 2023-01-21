package it.cavallium.data.generator.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import com.squareup.javapoet.TypeVariableName;
import it.cavallium.data.generator.DataSerializer;
import it.cavallium.data.generator.DataUpgrader;
import it.cavallium.data.generator.plugin.ClassGenerator;
import it.cavallium.data.generator.plugin.ComputedType.VersionedComputedType;
import it.cavallium.data.generator.plugin.ComputedTypeBase;
import it.cavallium.data.generator.plugin.ComputedTypeCustom;
import it.cavallium.data.generator.plugin.ComputedVersion;
import java.io.IOException;
import java.util.Objects;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

public class GenVersion extends ClassGenerator {

	private static final boolean STRICT_SWITCH = false;

	public GenVersion(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().stream().map(this::generateClass);
	}

	private GeneratedClass generateClass(ComputedVersion version) {
		var classBuilder = TypeSpec.classBuilder("Version");

		classBuilder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		var iVersionClassName = ClassName.get(dataModel.getRootPackage(basePackageName), "IVersion");
		var iBaseTypeClassName = ClassName.get(version.getPackage(basePackageName), "IBaseType");
		classBuilder.addSuperinterface(ParameterizedTypeName.get(iVersionClassName, iBaseTypeClassName));

		generateVersionField(version, classBuilder);

		generateInstanceField(version, classBuilder);

		generateUpgradeToNextVersion(version, classBuilder);

		generateSerializerInstance(version, classBuilder);

		generateUpgraderInstance(version, classBuilder);

		generateGetSerializer(version, classBuilder);

		generateGetVersion(version, classBuilder);

		return new GeneratedClass(version.getPackage(basePackageName), classBuilder);
	}

	/**
	 * Add a static variable for the current version
	 */
	private void generateVersionField(ComputedVersion version, Builder classBuilder) {
		var versionNumberXField = FieldSpec
				.builder(TypeName.INT, "VERSION")
				.addModifiers(Modifier.PUBLIC)
				.addModifiers(Modifier.STATIC)
				.addModifiers(Modifier.FINAL)
				.initializer("$T." + version.getVersionVarName(),
						ClassName.get(dataModel.getRootPackage(basePackageName), "Versions")
				)
				.build();
		classBuilder.addField(versionNumberXField);
	}

	/**
	 * Add a static instance for the current version
	 */
	private void generateInstanceField(ComputedVersion version, Builder classBuilder) {
		var versionClassType = ClassName.get(version.getPackage(basePackageName), "Version");
		var versionInstanceField = FieldSpec
				.builder(versionClassType, "INSTANCE")
				.addModifiers(Modifier.PUBLIC)
				.addModifiers(Modifier.STATIC)
				.addModifiers(Modifier.FINAL)
				.initializer("new $T()", versionClassType)
				.build();
		classBuilder.addField(versionInstanceField);
	}

	private void generateUpgradeToNextVersion(ComputedVersion version, Builder classBuilder) {
		var nextVersion = dataModel.getNextVersion(version).orElse(null);

		// Skip upgrade if it's the latest version
		if (nextVersion == null) {
			return;
		}

		var methodBuilder = MethodSpec.methodBuilder("upgradeToNextVersion");

		methodBuilder.addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
		methodBuilder.addException(ClassName.get(IOException.class));

		var nextIBaseType = ClassName.get(nextVersion.getPackage(basePackageName), "IBaseType");
		methodBuilder.returns(nextIBaseType);

		var iBaseTypeClassName = ClassName.get(version.getPackage(basePackageName), "IBaseType");
		methodBuilder.addParameter(iBaseTypeClassName, "oldData");

		methodBuilder.beginControlFlow( "return switch (oldData.getBaseType$$())");
		dataModel.getBaseTypesComputed(version).forEach(baseType -> {
			if (baseType.shouldUpgradeAfter(version)) {
				var nextBaseType = dataModel.getNextVersion(baseType);
				var versionType = ClassName.get(baseType.getVersion().getPackage(basePackageName), "Version");
				methodBuilder.addStatement("case $N -> ($T) $T.$NUpgraderInstance.upgrade(($T) oldData)",
						baseType.getName(),
						nextBaseType.getJTypeName(basePackageName),
						versionType,
						baseType.getName(),
						baseType.getJTypeName(basePackageName)
				);
			} else {
				if (STRICT_SWITCH) {
					methodBuilder.addStatement("case $N -> ($T) oldData",
							baseType.getName(),
							baseType.getJTypeName(basePackageName)
					);
				}
			}
		});
		if (!STRICT_SWITCH) {
			methodBuilder.addStatement("default -> ($T) oldData", nextIBaseType);
		}
		methodBuilder.addCode(CodeBlock.of("$<};"));
		classBuilder.addMethod(methodBuilder.build());
	}

	private void generateSerializerInstance(ComputedVersion version, Builder classBuilder) {
		var versionClassType = ClassName.get(version.getPackage(basePackageName), "Version");
		dataModel.getComputedTypes(version).forEach((typeName, type) -> {
			boolean shouldCreateInstanceField;
			// Check if the type matches the current version
			shouldCreateInstanceField = type instanceof VersionedComputedType versionedComputedType
					&& versionedComputedType.getVersion().equals(version);

			// Check if the type is custom, and this is the latest version
			shouldCreateInstanceField |= version.isCurrent() && type instanceof ComputedTypeCustom;

			if (!shouldCreateInstanceField) {
				return;
			}

			var serializerFieldLocation = type.getJSerializerInstance(basePackageName);
			if (!versionClassType.equals(serializerFieldLocation.className())) {
				return;
			}

			var serializerClassName = type.getJSerializerName(basePackageName);

			var fieldBuilder = FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(DataSerializer.class),
					type.getJTypeName(basePackageName)
			), serializerFieldLocation.fieldName(), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
			fieldBuilder.initializer("new $T()", serializerClassName);
			classBuilder.addField(fieldBuilder.build());
		});
	}

	private void generateUpgraderInstance(ComputedVersion version, Builder classBuilder) {
		var versionClassType = ClassName.get(version.getPackage(basePackageName), "Version");
		dataModel.getComputedTypes(version).forEach((typeName, type) -> {
			boolean shouldCreateInstanceField = type instanceof ComputedTypeBase versionedComputedType
					&& versionedComputedType.getVersion().equals(version) && !version.isCurrent();
			if (!shouldCreateInstanceField) {
				return;
			}

			var nextVersion = Objects.requireNonNull(dataModel.getNextVersion(type));

			var upgraderFieldLocation = type.getJUpgraderInstance(basePackageName);
			if (!versionClassType.equals(upgraderFieldLocation.className())) {
				return;
			}

			var genericClassName = ParameterizedTypeName.get(ClassName.get(DataUpgrader.class),
					type.getJTypeName(basePackageName), nextVersion.getJTypeName(basePackageName)
			);
			var upgraderClassName = type.getJUpgraderName(basePackageName);

			var fieldBuilder = FieldSpec.builder(genericClassName,
					upgraderFieldLocation.fieldName(),
					Modifier.PUBLIC,
					Modifier.STATIC,
					Modifier.FINAL
			);
			fieldBuilder.initializer("new $T()", upgraderClassName);
			classBuilder.addField(fieldBuilder.build());
		});
	}

	private void generateGetSerializer(ComputedVersion version, Builder classBuilder) {
		var methodBuilder = MethodSpec.methodBuilder("getSerializer");

		methodBuilder.addModifiers(Modifier.PUBLIC);
		methodBuilder.addAnnotation(Override.class);
		methodBuilder.addException(ClassName.get(IOException.class));

		var iBaseTypeClassName = ClassName.get(version.getPackage(basePackageName), "IBaseType");
		methodBuilder.addTypeVariable(TypeVariableName.get("T", iBaseTypeClassName));

		var baseTypeClassName = ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType");
		methodBuilder.addParameter(baseTypeClassName, "type");

		var returnType = ParameterizedTypeName.get(ClassName.get(DataSerializer.class), TypeVariableName.get("T"));
		methodBuilder.returns(returnType);

		methodBuilder.beginControlFlow("return ($T) switch (type)", returnType);
		dataModel.getBaseTypesComputed(version).forEach(baseType -> {
			var field = baseType.getJSerializerInstance(basePackageName);
			methodBuilder.addStatement("case $N -> $T.$N", baseType.getName(), field.className(), field.fieldName());
		});
		methodBuilder.addCode(CodeBlock.of("$<};"));
		classBuilder.addMethod(methodBuilder.build());
	}

	private void generateGetVersion(ComputedVersion version, Builder classBuilder) {
		classBuilder.addMethod(MethodSpec
				.methodBuilder("getVersion")
				.addModifiers(Modifier.PUBLIC)
				.returns(int.class)
				.addStatement("return VERSION")
				.addAnnotation(Override.class)
				.build());
	}
}
