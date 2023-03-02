package it.cavallium.data.generator.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;
import it.cavallium.data.generator.plugin.ClassGenerator;
import it.cavallium.data.generator.plugin.ComputedType;
import it.cavallium.data.generator.plugin.ComputedVersion;
import it.cavallium.stream.SafeDataInput;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

public class GenCurrentVersion extends ClassGenerator {

	public GenCurrentVersion(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		var currentVersionPackage = dataModel.getCurrentVersion().getPackage(basePackageName);
		var currentVersionDataPackage = dataModel.getCurrentVersion().getDataPackage(basePackageName);

		var currentVersionClass = TypeSpec.classBuilder("CurrentVersion");
		currentVersionClass.addModifiers(Modifier.PUBLIC);
		currentVersionClass.addModifiers(Modifier.FINAL);
		// Add a static variable for the current version
		{
			var versionNumberField = FieldSpec.builder(ClassName
							.get(dataModel.getCurrentVersion().getPackage(basePackageName),
									"Version"), "VERSION").addModifiers(Modifier.PUBLIC).addModifiers(Modifier.STATIC)
					.addModifiers(Modifier.FINAL).initializer("new " + dataModel.getCurrentVersion().getPackage(basePackageName)
							+ ".Version()").build();
			currentVersionClass.addField(versionNumberField);
		}
		// Check latest version method
		{
			var isLatestVersionMethod = MethodSpec.methodBuilder("isLatestVersion").addModifiers(Modifier.PUBLIC)
					.addModifiers(Modifier.FINAL).addModifiers(Modifier.STATIC).returns(TypeName.BOOLEAN)
					.addParameter(ParameterSpec.builder(TypeName.INT, "version").build())
					.addCode("return version == VERSION.getVersion();").build();
			currentVersionClass.addMethod(isLatestVersionMethod);
		}
		// Get super type classes method and static field
		{
			var returnType = ParameterizedTypeName.get(ClassName.get(Set.class),
					ParameterizedTypeName.get(ClassName.get(Class.class),
							WildcardTypeName.subtypeOf(ClassName.get(currentVersionPackage, "IType"))));
			var superTypesField = FieldSpec.builder(returnType, "SUPER_TYPE_CLASSES", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
			var getSuperTypeClasses = MethodSpec.methodBuilder("getSuperTypeClasses").addModifiers(Modifier.PUBLIC)
					.addModifiers(Modifier.FINAL).addModifiers(Modifier.STATIC)
					.returns(returnType);

			var superTypesInitializerField = CodeBlock.builder();
			superTypesInitializerField.add("$T.of(\n", Set.class);
			AtomicBoolean isFirst = new AtomicBoolean(true);
			dataModel.getSuperTypesComputed(dataModel.getCurrentVersion()).forEach(superType -> {
				if (!isFirst.getAndSet(false)) {
					superTypesInitializerField.add(",\n");
				}
				superTypesInitializerField.add("$T.class",
						ClassName.get(dataModel.getVersion(superType).getDataPackage(basePackageName), superType.getName())
				);
			});
			superTypesInitializerField.add("\n);");
			superTypesField.initializer(superTypesInitializerField.build());
			getSuperTypeClasses.addStatement("return SUPER_TYPE_CLASSES");
			currentVersionClass.addField(superTypesField.build());
			currentVersionClass.addMethod(getSuperTypeClasses.build());
		}
		// Get super type subtypes classes method
		{
			var getSuperTypeSubtypesClasses = MethodSpec.methodBuilder("getSuperTypeSubtypesClasses").addModifiers(Modifier.PUBLIC)
					.addModifiers(Modifier.FINAL).addModifiers(Modifier.STATIC)
					.returns(ParameterizedTypeName.get(ClassName.get(Set.class),
							ParameterizedTypeName.get(ClassName.get(Class.class),
									WildcardTypeName.subtypeOf(ClassName.get(currentVersionPackage, "IBaseType")))));
			getSuperTypeSubtypesClasses
					.addParameter(ParameterSpec.builder(ParameterizedTypeName.get(ClassName.get(Class.class),
							WildcardTypeName.subtypeOf(ClassName.get(currentVersionPackage, "IType"))
					), "superTypeClass").build());
			getSuperTypeSubtypesClasses.beginControlFlow("return switch (superTypeClass.getCanonicalName())");
			dataModel.getSuperTypesComputed(dataModel.getCurrentVersion()).forEach(superType -> {
				getSuperTypeSubtypesClasses.addCode("case \"" + ClassName
						.get(currentVersionDataPackage, superType.getName())
						.canonicalName() + "\" -> $T.of(\n", Set.class);
				getSuperTypeSubtypesClasses.addCode("$>");
				AtomicBoolean isFirst = new AtomicBoolean(true);
				for (ComputedType subType : superType.subTypes()) {
					if (!isFirst.getAndSet(false)) {
						getSuperTypeSubtypesClasses.addCode(",\n");
					}
					getSuperTypeSubtypesClasses.addCode("$T.class",
							ClassName.get(currentVersionDataPackage, subType.getName())
					);
				}
				getSuperTypeSubtypesClasses.addCode("$<");
				getSuperTypeSubtypesClasses.addCode("\n);\n");
			});
			getSuperTypeSubtypesClasses.addStatement("default -> throw new $T()", IllegalArgumentException.class);
			getSuperTypeSubtypesClasses.addCode(CodeBlock.of("$<};"));
			currentVersionClass.addMethod(getSuperTypeSubtypesClasses.build());
		}
		// UpgradeDataToLatestVersion1 Method
		{
			var upgradeDataToLatestVersion1MethodBuilder = MethodSpec.methodBuilder("upgradeDataToLatestVersion")
					.addTypeVariable(TypeVariableName.get("U", ClassName.get(currentVersionPackage, "IBaseType")))
					.addModifiers(Modifier.PUBLIC).addModifiers(Modifier.STATIC).addModifiers(Modifier.FINAL).returns(TypeVariableName.get("U"))
					.addParameter(ParameterSpec.builder(TypeName.INT, "oldVersion").build()).addParameter(
							ParameterSpec.builder(ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType"), "type").build())
					.addParameter(ParameterSpec.builder(SafeDataInput.class, "oldDataInput").build())
					.beginControlFlow("return upgradeDataToLatestVersion(oldVersion, switch (oldVersion)");
			for (var versionConfiguration : dataModel.getVersionsSet()) {
// Add a case in which the data version deserializes the serialized data and upgrades it
				var versions = ClassName.get(dataModel.getRootPackage(basePackageName), "Versions");
				upgradeDataToLatestVersion1MethodBuilder.addStatement("case $T.$N -> $T.INSTANCE.getSerializer(type).deserialize(oldDataInput)",
						versions,
						versionConfiguration.getVersionVarName(),
						ClassName.get(versionConfiguration.getPackage(basePackageName), "Version")
				);
			}
			var upgradeDataToLatestVersion1Method = upgradeDataToLatestVersion1MethodBuilder
					.addStatement("default -> throw new $T(\"Unknown version: \" + oldVersion)", UnsupportedOperationException.class)
					.addCode(CodeBlock.of("$<});"))
					.build();
			currentVersionClass.addMethod(upgradeDataToLatestVersion1Method);
		}
		// UpgradeDataToLatestVersion2 Method
		{
			var versionsClassName = ClassName.get(dataModel.getRootPackage(basePackageName), "Versions");
			var upgradeDataToLatestVersion2MethodBuilder = MethodSpec.methodBuilder("upgradeDataToLatestVersion")
					.addModifiers(Modifier.PUBLIC).addModifiers(Modifier.STATIC).addModifiers(Modifier.FINAL).addTypeVariable(TypeVariableName.get("T"))
					.addTypeVariable(TypeVariableName.get("U", ClassName.get(currentVersionPackage, "IBaseType")))
					.returns(TypeVariableName.get("U"))
					.addParameter(ParameterSpec.builder(TypeName.INT, "oldVersion").build())
					.addParameter(ParameterSpec.builder(TypeVariableName.get("T"), "oldData").build())
					.addStatement("$T data = oldData", Object.class);
			upgradeDataToLatestVersion2MethodBuilder.beginControlFlow("switch (oldVersion)");
			for (var versionConfiguration : dataModel.getVersionsSet()) {
// Add a case in which the data version deserializes the serialized data and upgrades it
				upgradeDataToLatestVersion2MethodBuilder.addCode("case $T.$N: ",
						versionsClassName,
						versionConfiguration.getVersionVarName()
				);
				if (versionConfiguration.isCurrent()) {
					// This is the latest version, don't upgrade.
					upgradeDataToLatestVersion2MethodBuilder.addStatement("return ($T) data", TypeVariableName.get("U"));
				} else {
					// Upgrade
					ComputedVersion computedVersion = dataModel.getNextVersionOrThrow(versionConfiguration);
					upgradeDataToLatestVersion2MethodBuilder
							.addStatement(
									"data = " + versionConfiguration.getPackage(basePackageName)
											+ ".Version.upgradeToNextVersion(($T) data)",
									ClassName.get(versionConfiguration.getPackage(basePackageName), "IBaseType")
							);
				}
			}
			upgradeDataToLatestVersion2MethodBuilder.addStatement("default: throw new $T(\"Unknown version: \" + oldVersion)", UnsupportedOperationException.class);
			upgradeDataToLatestVersion2MethodBuilder.endControlFlow();
			currentVersionClass.addMethod(upgradeDataToLatestVersion2MethodBuilder.build());
		}

		generateGetClass(dataModel.getCurrentVersion(), currentVersionClass);

		return Stream.of(new GeneratedClass(dataModel.getCurrentVersion().getPackage(basePackageName), currentVersionClass));
	}

	private void generateGetClass(ComputedVersion version, Builder classBuilder) {
		var methodBuilder = MethodSpec.methodBuilder("getClass");

		methodBuilder.addModifiers(Modifier.PUBLIC, Modifier.STATIC);

		var baseTypeClassName = ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType");
		methodBuilder.addParameter(baseTypeClassName, "type");

		var iBaseTypeClassName = ClassName.get(version.getPackage(basePackageName), "IBaseType");
		methodBuilder.returns(ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(iBaseTypeClassName)));

		methodBuilder.beginControlFlow("return switch (type)");
		dataModel.getBaseTypesComputed(version).forEach(baseType -> {
			methodBuilder.addStatement("case $N -> $T.class", baseType.getName(), baseType.getJTypeName(basePackageName));
		});
		methodBuilder.addCode(CodeBlock.of("$<};"));
		classBuilder.addMethod(methodBuilder.build());
	}
}
