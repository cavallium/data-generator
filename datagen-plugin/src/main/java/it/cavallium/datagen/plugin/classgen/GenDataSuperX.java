package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.TypeSpec;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedType;
import it.cavallium.datagen.plugin.ComputedType.BuildableComputedType;
import it.cavallium.datagen.plugin.ComputedTypeSuper;
import it.cavallium.datagen.plugin.ComputedVersion;
import java.util.List;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

public class GenDataSuperX extends ClassGenerator {

	public GenDataSuperX(ClassGeneratorParams params) {
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
		var type = (ClassName) typeSuper.getJTypeName(basePackageName);
		var classBuilder = TypeSpec.interfaceBuilder(type.simpleName());
		classBuilder.addModifiers(Modifier.PUBLIC);

		var builderClassBuilder = TypeSpec.interfaceBuilder("Builder");
		builderClassBuilder.addModifiers(Modifier.PUBLIC, Modifier.STATIC);

		if (version.isCurrent()) {
			Stream<ComputedTypeSuper> superTypesThatExtendThisSuperType = dataModel.getSuperTypesComputed(version)
					.filter(computedTypeSuper -> dataModel.getExtendsInterfaces(computedTypeSuper).anyMatch(typeSuper::equals));
			Stream<ComputedType> subTypes = typeSuper.subTypes().stream();
			List<ComputedType> permittedSubclasses = Stream.concat(superTypesThatExtendThisSuperType, subTypes)
					.distinct()
					.toList();
			var permittedBuilderSubclasses = permittedSubclasses.stream()
					.<ClassName>mapMulti((computedType, consumer) -> {
						if (computedType instanceof BuildableComputedType buildableComputedType
								&& buildableComputedType.getVersion().isCurrent()) {
							consumer.accept(buildableComputedType.getJBuilderName(basePackageName));
						}
					})
					.toList();
			if (!permittedSubclasses.isEmpty()) {
				classBuilder.addModifiers(Modifier.SEALED);
			}
			if (!permittedBuilderSubclasses.isEmpty()) {
				builderClassBuilder.addModifiers(Modifier.SEALED);
			}
			classBuilder.addPermittedSubclasses(permittedSubclasses.stream()
							.map(computedType -> computedType.getJTypeName(basePackageName))
							.toList());
			builderClassBuilder.addPermittedSubclasses(permittedBuilderSubclasses);
		}

		dataModel.getTypeSameVersions(typeSuper).forEach(v -> {
			var iTypeClass = ClassName.get(v.getPackage(basePackageName), "IBaseType");
			classBuilder.addSuperinterface(iTypeClass);
		});

		Stream
				.concat(dataModel.getSuperTypesOf(typeSuper, true), dataModel.getExtendsInterfaces(typeSuper))
				.distinct()
				.forEach(superType -> {
					classBuilder.addSuperinterface(superType.getJTypeName(basePackageName));
					builderClassBuilder.addSuperinterface(superType.getJBuilderName(basePackageName));
				});

		Stream
				.concat(dataModel.getCommonInterfaceData(typeSuper), dataModel.getCommonInterfaceGetters(typeSuper))
				.forEach(superType -> {
					var returnType = superType.getValue().getJTypeNameGeneric(basePackageName);
					var getter = MethodSpec
							.methodBuilder(superType.getKey())
							.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
							.returns(returnType);
					if (!returnType.isPrimitive()) {
						getter.addAnnotation(NotNull.class);
					}
					classBuilder.addMethod(getter.build());
				});

		dataModel.getCommonInterfaceData(typeSuper).forEach(superType -> {
			var returnType = superType.getValue().getJTypeNameGeneric(basePackageName);

			var setter = MethodSpec
					.methodBuilder("set" + StringUtils.capitalize(superType.getKey()))
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.addParameter(ParameterSpec.builder(returnType, "value").addAnnotation(NotNull.class).build())
					.addAnnotation(NotNull.class)
					.returns(type);
			classBuilder.addMethod(setter.build());
		});

		classBuilder.addMethod(MethodSpec
				.methodBuilder("getMetaId$" + typeSuper.getName())
				.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
				.returns(int.class)
				.build());


		var buildMethod = MethodSpec
				.methodBuilder("build")
				.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
				.addAnnotation(NotNull.class)
				.returns(type);
		builderClassBuilder.addMethod(buildMethod.build());

		var builderMethod = MethodSpec
				.methodBuilder("builder")
				.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
				.addAnnotation(NotNull.class)
				.returns(typeSuper.getJBuilderName(basePackageName));
		if (version.isCurrent()) {
			classBuilder.addMethod(builderMethod.build());
			classBuilder.addType(builderClassBuilder.build());
		}

		return new GeneratedClass(type.packageName(), classBuilder);
	}
}
