package it.cavallium.datagen.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import it.cavallium.datagen.DataUpgrader;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedType;
import it.cavallium.datagen.plugin.ComputedType.VersionedComputedType;
import it.cavallium.datagen.plugin.ComputedTypeSuper;
import it.cavallium.datagen.plugin.ComputedVersion;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.jetbrains.annotations.NotNull;

public class GenUpgraderSuperX extends ClassGenerator {

	public GenUpgraderSuperX(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().parallelStream().flatMap(this::generateVersionClasses);
	}

	private Stream<GeneratedClass> generateVersionClasses(ComputedVersion version) {
		return dataModel
				.getSuperTypesComputed(version)
				.filter(type -> !type.getVersion().isCurrent() && type.getVersion().equals(version))
				.map(type -> generateTypeVersioned(version, type));
	}

	private GeneratedClass generateTypeVersioned(ComputedVersion version, ComputedTypeSuper typeSuper) {
		ClassName upgraderClassName = typeSuper.getJUpgraderName(basePackageName);
		ClassName typeBaseClassName = typeSuper.getJTypeName(basePackageName);
		ComputedTypeSuper nextTypeSuper = dataModel.getNextVersion(typeSuper);

		var classBuilder = TypeSpec.classBuilder(upgraderClassName.simpleName());

		classBuilder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		classBuilder.addSuperinterface(ParameterizedTypeName.get(ClassName.get(DataUpgrader.class),
				typeBaseClassName,
				nextTypeSuper.getJTypeName(basePackageName)
		));

		generateUpgradeMethod(version, typeSuper, nextTypeSuper, classBuilder);

		return new GeneratedClass(upgraderClassName.packageName(), classBuilder);
	}

	private void generateUpgradeMethod(ComputedVersion version, ComputedTypeSuper typeSuper,
			ComputedTypeSuper nextTypeSuper,
			Builder classBuilder) {
		var method = MethodSpec.methodBuilder("upgrade");

		method.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		ClassName typeSuperClassName = typeSuper.getJTypeName(basePackageName);
		ClassName nextTypeSuperClassName = nextTypeSuper.getJTypeName(basePackageName);
		method.returns(nextTypeSuperClassName);
		method.addAnnotation(NotNull.class);

		method.addParameter(ParameterSpec.builder(typeSuperClassName, "data").addAnnotation(NotNull.class).build());

		method.beginControlFlow("return switch (data.getMetaId$$$N())", typeSuper.getName());
		int i = 0;
		for (ComputedType subType : typeSuper.subTypes()) {
			method.addCode("case $L -> ", i);
			method.addStatement(upgradeSubTypeToType(subType, CodeBlock.of("($T) data", subType.getJTypeName(basePackageName)), nextTypeSuper));
			i++;
		}
		method.addStatement("default -> throw new $T(data.getMetaId$$$N())", IndexOutOfBoundsException.class, typeSuper.getName());
		method.addCode("$<};\n");

		classBuilder.addMethod(method.build());
	}

	private CodeBlock upgradeSubTypeToType(ComputedType subType,
			CodeBlock codeBlock,
			ComputedTypeSuper nextTypeSuper) {
		while (subType instanceof VersionedComputedType versionedComputedType
				&& versionedComputedType.getVersion().compareTo(nextTypeSuper.getVersion()) < 0) {
			var currentFieldType = subType;
			var nextFieldType = dataModel.getNextVersion(currentFieldType);
			codeBlock = currentFieldType.wrapWithUpgrade(basePackageName, codeBlock, nextFieldType);
			subType = nextFieldType;
		}
		return codeBlock;
	}
}
