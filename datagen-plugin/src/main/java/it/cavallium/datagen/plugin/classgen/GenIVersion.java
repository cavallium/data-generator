package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeVariableName;
import it.cavallium.datagen.DataSerializer;
import it.cavallium.datagen.plugin.ClassGenerator;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

public class GenIVersion extends ClassGenerator {

	public GenIVersion(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		var iVersionClass = TypeSpec.interfaceBuilder("IVersion");
		iVersionClass.addModifiers(Modifier.PUBLIC);
		iVersionClass.addTypeVariable(TypeVariableName.get("B"));

		// Add getSerializer method
		{
			var getSerializerMethodBuilder = MethodSpec
					.methodBuilder("getSerializer")
					.addModifiers(Modifier.PUBLIC)
					.addModifiers(Modifier.ABSTRACT)
					.addTypeVariable(TypeVariableName.get("T",
							TypeVariableName.get("B")
					))
					.returns(ParameterizedTypeName.get(ClassName.get(DataSerializer.class), TypeVariableName.get("T")))
					.addParameter(ParameterSpec
							.builder(ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType"), "type")
							.build());
			iVersionClass.addMethod(getSerializerMethodBuilder.build());
		}

		// Add getVersion method
		{
			var getVersionMethod = MethodSpec
					.methodBuilder("getVersion")
					.addModifiers(Modifier.PUBLIC)
					.addModifiers(Modifier.ABSTRACT)
					.returns(TypeName.INT)
					.build();
			iVersionClass.addMethod(getVersionMethod);
		}

		return Stream.of(new GeneratedClass(dataModel.getRootPackage(basePackageName), iVersionClass));
	}
}
