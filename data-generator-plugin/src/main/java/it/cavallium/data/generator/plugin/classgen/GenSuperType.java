package it.cavallium.data.generator.plugin.classgen;

import com.squareup.javapoet.TypeSpec;
import it.cavallium.data.generator.plugin.ClassGenerator;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

public class GenSuperType extends ClassGenerator {

	public GenSuperType(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		var superTypeClass = TypeSpec.enumBuilder("SuperType");
		superTypeClass.addModifiers(Modifier.PUBLIC);
		dataModel.getSuperTypesComputed().forEach(superType -> superTypeClass.addEnumConstant(superType.getName()));
		return Stream.of(new GeneratedClass(dataModel.getRootPackage(basePackageName), superTypeClass));
	}
}
