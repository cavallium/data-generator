package it.cavallium.datagen.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedVersion;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

public class GenINullableSuperType extends ClassGenerator {

	public GenINullableSuperType(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().stream().map(this::generateClass);
	}

	private GeneratedClass generateClass(ComputedVersion version) {
		var interfaceBuilder = TypeSpec.interfaceBuilder("INullableSuperType");

		interfaceBuilder.addModifiers(Modifier.PUBLIC);

		var iNullableITypeClass = ClassName.get(version.getDataNullablesPackage(basePackageName), "INullableIType");
		var superTypeClass = ClassName.get(dataModel.getRootPackage(basePackageName), "SuperType");
		interfaceBuilder.addSuperinterface(iNullableITypeClass);

		interfaceBuilder.addMethod(MethodSpec
				.methodBuilder("getSuperType$")
				.addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
				.returns(superTypeClass)
				.build());

		return new GeneratedClass(version.getDataNullablesPackage(basePackageName), interfaceBuilder);
	}
}
