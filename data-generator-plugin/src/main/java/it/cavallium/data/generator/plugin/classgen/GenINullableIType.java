package it.cavallium.data.generator.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeSpec;
import it.cavallium.data.generator.nativedata.INullable;
import it.cavallium.data.generator.plugin.ClassGenerator;
import it.cavallium.data.generator.plugin.ComputedVersion;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

public class GenINullableIType extends ClassGenerator {

	public GenINullableIType(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().stream().map(this::generateClass);
	}

	private GeneratedClass generateClass(ComputedVersion version) {
		var interfaceBuilder = TypeSpec.interfaceBuilder("INullableIType");

		interfaceBuilder.addModifiers(Modifier.PUBLIC);

		var iTypeClass = ClassName.get(version.getPackage(basePackageName), "IType");
		var iSuperNullableClass = ClassName.get(INullable.class);
		interfaceBuilder.addSuperinterface(iTypeClass);
		interfaceBuilder.addSuperinterface(iSuperNullableClass);

		return new GeneratedClass(version.getDataNullablesPackage(basePackageName), interfaceBuilder);
	}
}
