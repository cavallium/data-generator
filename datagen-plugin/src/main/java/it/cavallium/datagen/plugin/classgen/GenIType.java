package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.TypeSpec;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedVersion;
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

		return new GeneratedClass(version.getPackage(basePackageName), interfaceBuilder);
	}
}
