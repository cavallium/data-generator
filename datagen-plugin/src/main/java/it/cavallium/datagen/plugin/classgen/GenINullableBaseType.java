package it.cavallium.datagen.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedVersion;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

public class GenINullableBaseType extends ClassGenerator {

	public GenINullableBaseType(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().stream().map(this::generateClass);
	}

	private GeneratedClass generateClass(ComputedVersion version) {
		var interfaceBuilder = TypeSpec.interfaceBuilder("INullableBaseType");

		interfaceBuilder.addModifiers(Modifier.PUBLIC);

		var iNullableITypeClass = ClassName.get(version.getDataNullablesPackage(basePackageName), "INullableIType");
		var baseTypeClass = ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType");
		interfaceBuilder.addSuperinterface(iNullableITypeClass);

		interfaceBuilder.addMethod(MethodSpec
				.methodBuilder("getBaseType$")
				.addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
				.returns(baseTypeClass)
				.build());

		return new GeneratedClass(version.getDataNullablesPackage(basePackageName), interfaceBuilder);
	}
}
