package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.TypeSpec;
import it.cavallium.datagen.plugin.ClassGenerator;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

public class GenBaseType extends ClassGenerator {

	public GenBaseType(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		var baseTypeClass = TypeSpec.enumBuilder("BaseType");
		baseTypeClass.addModifiers(Modifier.PUBLIC);
		dataModel.getBaseTypesComputed().forEach(baseType -> baseTypeClass.addEnumConstant(baseType.getName()));
		return Stream.of(new GeneratedClass(dataModel.getRootPackage(basePackageName), baseTypeClass));
	}
}
