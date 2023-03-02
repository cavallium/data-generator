package it.cavallium.data.generator.plugin.classgen;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import it.cavallium.data.generator.DataSerializer;
import it.cavallium.data.generator.nativedata.ImmutableWrappedArrayList;
import it.cavallium.data.generator.plugin.ClassGenerator;
import it.cavallium.data.generator.plugin.ComputedTypeArray;
import it.cavallium.data.generator.plugin.ComputedTypeArrayFixed;
import it.cavallium.data.generator.plugin.ComputedTypeArrayVersioned;
import it.cavallium.data.generator.plugin.ComputedVersion;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.NotSerializableException;
import java.util.Objects;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.jetbrains.annotations.NotNull;

public class GenSerializerArrayX extends ClassGenerator {

	public GenSerializerArrayX(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().parallelStream().flatMap(this::generateVersionClasses);
	}

	private Stream<GeneratedClass> generateVersionClasses(ComputedVersion version) {
		return dataModel
				.getArrayTypesComputed(version)
				.filter(type -> (type instanceof ComputedTypeArrayVersioned versioned
						&& versioned.getVersion().equals(version)) || type instanceof ComputedTypeArrayFixed)
				.map(type -> generateTypeVersioned(version, type));
	}

	private GeneratedClass generateTypeVersioned(ComputedVersion version, ComputedTypeArray typeArray) {
		ClassName serializerClassName = typeArray.getJSerializerName(basePackageName);
		var typeArrayClassName = typeArray.getJTypeName(basePackageName);

		var classBuilder = TypeSpec.classBuilder(serializerClassName.simpleName());

		classBuilder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		classBuilder.addSuperinterface(ParameterizedTypeName.get(ClassName.get(DataSerializer.class), typeArrayClassName));

		generateSerialize(version, typeArray, classBuilder);

		generateDeserialize(version, typeArray, classBuilder);

		return new GeneratedClass(serializerClassName.packageName(), classBuilder);
	}

	private void generateSerialize(ComputedVersion version, ComputedTypeArray typeArray, Builder classBuilder) {
		var method = MethodSpec.methodBuilder("serialize");

		method.addModifiers(Modifier.PUBLIC, Modifier.FINAL);
		method.addException(IOException.class);

		method.addParameter(ParameterSpec.builder(DataOutput.class, "out").build());
		method.addParameter(ParameterSpec
				.builder(typeArray.getJTypeName(basePackageName), "data")
				.addAnnotation(NotNull.class)
				.build());

		if (generateOldSerializers || version.isCurrent()) {
			method.addStatement("$T.requireNonNull(data)", Objects.class);
			method.addCode("\n");
			method.addStatement("final int sz = data.size()");
			method.addStatement("out.writeInt(sz)");
			method.addCode("\n");
			method.beginControlFlow("for (var item : data)");
			var baseSerializerInstance = typeArray.getBase().getJSerializerInstance(basePackageName);
			method.addStatement("$T.$N.serialize(out, ($T) item)",
					baseSerializerInstance.className(),
					baseSerializerInstance.fieldName(),
					typeArray.getBase().getJTypeName(basePackageName)
			);
			method.endControlFlow();
		} else {
			method.addStatement("throw new $T()", NotSerializableException.class);
		}

		classBuilder.addMethod(method.build());
	}

	private void generateDeserialize(ComputedVersion version, ComputedTypeArray typeArray, Builder classBuilder) {
		var method = MethodSpec.methodBuilder("deserialize");

		method.addModifiers(Modifier.PUBLIC, Modifier.FINAL);
		method.addException(IOException.class);

		var typeArrayClassName = typeArray.getJTypeName(basePackageName);
		method.returns(typeArrayClassName);
		method.addAnnotation(NotNull.class);

		method.addParameter(ParameterSpec.builder(DataInput.class, "in").build());

		method.addStatement("int sz = in.readInt()");
		var arrayTypeName = ArrayTypeName.of(typeArray.getBase().getJTypeName(basePackageName));
		method.addStatement("$T a = new $T[sz]", arrayTypeName, arrayTypeName.componentType);
		method.addCode("\n");
		method.beginControlFlow("for (int i = 0; i < sz; ++i)");
		var baseSerializerInstance = typeArray.getBase().getJSerializerInstance(basePackageName);
		method.addStatement("a[i] = $T.$N.deserialize(in)", baseSerializerInstance.className(), baseSerializerInstance.fieldName());
		method.endControlFlow();

		method.addCode("\n");
		method.addStatement("return new $T(a)", ParameterizedTypeName.get(ClassName.get(ImmutableWrappedArrayList.class),
				typeArray.getBase().getJTypeName(basePackageName)));

		classBuilder.addMethod(method.build());
	}
}
