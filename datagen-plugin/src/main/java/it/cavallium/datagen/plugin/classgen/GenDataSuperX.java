package it.cavallium.datagen.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedTypeSuper;
import it.cavallium.datagen.plugin.ComputedVersion;
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

		if (version.isCurrent()) {
			classBuilder.addModifiers(Modifier.SEALED);
			Stream<TypeName> superTypesThatExtendThisSuperType = dataModel.getSuperTypesComputed(version)
					.filter(computedTypeSuper -> dataModel.getExtendsInterfaces(computedTypeSuper).anyMatch(typeSuper::equals))
					.map(computedTypeSuper -> computedTypeSuper.getJTypeName(basePackageName));
			Stream<TypeName> subTypes = typeSuper.subTypes().stream()
					.map(subType -> subType.getJTypeName(basePackageName));
			Stream<TypeName> permittedSubclasses = Stream.concat(superTypesThatExtendThisSuperType, subTypes).distinct();
			classBuilder.addPermittedSubclasses(permittedSubclasses.toList());
		}

		dataModel.getTypeSameVersions(typeSuper).forEach(v -> {
			var iTypeClass = ClassName.get(v.getPackage(basePackageName), "IBaseType");
			classBuilder.addSuperinterface(iTypeClass);
		});

		Stream
				.concat(dataModel.getSuperTypesOf(typeSuper, true), dataModel.getExtendsInterfaces(typeSuper))
				.distinct()
				.forEach(superType -> classBuilder.addSuperinterface(superType.getJTypeName(basePackageName)));

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

		return new GeneratedClass(type.packageName(), classBuilder);
	}
}
