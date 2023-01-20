package it.cavallium.data.generator.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;
import it.cavallium.data.generator.plugin.ClassGenerator;
import it.cavallium.data.generator.plugin.ComputedTypeSuper;
import it.cavallium.data.generator.plugin.ComputedVersion;
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

		var baseTypeClass = ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType");
		var iBaseTypeClass = ClassName.get(version.getPackage(basePackageName), "IBaseType");
		classBuilder.addSuperinterface(iBaseTypeClass);

		dataModel.getExtendsInterfaces(typeSuper).forEach(superType -> {
			classBuilder.addSuperinterface(superType.getJTypeName(basePackageName));
		});

		Stream
				.concat(dataModel.getCommonInterfaceData(typeSuper), dataModel.getCommonInterfaceGetters(typeSuper))
				.forEach(superType -> {
					var returnType = superType.getValue().getJTypeName(basePackageName);
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
			var returnType = superType.getValue().getJTypeName(basePackageName);

			var setter = MethodSpec
					.methodBuilder("set" + StringUtils.capitalize(superType.getKey()))
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.addParameter(ParameterSpec.builder(returnType, "value").addAnnotation(NotNull.class).build())
					.addAnnotation(NotNull.class)
					.returns(type);
			classBuilder.addMethod(setter.build());
		});

		dataModel.getSuperTypesOf(typeSuper).forEach(superType -> {
			classBuilder.addSuperinterface(superType.getJTypeName(basePackageName));
		});

		classBuilder.addMethod(MethodSpec
				.methodBuilder("getMetaId$" + typeSuper.getName())
				.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
				.returns(int.class)
				.build());

		return new GeneratedClass(type.packageName(), classBuilder);
	}
}
