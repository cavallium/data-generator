package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeSpec.Builder;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.CodecReadState;
import it.cavallium.datagen.NotSerializableException;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.datagen.ReadSession;
import it.cavallium.buffer.RandomAccessDataInput;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedTypeArray;
import it.cavallium.datagen.plugin.ComputedTypeArrayFixed;
import it.cavallium.datagen.plugin.ComputedTypeArrayVersioned;
import it.cavallium.datagen.plugin.ComputedTypeCustom;
import it.cavallium.datagen.plugin.ComputedVersion;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.util.Objects;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.jetbrains.annotations.NotNull;

public class GenSerializerArrayX extends ClassGenerator {
	private final ReadPlanCompiler readPlanCompiler;

	public GenSerializerArrayX(ClassGeneratorParams params) {
		super(params);
		this.readPlanCompiler = new ReadPlanCompiler(dataModel,
				message -> new IllegalArgumentException("Array codec wire shape: " + message));
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

		classBuilder.addSuperinterface(ParameterizedTypeName.get(ClassName.get(DataCodec.class), typeArrayClassName));
		classBuilder.addField(FieldSpec.builder(typeArrayClassName, "EMPTY",
				Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
				.initializer("new $T[0]", typeArray.getBase().getJTypeName(basePackageName))
				.build());
		classBuilder.addMethod(MethodSpec.methodBuilder("emptyArray")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.returns(typeArrayClassName)
				.addStatement("return EMPTY")
				.build());

		generateSerialize(version, typeArray, classBuilder);

		generateRead(version, typeArray, classBuilder);

		generateSkip(typeArray, classBuilder);

		generateReadSession(typeArray, classBuilder);

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
			method.addStatement("final int sz = data.length");
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

	private void generateRead(ComputedVersion version, ComputedTypeArray typeArray, Builder classBuilder) {
		var typeArrayClassName = typeArray.getJTypeName(basePackageName);
		var arrayComponentTypeName = typeArray.getBase().getJTypeName(basePackageName);
		classBuilder.addMethod(MethodSpec.methodBuilder("read")
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.returns(typeArrayClassName)
				.addAnnotation(NotNull.class)
				.addParameter(ParameterSpec.builder(SafeDataInput.class, "in").build())
				.addStatement("return readValue(in, in.decodeBudget().codecReadState())")
				.build());

		var method = MethodSpec.methodBuilder("readValue")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.returns(typeArrayClassName)
				.addAnnotation(NotNull.class)
				.addParameter(ParameterSpec.builder(SafeDataInput.class, "in").build())
				.addParameter(ParameterSpec.builder(CodecReadState.class, "codecState").build());
		method.addStatement("in.decodeBudget().enterStructure()")
				.beginControlFlow("try");

		method.addStatement("int sz = $T.readLength(in)", ProjectionReadSupport.class);
		if (typeArray.getBase() instanceof ComputedTypeCustom custom && custom.getFixedSize() != null) {
			int fixedSize = custom.getFixedSize();
			method.addStatement("int bodyBytes = $T.checkedArrayBytes(sz, $L)", ProjectionReadSupport.class,
					fixedSize)
					.beginControlFlow("if (sz == 0)")
					.addStatement("in.decodeBudget().claimArrayElements(0)")
					.addStatement("return EMPTY")
					.endControlFlow()
					.beginControlFlow("if (in instanceof $T randomInput)", RandomAccessDataInput.class)
					.addStatement("int bodyStart = randomInput.reserve(bodyBytes)")
					.addStatement("in.decodeBudget().claimArrayElements(sz)")
					.addStatement("$T a = new $T[sz]", typeArrayClassName, arrayComponentTypeName)
					.addStatement("var session = $L", customSession(custom, "codecState"))
					.beginControlFlow("for (int i = 0; i < sz; ++i)")
					.addStatement("a[i] = session.readReserved(randomInput, bodyStart + i * $L, $L)", fixedSize,
							fixedSize)
					.endControlFlow()
					.addStatement("return a")
					.endControlFlow()
					.addStatement("$T.prepareArrayAllocation(in, sz, $L)", ProjectionReadSupport.class, fixedSize)
					.addStatement("$T a = new $T[sz]", typeArrayClassName, arrayComponentTypeName)
					.addStatement("var session = $L", customSession(custom, "codecState"))
					.beginControlFlow("for (int i = 0; i < sz; ++i)")
					.addStatement("a[i] = session.read(in)")
					.endControlFlow()
					.addStatement("return a");
			method.nextControlFlow("finally")
					.addStatement("in.decodeBudget().exitStructure()")
					.endControlFlow();
			classBuilder.addMethod(method.build());
			return;
		}
		method.addStatement("$T.prepareArrayAllocation(in, sz, $L)", ProjectionReadSupport.class,
				readPlanCompiler.minimumSerializedSize(typeArray.getBase()));
		method.beginControlFlow("if (sz == 0)")
				.addStatement("return EMPTY")
				.endControlFlow();
		method.addStatement("$T a = new $T[sz]", typeArrayClassName, arrayComponentTypeName);
		method.addCode("\n");
		method.beginControlFlow("for (int i = 0; i < sz; ++i)");
		var baseSerializerInstance = typeArray.getBase().getJSerializerInstance(basePackageName);

		if (typeArray.getBase() instanceof ComputedTypeCustom custom) {
			method.addStatement("a[i] = $L.read(in)", customSession(custom, "codecState"));
		} else {
			method.addStatement("a[i] = $T.readValue(in, codecState)",
					typeArray.getBase().getJSerializerName(basePackageName));
		}
		method.endControlFlow();

		method.addCode("\n");
		method.addStatement("return a");
		method.nextControlFlow("finally")
				.addStatement("in.decodeBudget().exitStructure()")
				.endControlFlow();

		classBuilder.addMethod(method.build());
	}

	private void generateSkip(ComputedTypeArray typeArray, Builder classBuilder) {
		var baseCodec = typeArray.getBase().getJSerializerInstance(basePackageName);
		classBuilder.addMethod(MethodSpec.methodBuilder("skip")
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.addParameter(SafeDataInput.class, "in")
				.addStatement("skipValue(in, in.decodeBudget().codecReadState())")
				.build());
		var method = MethodSpec.methodBuilder("skipValue")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.addParameter(SafeDataInput.class, "in")
				.addParameter(CodecReadState.class, "codecState")
				.addStatement("in.decodeBudget().enterStructure()")
				.beginControlFlow("try");
		if (typeArray.getBase() instanceof ComputedTypeCustom custom && custom.getFixedSize() != null) {
			method.addStatement("$T.skipFixedArray(in, $L)", ProjectionReadSupport.class, custom.getFixedSize());
		} else {
			method.addStatement("int size = $T.readLength(in)", ProjectionReadSupport.class)
					.addStatement("$T.prepareArrayAllocation(in, size, $L)", ProjectionReadSupport.class,
							readPlanCompiler.minimumSerializedSize(typeArray.getBase()))
					.beginControlFlow("for (int i = 0; i < size; i++)");
			if (typeArray.getBase() instanceof ComputedTypeCustom custom) {
				method.addStatement("$L.skip(in)", customSession(custom, "codecState"));
			} else {
				method.addStatement("$T.skipValue(in, codecState)",
						typeArray.getBase().getJSerializerName(basePackageName));
			}
			method.endControlFlow();
		}
		method.nextControlFlow("finally")
				.addStatement("in.decodeBudget().exitStructure()")
				.endControlFlow();
		classBuilder.addMethod(method.build());
	}

	private void generateReadSession(ComputedTypeArray typeArray, Builder classBuilder) {
		var valueType = typeArray.getJTypeName(basePackageName);
		ClassName serializerType = typeArray.getJSerializerName(basePackageName);
		classBuilder.addMethod(MethodSpec.methodBuilder("newReadSession")
				.addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.returns(ParameterizedTypeName.get(ClassName.get(ReadSession.class), valueType))
				.addStatement("return new Session()")
				.build());
		classBuilder.addType(TypeSpec.classBuilder("Session")
				.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
				.superclass(ParameterizedTypeName.get(ClassName.get(ReadSession.class), valueType))
				.addMethod(MethodSpec.methodBuilder("decode")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PROTECTED)
						.returns(valueType)
						.addParameter(SafeDataInput.class, "input")
						.addStatement("return $T.readValue(input, input.decodeBudget().codecReadState())",
								serializerType)
						.build())
				.addMethod(MethodSpec.methodBuilder("skipValue")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PROTECTED)
						.addParameter(SafeDataInput.class, "input")
						.addStatement("$T.skipValue(input, input.decodeBudget().codecReadState())", serializerType)
						.build())
				.addMethod(MethodSpec.methodBuilder("clearTransientState")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PROTECTED)
						.addComment("All mutable custom state belongs to the input lane's CodecReadState.")
						.build())
				.build());
	}

	private CodeBlock customSession(ComputedTypeCustom custom, String stateName) {
		var codec = custom.getJSerializerInstance(basePackageName);
		return CodeBlock.of("$N.session($S, $T.$N)", stateName, custom.getName(),
				codec.className(), codec.fieldName());
	}
}
