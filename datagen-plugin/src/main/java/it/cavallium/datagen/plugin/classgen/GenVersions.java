package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

public class GenVersions extends ClassGenerator {

	public GenVersions(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		var versionsClass = TypeSpec.classBuilder("Versions");
		versionsClass.addModifiers(Modifier.PUBLIC);
		versionsClass.addModifiers(Modifier.FINAL);
		var versionsInstances = FieldSpec.builder(ArrayTypeName.of(ClassName.get(dataModel.getRootPackage(basePackageName), "IVersion")),
				"VERSIONS",
				Modifier.PUBLIC,
				Modifier.STATIC,
				Modifier.FINAL
		);
		List<CodeBlock> versionsInstancesValue = new ArrayList<>();
		for (ComputedVersion version : dataModel.getVersionsSet()) {
			// Add a static variable for this version, containing the normalized version number
			var versionNumberField = FieldSpec
					.builder(TypeName.INT, version.getVersionVarName())
					.addModifiers(Modifier.PUBLIC)
					.addModifiers(Modifier.STATIC)
					.addModifiers(Modifier.FINAL)
					.initializer(version.getVersionShortInt())
					.build();
			// Add the fields to the class
			versionsClass.addField(versionNumberField);

			var versionPackage = version.getPackage(basePackageName);
			var versionClassType = ClassName.get(versionPackage, "Version");

			versionsInstancesValue.add(CodeBlock.builder().add("$T.INSTANCE", versionClassType).build());
		}
		versionsInstances.initializer(CodeBlock
				.builder()
				.add("{\n")
				.add(CodeBlock.join(versionsInstancesValue, ",\n"))
				.add("\n}")
				.build());
		versionsClass.addField(versionsInstances.build());
		return Stream.of(new GeneratedClass(dataModel.getRootPackage(basePackageName), versionsClass));
	}
}
