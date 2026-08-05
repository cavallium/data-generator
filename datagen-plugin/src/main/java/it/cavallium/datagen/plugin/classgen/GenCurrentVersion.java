package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeSpec.Builder;
import com.palantir.javapoet.TypeVariableName;
import com.palantir.javapoet.WildcardTypeName;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedType;
import it.cavallium.datagen.plugin.ComputedVersion;
import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.FallbackBufDataCursor;
import it.cavallium.buffer.HeapBufDataCursor;
import it.cavallium.buffer.MemorySegmentBufDataCursor;
import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.stream.SafeDataInput;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

public class GenCurrentVersion extends ClassGenerator {

	public GenCurrentVersion(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		var currentVersionPackage = dataModel.getCurrentVersion().getPackage(basePackageName);
		var currentVersionDataPackage = dataModel.getCurrentVersion().getDataPackage(basePackageName);

		var currentVersionClass = TypeSpec.classBuilder("CurrentVersion");
		currentVersionClass.addModifiers(Modifier.PUBLIC);
		currentVersionClass.addModifiers(Modifier.FINAL);
		// Add a static variable for the current version
		{
			var versionNumberField = FieldSpec.builder(ClassName
							.get(dataModel.getCurrentVersion().getPackage(basePackageName),
									"Version"), "VERSION").addModifiers(Modifier.PUBLIC).addModifiers(Modifier.STATIC)
					.addModifiers(Modifier.FINAL).initializer("new " + dataModel.getCurrentVersion().getPackage(basePackageName)
							+ ".Version()").build();
			currentVersionClass.addField(versionNumberField);
		}
		// Check latest version method
		{
			var isLatestVersionMethod = MethodSpec.methodBuilder("isLatestVersion").addModifiers(Modifier.PUBLIC)
					.addModifiers(Modifier.FINAL).addModifiers(Modifier.STATIC).returns(TypeName.BOOLEAN)
					.addParameter(ParameterSpec.builder(TypeName.INT, "version").build())
					.addCode("return version == VERSION.getVersion();").build();
			currentVersionClass.addMethod(isLatestVersionMethod);
		}
		// Get super type classes method and static field
		{
			var returnType = ParameterizedTypeName.get(ClassName.get(Set.class),
					ParameterizedTypeName.get(ClassName.get(Class.class),
							WildcardTypeName.subtypeOf(ClassName.get(currentVersionPackage, "IType"))));
			var superTypesField = FieldSpec.builder(returnType, "SUPER_TYPE_CLASSES", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
			var getSuperTypeClasses = MethodSpec.methodBuilder("getSuperTypeClasses").addModifiers(Modifier.PUBLIC)
					.addModifiers(Modifier.FINAL).addModifiers(Modifier.STATIC)
					.returns(returnType);

			var superTypesInitializerField = CodeBlock.builder();
			superTypesInitializerField.add("$T.of(\n", Set.class);
			AtomicBoolean isFirst = new AtomicBoolean(true);
			dataModel.getSuperTypesComputed(dataModel.getCurrentVersion()).forEach(superType -> {
				if (!isFirst.getAndSet(false)) {
					superTypesInitializerField.add(",\n");
				}
				superTypesInitializerField.add("$T.class",
						ClassName.get(dataModel.getVersion(superType).getDataPackage(basePackageName), superType.getName())
				);
			});
			superTypesInitializerField.add("\n);");
			superTypesField.initializer(superTypesInitializerField.build());
			getSuperTypeClasses.addStatement("return SUPER_TYPE_CLASSES");
			currentVersionClass.addField(superTypesField.build());
			currentVersionClass.addMethod(getSuperTypeClasses.build());
		}
		// Get super type subtypes classes method
		{
			var getSuperTypeSubtypesClasses = MethodSpec.methodBuilder("getSuperTypeSubtypesClasses").addModifiers(Modifier.PUBLIC)
					.addModifiers(Modifier.FINAL).addModifiers(Modifier.STATIC)
					.returns(ParameterizedTypeName.get(ClassName.get(Set.class),
							ParameterizedTypeName.get(ClassName.get(Class.class),
									WildcardTypeName.subtypeOf(ClassName.get(currentVersionPackage, "IBaseType")))));
			getSuperTypeSubtypesClasses
					.addParameter(ParameterSpec.builder(ParameterizedTypeName.get(ClassName.get(Class.class),
							WildcardTypeName.subtypeOf(ClassName.get(currentVersionPackage, "IType"))
					), "superTypeClass").build());
			var currentSuperTypes = dataModel.getSuperTypesComputed(dataModel.getCurrentVersion()).toList();
			if (currentSuperTypes.isEmpty()) {
				getSuperTypeSubtypesClasses.addStatement("throw new $T()", IllegalArgumentException.class);
			} else {
				getSuperTypeSubtypesClasses.beginControlFlow("return switch (superTypeClass.getCanonicalName())");
				currentSuperTypes.forEach(superType -> {
					getSuperTypeSubtypesClasses.addCode("case \"" + ClassName
							.get(currentVersionDataPackage, superType.getName())
							.canonicalName() + "\" -> $T.of(\n", Set.class);
					getSuperTypeSubtypesClasses.addCode("$>");
					AtomicBoolean isFirst = new AtomicBoolean(true);
					for (ComputedType subType : superType.subTypes()) {
						if (!isFirst.getAndSet(false)) {
							getSuperTypeSubtypesClasses.addCode(",\n");
						}
						getSuperTypeSubtypesClasses.addCode("$T.class",
								ClassName.get(currentVersionDataPackage, subType.getName())
						);
					}
					getSuperTypeSubtypesClasses.addCode("$<");
					getSuperTypeSubtypesClasses.addCode("\n);\n");
				});
				getSuperTypeSubtypesClasses.addStatement("default -> throw new $T()", IllegalArgumentException.class);
				getSuperTypeSubtypesClasses.addCode(CodeBlock.of("$<};"));
			}
			currentVersionClass.addMethod(getSuperTypeSubtypesClasses.build());
		}
		// Read serialized data directly into the current public type.
		{
			var readMethodBuilder = MethodSpec.methodBuilder("read")
					.addTypeVariable(TypeVariableName.get("U", ClassName.get(currentVersionPackage, "IBaseType")))
					.addModifiers(Modifier.PUBLIC).addModifiers(Modifier.STATIC).addModifiers(Modifier.FINAL).returns(TypeVariableName.get("U"))
					.addParameter(ParameterSpec.builder(TypeName.INT, "version").build()).addParameter(
							ParameterSpec.builder(ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType"), "type").build())
					.addParameter(ParameterSpec.builder(SafeDataInput.class, "input").build())
					.addStatement("$T.requireNonNull(type, $S)", Objects.class, "type")
					.addStatement("$T.requireNonNull(input, $S)", Objects.class, "input")
					.addStatement("input.decodeBudget().enterRoot()")
					.beginControlFlow("try")
					.beginControlFlow("return ($T) switch (type)", TypeVariableName.get("U"));
			dataModel.getBaseTypesComputed(dataModel.getCurrentVersion()).forEach(baseType ->
					readMethodBuilder.addStatement("case $N -> $T.read(version, input)", baseType.getName(),
							GenReadPlan.className(basePackageName, currentVersionPackage, baseType.getName())));
			var readMethod = readMethodBuilder
					.addCode(CodeBlock.of("$<};\n"))
					.nextControlFlow("finally")
					.addStatement("input.decodeBudget().exitRoot()")
					.endControlFlow()
					.build();
			currentVersionClass.addMethod(readMethod);
		}
		// UpgradeDataToLatestVersion2 Method
		{
			var versionsClassName = ClassName.get(dataModel.getRootPackage(basePackageName), "Versions");
			var upgradeDataToLatestVersion2MethodBuilder = MethodSpec.methodBuilder("upgradeDataToLatestVersion")
					.addModifiers(Modifier.PUBLIC).addModifiers(Modifier.STATIC).addModifiers(Modifier.FINAL).addTypeVariable(TypeVariableName.get("T"))
					.addTypeVariable(TypeVariableName.get("U", ClassName.get(currentVersionPackage, "IBaseType")))
					.returns(TypeVariableName.get("U"))
					.addParameter(ParameterSpec.builder(TypeName.INT, "oldVersion").build())
					.addParameter(ParameterSpec.builder(TypeVariableName.get("T"), "oldData").build())
					.addStatement("$T data = oldData", Object.class);
			upgradeDataToLatestVersion2MethodBuilder.beginControlFlow("switch (oldVersion)");
			for (var versionConfiguration : dataModel.getVersionsSet()) {
				// Upgrade an already materialized value through each structural boundary.
				upgradeDataToLatestVersion2MethodBuilder.addCode("case $T.$N: ",
						versionsClassName,
						versionConfiguration.getVersionVarName()
				);
				if (versionConfiguration.isCurrent()) {
					// This is the latest version, don't upgrade.
					upgradeDataToLatestVersion2MethodBuilder.addStatement("return ($T) data", TypeVariableName.get("U"));
				} else {
					// Upgrade
					ComputedVersion computedVersion = dataModel.getNextVersionOrThrow(versionConfiguration);
					upgradeDataToLatestVersion2MethodBuilder
							.addStatement(
									"data = " + versionConfiguration.getPackage(basePackageName)
											+ ".Version.upgradeToNextVersion(($T) data)",
									ClassName.get(versionConfiguration.getPackage(basePackageName), "IBaseType")
							);
				}
			}
			upgradeDataToLatestVersion2MethodBuilder.addStatement("default: throw new $T(\"Unknown version: \" + oldVersion)", UnsupportedOperationException.class);
			upgradeDataToLatestVersion2MethodBuilder.endControlFlow();
			currentVersionClass.addMethod(upgradeDataToLatestVersion2MethodBuilder.build());
		}

		generateReader(currentVersionClass, currentVersionPackage);

		generateGetClass(dataModel.getCurrentVersion(), currentVersionClass);

		return Stream.of(new GeneratedClass(dataModel.getCurrentVersion().getPackage(basePackageName), currentVersionClass));
	}

	private void generateReader(Builder currentVersionClass, String currentVersionPackage) {
		var iBaseType = ClassName.get(currentVersionPackage, "IBaseType");
		var baseType = ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType");
		var readerType = ClassName.get(currentVersionPackage, "CurrentVersion").nestedClass("Reader");
		var boundReaderType = ClassName.get(currentVersionPackage, "CurrentVersion").nestedClass("BoundReader");
		var heapCursorType = ClassName.get(HeapBufDataCursor.class);
		var segmentCursorType = ClassName.get(MemorySegmentBufDataCursor.class);
		var fallbackCursorType = ClassName.get(FallbackBufDataCursor.class);

		var reader = TypeSpec.interfaceBuilder("Reader")
				.addModifiers(Modifier.PUBLIC)
				.addTypeVariable(TypeVariableName.get("U", iBaseType))
				.addJavadoc("Reusable thread-confined reader. Implementations retain no source after a read returns.\n")
				.addMethod(MethodSpec.methodBuilder("read")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(TypeVariableName.get("U"))
						.addParameter(TypeName.INT, "version")
						.addParameter(Buf.class, "source")
						.build())
				.addMethod(MethodSpec.methodBuilder("read")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(TypeVariableName.get("U"))
						.addParameter(TypeName.INT, "version")
						.addParameter(Buf.class, "source")
						.addParameter(TypeName.INT, "offset")
						.addParameter(TypeName.INT, "length")
						.build())
				.build();
		currentVersionClass.addType(reader);

		var boundReader = TypeSpec.interfaceBuilder("BoundReader")
				.addModifiers(Modifier.PUBLIC)
				.addTypeVariable(TypeVariableName.get("U", iBaseType))
				.addJavadoc("Reusable thread-confined reader with type and serialized version selected once.\n")
				.addMethod(MethodSpec.methodBuilder("read")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(TypeVariableName.get("U"))
						.addParameter(Buf.class, "source")
						.build())
				.addMethod(MethodSpec.methodBuilder("read")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(TypeVariableName.get("U"))
						.addParameter(Buf.class, "source")
						.addParameter(TypeName.INT, "offset")
						.addParameter(TypeName.INT, "length")
						.build())
				.build();
		currentVersionClass.addType(boundReader);

		currentVersionClass.addMethod(MethodSpec.methodBuilder("trailingBytes")
				.addModifiers(Modifier.PRIVATE, Modifier.STATIC)
				.returns(MalformedDataException.class)
				.addParameter(TypeName.INT, "trailing")
				.addStatement("return new $T($S.concat($T.toString(trailing)))", MalformedDataException.class,
						"Trailing bytes: ", Integer.class)
				.build());

		var readerBase = TypeSpec.classBuilder("ReaderBase")
				.addModifiers(Modifier.PRIVATE, Modifier.ABSTRACT, Modifier.STATIC)
				.addTypeVariable(TypeVariableName.get("U", iBaseType))
				.addSuperinterface(ParameterizedTypeName.get(readerType, TypeVariableName.get("U")))
				.addField(FieldSpec.builder(DecodeBudget.class, "budget", Modifier.PRIVATE, Modifier.FINAL).build())
				.addField(FieldSpec.builder(heapCursorType, "heapCursor", Modifier.PRIVATE, Modifier.FINAL).build())
				.addField(FieldSpec.builder(segmentCursorType, "segmentCursor", Modifier.PRIVATE, Modifier.FINAL).build())
				.addField(FieldSpec.builder(fallbackCursorType, "fallbackCursor", Modifier.PRIVATE, Modifier.FINAL).build())
				.addMethod(MethodSpec.constructorBuilder()
						.addParameter(DecodeLimits.class, "limits")
						.addStatement("this.budget = new $T($T.requireNonNull(limits, $S))", DecodeBudget.class,
								Objects.class, "limits")
						.addStatement("this.heapCursor = new $T(budget)", heapCursorType)
						.addStatement("this.segmentCursor = new $T(budget)", segmentCursorType)
						.addStatement("this.fallbackCursor = new $T(budget)", fallbackCursorType)
						.build())
				.addMethod(MethodSpec.methodBuilder("readHeapValue")
						.addModifiers(Modifier.PROTECTED, Modifier.ABSTRACT)
						.returns(TypeVariableName.get("U"))
						.addParameter(TypeName.INT, "version")
						.addParameter(heapCursorType, "input")
						.build())
				.addMethod(MethodSpec.methodBuilder("readMemorySegmentValue")
						.addModifiers(Modifier.PROTECTED, Modifier.ABSTRACT)
						.returns(TypeVariableName.get("U"))
						.addParameter(TypeName.INT, "version")
						.addParameter(segmentCursorType, "input")
						.build())
				.addMethod(MethodSpec.methodBuilder("readFallbackValue")
						.addModifiers(Modifier.PROTECTED, Modifier.ABSTRACT)
						.returns(TypeVariableName.get("U"))
						.addParameter(TypeName.INT, "version")
						.addParameter(fallbackCursorType, "input")
						.build())
				.addMethod(MethodSpec.methodBuilder("read")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(TypeVariableName.get("U"))
						.addParameter(TypeName.INT, "version")
						.addParameter(Buf.class, "source")
						.addStatement("$T.requireNonNull(source, $S)", Objects.class, "source")
						.addStatement("return read(version, source, 0, source.size())")
						.build())
				.addMethod(MethodSpec.methodBuilder("read")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(TypeVariableName.get("U"))
						.addParameter(TypeName.INT, "version")
						.addParameter(Buf.class, "source")
						.addParameter(TypeName.INT, "offset")
						.addParameter(TypeName.INT, "length")
						.beginControlFlow("return switch ($T.bindSpecialized(source, offset, length, heapCursor, "
								+ "segmentCursor, fallbackCursor))", BufDataCursor.class)
						.addStatement("case HEAP -> readHeap(version)")
						.addStatement("case MEMORY_SEGMENT -> readMemorySegment(version)")
						.addStatement("case FALLBACK -> readFallback(version)")
						.addCode("$<};\n")
						.build())
					.addMethod(storageReadMethod("readHeap", "readHeapValue", "heapCursor", true))
					.addMethod(storageReadMethod("readMemorySegment", "readMemorySegmentValue",
							"segmentCursor", true))
					.addMethod(storageReadMethod("readFallback", "readFallbackValue",
							"fallbackCursor", true))
					.build();
		currentVersionClass.addType(readerBase);

		var boundReaderBase = TypeSpec.classBuilder("BoundReaderBase")
				.addModifiers(Modifier.PRIVATE, Modifier.ABSTRACT, Modifier.STATIC)
				.addTypeVariable(TypeVariableName.get("U", iBaseType))
				.addSuperinterface(ParameterizedTypeName.get(boundReaderType, TypeVariableName.get("U")))
				.addField(FieldSpec.builder(DecodeBudget.class, "budget", Modifier.PRIVATE, Modifier.FINAL).build())
				.addField(FieldSpec.builder(heapCursorType, "heapCursor", Modifier.PRIVATE, Modifier.FINAL).build())
				.addField(FieldSpec.builder(segmentCursorType, "segmentCursor", Modifier.PRIVATE, Modifier.FINAL).build())
				.addField(FieldSpec.builder(fallbackCursorType, "fallbackCursor", Modifier.PRIVATE, Modifier.FINAL).build())
				.addMethod(MethodSpec.constructorBuilder()
						.addParameter(DecodeLimits.class, "limits")
						.addStatement("this.budget = new $T($T.requireNonNull(limits, $S))", DecodeBudget.class,
								Objects.class, "limits")
						.addStatement("this.heapCursor = new $T(budget)", heapCursorType)
						.addStatement("this.segmentCursor = new $T(budget)", segmentCursorType)
						.addStatement("this.fallbackCursor = new $T(budget)", fallbackCursorType)
						.build())
				.addMethod(MethodSpec.methodBuilder("readHeapValue")
						.addModifiers(Modifier.PROTECTED, Modifier.ABSTRACT)
						.returns(TypeVariableName.get("U"))
						.addParameter(heapCursorType, "input")
						.build())
				.addMethod(MethodSpec.methodBuilder("readMemorySegmentValue")
						.addModifiers(Modifier.PROTECTED, Modifier.ABSTRACT)
						.returns(TypeVariableName.get("U"))
						.addParameter(segmentCursorType, "input")
						.build())
				.addMethod(MethodSpec.methodBuilder("readFallbackValue")
						.addModifiers(Modifier.PROTECTED, Modifier.ABSTRACT)
						.returns(TypeVariableName.get("U"))
						.addParameter(fallbackCursorType, "input")
						.build())
				.addMethod(MethodSpec.methodBuilder("read")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
						.returns(TypeVariableName.get("U"))
						.addParameter(Buf.class, "source")
						.addStatement("$T.requireNonNull(source, $S)", Objects.class, "source")
						.addStatement("return read(source, 0, source.size())")
						.build())
				.addMethod(MethodSpec.methodBuilder("read")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
						.returns(TypeVariableName.get("U"))
						.addParameter(Buf.class, "source")
						.addParameter(TypeName.INT, "offset")
						.addParameter(TypeName.INT, "length")
						.beginControlFlow("return switch ($T.bindSpecialized(source, offset, length, heapCursor, "
								+ "segmentCursor, fallbackCursor))", BufDataCursor.class)
						.addStatement("case HEAP -> readHeap()")
						.addStatement("case MEMORY_SEGMENT -> readMemorySegment()")
						.addStatement("case FALLBACK -> readFallback()")
						.addCode("$<};\n")
						.build())
				.addMethod(storageReadMethod("readHeap", "readHeapValue", "heapCursor", false))
				.addMethod(storageReadMethod("readMemorySegment", "readMemorySegmentValue",
						"segmentCursor", false))
				.addMethod(storageReadMethod("readFallback", "readFallbackValue",
						"fallbackCursor", false))
					.build();
		currentVersionClass.addType(boundReaderBase);

		dataModel.getBaseTypesComputed(dataModel.getCurrentVersion()).forEach(currentType -> {
			String className = currentType.getName() + "Reader";
			ClassName planType = GenReadPlan.className(basePackageName, currentVersionPackage, currentType.getName());
			currentVersionClass.addType(TypeSpec.classBuilder(className)
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
					.superclass(ParameterizedTypeName.get(
							ClassName.get(currentVersionPackage, "CurrentVersion").nestedClass("ReaderBase"),
							currentType.getJTypeName(basePackageName)))
					.addMethod(MethodSpec.constructorBuilder()
							.addParameter(DecodeLimits.class, "limits")
							.addStatement("super(limits)")
							.build())
					.addField(FieldSpec.builder(planType.nestedClass("State"), "state", Modifier.PRIVATE, Modifier.FINAL)
							.initializer("new $T()", planType.nestedClass("State"))
							.build())
					.addMethod(MethodSpec.methodBuilder("readHeapValue")
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PROTECTED)
							.returns(currentType.getJTypeName(basePackageName))
							.addParameter(TypeName.INT, "version")
							.addParameter(heapCursorType, "input")
							.addStatement("return $T.read(version, input, state)", planType)
							.build())
					.addMethod(MethodSpec.methodBuilder("readMemorySegmentValue")
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PROTECTED)
							.returns(currentType.getJTypeName(basePackageName))
							.addParameter(TypeName.INT, "version")
							.addParameter(segmentCursorType, "input")
							.addStatement("return $T.read(version, input, state)", planType)
							.build())
					.addMethod(MethodSpec.methodBuilder("readFallbackValue")
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PROTECTED)
							.returns(currentType.getJTypeName(basePackageName))
							.addParameter(TypeName.INT, "version")
							.addParameter(fallbackCursorType, "input")
							.addStatement("return $T.read(version, input, state)", planType)
							.build())
					.build());

			for (ComputedVersion version : dataModel.getVersionsSet()) {
				String boundClassName = currentType.getName() + "V" + version.getVersion() + "Reader";
				currentVersionClass.addType(TypeSpec.classBuilder(boundClassName)
						.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
						.superclass(ParameterizedTypeName.get(
								ClassName.get(currentVersionPackage, "CurrentVersion").nestedClass("BoundReaderBase"),
								currentType.getJTypeName(basePackageName)))
						.addMethod(MethodSpec.constructorBuilder()
								.addParameter(DecodeLimits.class, "limits")
								.addStatement("super(limits)")
								.build())
						.addField(FieldSpec.builder(planType.nestedClass("State"), "state",
								Modifier.PRIVATE, Modifier.FINAL)
								.initializer("new $T()", planType.nestedClass("State"))
								.build())
						.addMethod(MethodSpec.methodBuilder("readHeapValue")
								.addAnnotation(Override.class)
								.addModifiers(Modifier.PROTECTED, Modifier.FINAL)
								.returns(currentType.getJTypeName(basePackageName))
								.addParameter(heapCursorType, "input")
								.addStatement("return $T.readV$L(input, state)", planType, version.getVersion())
								.build())
						.addMethod(MethodSpec.methodBuilder("readMemorySegmentValue")
								.addAnnotation(Override.class)
								.addModifiers(Modifier.PROTECTED, Modifier.FINAL)
								.returns(currentType.getJTypeName(basePackageName))
								.addParameter(segmentCursorType, "input")
								.addStatement("return $T.readV$L(input, state)", planType, version.getVersion())
								.build())
						.addMethod(MethodSpec.methodBuilder("readFallbackValue")
								.addAnnotation(Override.class)
								.addModifiers(Modifier.PROTECTED, Modifier.FINAL)
								.returns(currentType.getJTypeName(basePackageName))
								.addParameter(fallbackCursorType, "input")
								.addStatement("return $T.readV$L(input, state)", planType, version.getVersion())
								.build())
						.build());
			}
		});

		var newReader = MethodSpec.methodBuilder("newReader")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.addTypeVariable(TypeVariableName.get("U", iBaseType))
				.returns(ParameterizedTypeName.get(readerType, TypeVariableName.get("U")))
				.addParameter(baseType, "type")
				.addParameter(DecodeLimits.class, "limits")
				.addStatement("$T.requireNonNull(type, $S)", Objects.class, "type")
				.addStatement("$T.requireNonNull(limits, $S)", Objects.class, "limits")
				.beginControlFlow("return ($T) switch (type)",
						ParameterizedTypeName.get(readerType, TypeVariableName.get("U")));
		dataModel.getBaseTypesComputed(dataModel.getCurrentVersion()).forEach(currentType ->
				newReader.addStatement("case $N -> new $N(limits)", currentType.getName(), currentType.getName() + "Reader"));
		newReader.addCode(CodeBlock.of("$<};"));
		currentVersionClass.addMethod(newReader.build());

		var newBoundReader = MethodSpec.methodBuilder("newReader")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.addTypeVariable(TypeVariableName.get("U", iBaseType))
				.returns(ParameterizedTypeName.get(boundReaderType, TypeVariableName.get("U")))
				.addParameter(TypeName.INT, "version")
				.addParameter(baseType, "type")
				.addParameter(DecodeLimits.class, "limits")
				.addStatement("$T.requireNonNull(type, $S)", Objects.class, "type")
				.addStatement("$T.requireNonNull(limits, $S)", Objects.class, "limits")
				.beginControlFlow("return ($T) switch (type)",
						ParameterizedTypeName.get(boundReaderType, TypeVariableName.get("U")));
		dataModel.getBaseTypesComputed(dataModel.getCurrentVersion()).forEach(currentType -> {
			String helperName = "new" + currentType.getName() + "BoundReader";
			newBoundReader.addStatement("case $N -> $N(version, limits)", currentType.getName(), helperName);
			var newTypeBoundReader = MethodSpec.methodBuilder(helperName)
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC)
					.returns(ParameterizedTypeName.get(boundReaderType,
							currentType.getJTypeName(basePackageName)))
					.addParameter(TypeName.INT, "version")
					.addParameter(DecodeLimits.class, "limits")
					.beginControlFlow("return switch (version)");
			for (ComputedVersion version : dataModel.getVersionsSet()) {
				newTypeBoundReader.addStatement("case $L -> new $N(limits)", version.getVersion(),
						currentType.getName() + "V" + version.getVersion() + "Reader");
			}
			newTypeBoundReader.addStatement("default -> throw new $T($S + version)", IllegalArgumentException.class,
					"Unsupported serialized version: ")
					.addCode(CodeBlock.of("$<};"));
			currentVersionClass.addMethod(newTypeBoundReader.build());
		});
		newBoundReader.addCode(CodeBlock.of("$<};"));
		currentVersionClass.addMethod(newBoundReader.build());
	}

	private MethodSpec storageReadMethod(String methodName,
			String valueMethod,
			String cursorField,
			boolean versioned) {
		var method = MethodSpec.methodBuilder(methodName)
				.addModifiers(Modifier.PRIVATE)
				.returns(TypeVariableName.get("U"));
		if (versioned) method.addParameter(TypeName.INT, "version");
		method.addStatement("budget.enterRoot()")
				.beginControlFlow("try")
				.addStatement("U result = $N($L$N)", valueMethod, versioned ? "version, " : "", cursorField)
				.addStatement("int trailing = $N.remainingIncludingClosed()", cursorField)
				.beginControlFlow("if (trailing != 0)")
				.addStatement("throw trailingBytes(trailing)")
				.endControlFlow()
				.addStatement("return result")
				.nextControlFlow("finally")
				.addStatement("$N.unbind()", cursorField)
				.addStatement("budget.exitRoot()")
				.endControlFlow();
		return method.build();
	}

	private void generateGetClass(ComputedVersion version, Builder classBuilder) {
		var methodBuilder = MethodSpec.methodBuilder("getClass");

		methodBuilder.addModifiers(Modifier.PUBLIC, Modifier.STATIC);

		var baseTypeClassName = ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType");
		methodBuilder.addParameter(baseTypeClassName, "type");

		var iBaseTypeClassName = ClassName.get(version.getPackage(basePackageName), "IBaseType");
		methodBuilder.returns(ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(iBaseTypeClassName)));

		methodBuilder.beginControlFlow("return switch (type)");
		dataModel.getBaseTypesComputed(version).forEach(baseType -> {
			methodBuilder.addStatement("case $N -> $T.class", baseType.getName(), baseType.getJTypeName(basePackageName));
		});
		methodBuilder.addCode(CodeBlock.of("$<};"));
		classBuilder.addMethod(methodBuilder.build());
	}
}
