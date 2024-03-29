package it.cavallium.datagen.plugin.classgen;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import com.squareup.javapoet.WildcardTypeName;
import it.cavallium.datagen.DataSerializer;
import it.cavallium.datagen.NotSerializableException;
import it.cavallium.datagen.nativedata.ImmutableWrappedArrayList;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedTypeArray;
import it.cavallium.datagen.plugin.ComputedTypeArrayFixed;
import it.cavallium.datagen.plugin.ComputedTypeArrayVersioned;
import it.cavallium.datagen.plugin.ComputedVersion;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.util.Objects;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.jetbrains.annotations.NotNull;

public class GenSerializerArrayX extends ClassGenerator {

	/**
	 * Enabling this option can slow down deserialization updates
	 */
	private static final boolean USE_NATIVE_TYPED_ARRAYS = false;

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

		method.addParameter(ParameterSpec.builder(SafeDataOutput.class, "out").build());
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

		var typeArrayClassName = typeArray.getJTypeName(basePackageName);

		var arrayComponentTypeName = typeArray.getBase().getJTypeName(basePackageName);
		var typedArrayTypeName = ArrayTypeName.of(arrayComponentTypeName);

		method.returns(typeArrayClassName);
		method.addAnnotation(NotNull.class);

		method.addParameter(ParameterSpec.builder(SafeDataInput.class, "in").build());

		method.addStatement("int sz = in.readInt()");
		if (USE_NATIVE_TYPED_ARRAYS) {
			method.addStatement("$T a = new $T[sz]", typedArrayTypeName, arrayComponentTypeName);
		} else {
			method.addStatement("$T a = new $T[sz]", Object[].class, Object.class);
		}
		method.addCode("\n");
		method.beginControlFlow("for (int i = 0; i < sz; ++i)");
		var baseSerializerInstance = typeArray.getBase().getJSerializerInstance(basePackageName);

		method.addStatement("a[i] = $T.$N.deserialize(in)", baseSerializerInstance.className(), baseSerializerInstance.fieldName());
		method.endControlFlow();

		method.addCode("\n");
		if (USE_NATIVE_TYPED_ARRAYS) {
			method.addStatement("return $T.of(a)", ParameterizedTypeName.get(ClassName.get(ImmutableWrappedArrayList.class), arrayComponentTypeName));
		} else {
			method.addStatement("return ($T) ($T) $T.of(a)",
					ParameterizedTypeName.get(ClassName.get(ImmutableWrappedArrayList.class), arrayComponentTypeName),
					ParameterizedTypeName.get(ClassName.get(ImmutableWrappedArrayList.class), WildcardTypeName.subtypeOf(TypeName.OBJECT)),
					ClassName.get(ImmutableWrappedArrayList.class)
			);
		}

		classBuilder.addMethod(method.build());
	}
}
