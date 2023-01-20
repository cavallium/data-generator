package it.cavallium.data.generator.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeSpec;
import it.cavallium.data.generator.plugin.ClassGenerator;
import it.cavallium.data.generator.plugin.ComputedVersion;
import java.io.Serializable;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

public class GenIType extends ClassGenerator {

	public GenIType(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().stream().map(this::generateClass);
	}

	private GeneratedClass generateClass(ComputedVersion version) {
		var interfaceBuilder = TypeSpec.interfaceBuilder("IType");

		interfaceBuilder.addModifiers(Modifier.PUBLIC);

		interfaceBuilder.addSuperinterface(ClassName.get(Serializable.class));

		return new GeneratedClass(version.getPackage(basePackageName), interfaceBuilder);
	}
}
