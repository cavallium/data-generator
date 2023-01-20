package it.cavallium.data.generator.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import it.cavallium.data.generator.plugin.ClassGenerator;
import it.cavallium.data.generator.plugin.ComputedVersion;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

public class GenIBaseType extends ClassGenerator {

	public GenIBaseType(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().stream().map(this::generateClass);
	}

	private GeneratedClass generateClass(ComputedVersion version) {
		var interfaceBuilder = TypeSpec.interfaceBuilder("IBaseType");

		interfaceBuilder.addModifiers(Modifier.PUBLIC);

		var iTypeClassName = ClassName.get(version.getDataPackage(basePackageName), "IType");
		var baseTypeClassName = ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType");
		interfaceBuilder.addSuperinterface(iTypeClassName);

		interfaceBuilder.addMethod(MethodSpec
				.methodBuilder("getBaseType$")
				.addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
				.returns(baseTypeClassName)
				.build());

		return new GeneratedClass(version.getPackage(basePackageName), interfaceBuilder);
	}
}
