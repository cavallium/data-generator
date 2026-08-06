package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataInitializer;
import it.cavallium.datagen.DataUpgrader;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.FallbackBufDataCursor;
import it.cavallium.buffer.HeapBufDataCursor;
import it.cavallium.buffer.MemorySegmentBufDataCursor;
import it.cavallium.buffer.RandomAccessDataInput;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedType;
import it.cavallium.datagen.plugin.ComputedType.VersionedComputedType;
import it.cavallium.datagen.plugin.ComputedTypeArray;
import it.cavallium.datagen.plugin.ComputedTypeArrayNative;
import it.cavallium.datagen.plugin.ComputedTypeBase;
import it.cavallium.datagen.plugin.ComputedTypeCustom;
import it.cavallium.datagen.plugin.ComputedTypeNative;
import it.cavallium.datagen.plugin.ComputedTypeNullable;
import it.cavallium.datagen.plugin.ComputedTypeSuper;
import it.cavallium.datagen.plugin.CustomTypesConfiguration;
import it.cavallium.datagen.plugin.DataModel;
import it.cavallium.datagen.plugin.FieldLocation;
import it.cavallium.datagen.plugin.JInterfaceLocation;
import it.cavallium.datagen.plugin.JInterfaceLocation.JInterfaceLocationClassName;
import it.cavallium.datagen.plugin.JInterfaceLocation.JInterfaceLocationInstanceField;
import it.cavallium.datagen.plugin.NewDataConfiguration;
import it.cavallium.datagen.plugin.ReadTransformConfiguration;
import it.cavallium.datagen.plugin.UpgradeDataConfiguration;
import it.cavallium.datagen.plugin.WireLayout;
import it.cavallium.datagen.nativedata.BinaryStringSerializer;
import it.cavallium.datagen.nativedata.Int52Serializer;
import it.cavallium.stream.SafeDataInput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

/** Generates fused historical wire readers, one helper class per current base type. */
public final class GenReadPlan extends ClassGenerator {
	private static final ClassName VECTOR_ARRAY_SUPPORT =
			ClassName.get("it.cavallium.datagen.vector", "VectorArraySupport");

	public GenReadPlan(ClassGeneratorParams params) {
		super(params);
	}

	public static ClassName className(String basePackageName, String currentPackage, String baseTypeName) {
		return ClassName.get(DataModel.joinPackage(currentPackage, "readers"), baseTypeName + "ReadPlan");
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		String currentPackage = dataModel.getCurrentVersion().getPackage(basePackageName);
		ReadPlanCompiler compiler = new ReadPlanCompiler(dataModel,
				message -> new IllegalArgumentException("Read plan: " + message));
		return dataModel.getBaseTypesComputed(dataModel.getCurrentVersion())
				.map(type -> new PlanGenerator(type,
						className(basePackageName, currentPackage, type.getName()), compiler).generate());
	}

	private final class PlanGenerator {

		private final ComputedTypeBase currentType;
		private final ClassName planClassName;
		private final TypeSpec.Builder classBuilder;
		private final TypeSpec.Builder stateBuilder;
		private final ReadPlanCompiler readPlanCompiler;
		private final Map<ReaderKey, String> readerMethods = new LinkedHashMap<>();
		private final Deque<ReaderKey> pendingReaders = new ArrayDeque<>();
		private final Map<String, List<MethodSpec>> generatedReaders = new LinkedHashMap<>();
		private final Map<String, Set<String>> readerDependencies = new LinkedHashMap<>();
		private final Map<FieldReaderKey, String> fieldReaderMethods = new LinkedHashMap<>();
		private final Deque<FieldReaderKey> pendingFieldReaders = new ArrayDeque<>();
		private final Map<String, MethodSpec> generatedFieldReaders = new LinkedHashMap<>();
		private final Set<String> externallyRequiredReaders = new java.util.HashSet<>();
		private final Map<ObjectMapperKey, String> objectMapperMethods = new LinkedHashMap<>();
		private final Deque<ObjectMapperKey> pendingObjectMappers = new ArrayDeque<>();
		private final Map<SkipperKey, String> skipperMethods = new LinkedHashMap<>();
		private final Deque<Map.Entry<SkipperKey, ComputedType>> pendingSkippers = new ArrayDeque<>();
		private final IdentityHashMap<Object, TransformSupport> transformSupports = new IdentityHashMap<>();
		private final IdentityHashMap<Object, TransformSupport> readTransformSupports = new IdentityHashMap<>();
		private final Map<ReadInitializerFrameKey, String> readInitializerFrames = new LinkedHashMap<>();
		private final Map<ReadUpgradeFrameKey, String> readUpgradeFrames = new LinkedHashMap<>();
		private final Map<WireTransformCursorKey, String> wireTransformCursors = new LinkedHashMap<>();
		private final Map<String, String> sharedStateAccessors = new LinkedHashMap<>();
		private final IdentityHashMap<ComputedType, WireViewBinding> activeRegionWireViews = new IdentityHashMap<>();
		private final Set<String> recursiveRegionWireViewFields = new java.util.HashSet<>();
		private final IdentityHashMap<ComputedType, WireViewBinding> activeDelegatingWireViews = new IdentityHashMap<>();
		private final Set<String> recursiveDelegatingWireViewFields = new java.util.HashSet<>();
		private final int stateId;
		private final int stateCount;
		private int nextReaderId;
		private int nextFieldReaderId;
		private int nextObjectMapperId;
		private int nextSkipperId;
		private int nextTransformId;
		private int nextReadFrameId;
		private int nextWireViewId;
		private int nextWireTransformCursorId;
		private String activeGeneratedMethod;
		private StorageKernel activeKernel = StorageKernel.GENERIC;

		private PlanGenerator(ComputedTypeBase currentType,
				ClassName planClassName,
				ReadPlanCompiler readPlanCompiler) {
			this.currentType = currentType;
			this.planClassName = planClassName;
			this.classBuilder = TypeSpec.classBuilder(planClassName.simpleName())
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.addJavadoc("Fused historical readers for {@code $T}.\n", currentType.getJTypeName(basePackageName))
					.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
			this.classBuilder.addType(TypeSpec.interfaceBuilder("WireRegionOwner")
					.addModifiers(Modifier.PRIVATE)
					.addMethod(MethodSpec.methodBuilder("wireParent")
							.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
							.returns(RandomAccessDataInput.class)
							.build())
					.addMethod(MethodSpec.methodBuilder("wireState")
							.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
							.returns(planClassName.nestedClass("State"))
							.build())
					.addMethod(MethodSpec.methodBuilder("wireStart")
							.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
							.returns(TypeName.INT)
							.build())
					.addMethod(MethodSpec.methodBuilder("wireLength")
							.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
							.returns(TypeName.INT)
							.build())
					.build());
			var currentTypes = dataModel.getBaseTypesComputed(dataModel.getCurrentVersion()).toList();
			this.stateId = java.util.stream.IntStream.range(0, currentTypes.size())
					.filter(index -> currentTypes.get(index).getName().equals(currentType.getName()))
					.findFirst()
					.orElseThrow(() -> generationError("current type has no shared-state id"));
			this.stateCount = currentTypes.size();
			this.stateBuilder = TypeSpec.classBuilder("State")
					.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
					.addJavadoc("Reusable thread-confined scratch state for this read plan.\n")
					.addField(Object[].class, "sharedStates", Modifier.PRIVATE)
					.addMethod(MethodSpec.constructorBuilder()
							.addModifiers(Modifier.PUBLIC)
							.build())
					.addMethod(MethodSpec.constructorBuilder()
							.addModifiers(Modifier.PRIVATE)
							.addParameter(Object[].class, "sharedStates")
							.addStatement("this.sharedStates = sharedStates")
							.addStatement("sharedStates[$L] = this", stateId)
							.build())
					.addMethod(MethodSpec.methodBuilder("sharedState")
							.addModifiers(Modifier.STATIC)
							.returns(planClassName.nestedClass("State"))
							.addParameter(Object[].class, "sharedStates")
							.addStatement("Object existing = sharedStates[$L]", stateId)
							.beginControlFlow("if (existing == null)")
							.addStatement("return new $T(sharedStates)", planClassName.nestedClass("State"))
							.endControlFlow()
							.addStatement("return ($T) existing", planClassName.nestedClass("State"))
							.build())
					.addMethod(MethodSpec.methodBuilder("sharedStates")
							.returns(Object[].class)
							.beginControlFlow("if (sharedStates == null)")
							.addStatement("sharedStates = new Object[$L]", stateCount)
							.addStatement("sharedStates[$L] = this", stateId)
							.endControlFlow()
							.addStatement("return sharedStates")
							.build());
			this.readPlanCompiler = readPlanCompiler;
		}

		private GeneratedClass generate() {
			var dispatches = new ArrayList<VersionDispatch>();
			for (var version : dataModel.getVersionsSet()) {
				ComputedType input = typeNamed(version.getVersion(), currentType.getName());
				if (!(input instanceof ComputedTypeBase inputBase)) {
					throw generationError("input type is not a record in version " + version.getVersion());
				}
				dispatches.add(new VersionDispatch(version.getVersion(), ensureReader(inputBase, currentType),
						readPlanCompiler.compile(inputBase, currentType)));
			}

			do {
				generatePendingReaders();
				generatePendingFieldReaders();
			} while (!pendingReaders.isEmpty() || !pendingFieldReaders.isEmpty());
			generatePendingObjectMappers();
			dispatches = coalesceAdjacent(dispatches);

			classBuilder.addMethod(MethodSpec.methodBuilder("read")
					.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
					.returns(currentType.getJTypeName(basePackageName))
					.addParameter(TypeName.INT, "version")
					.addParameter(SafeDataInput.class, "input")
					.addStatement("return read(version, input, new $T())", planClassName.nestedClass("State"))
					.build());

			var read = MethodSpec.methodBuilder("read")
					.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
					.returns(currentType.getJTypeName(basePackageName))
					.addParameter(TypeName.INT, "version")
					.addParameter(SafeDataInput.class, "input")
					.addParameter(planClassName.nestedClass("State"), "state")
					.addStatement("$T.requireNonNull(input, $S)", Objects.class, "input")
					.addStatement("$T.requireNonNull(state, $S)", Objects.class, "state")
					.beginControlFlow("return switch (version)");
			for (VersionDispatch dispatch : dispatches) {
				read.addStatement("case $L -> $N(input, state)", dispatch.version(), dispatch.method());
			}
			read.addStatement("default -> throw new $T($S + version)", IllegalArgumentException.class,
						"Unsupported serialized version: ")
					.addCode("$<};\n");
			classBuilder.addMethod(read.build());
			for (StorageKernel kernel : StorageKernel.specialized()) {
				var specializedRead = MethodSpec.methodBuilder("read")
						.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
						.returns(currentType.getJTypeName(basePackageName))
						.addParameter(TypeName.INT, "version")
						.addParameter(kernel.inputType(), "input")
						.addParameter(planClassName.nestedClass("State"), "state")
						.beginControlFlow("return switch (version)");
				for (VersionDispatch dispatch : dispatches) {
					specializedRead.addStatement("case $L -> $N(input, state)", dispatch.version(),
							kernel.method(dispatch.method()));
				}
				specializedRead.addStatement("default -> throw new $T($S + version)", IllegalArgumentException.class,
							"Unsupported serialized version: ")
						.addCode("$<};\n");
				classBuilder.addMethod(specializedRead.build());
			}

			for (VersionDispatch dispatch : dispatches) {
				classBuilder.addMethod(MethodSpec.methodBuilder("readV" + dispatch.version())
						.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
						.returns(currentType.getJTypeName(basePackageName))
						.addParameter(SafeDataInput.class, "input")
						.addStatement("return $N(input, new $T())", dispatch.method(), planClassName.nestedClass("State"))
						.build());
				classBuilder.addMethod(MethodSpec.methodBuilder("readV" + dispatch.version())
						.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
						.returns(currentType.getJTypeName(basePackageName))
						.addParameter(SafeDataInput.class, "input")
						.addParameter(planClassName.nestedClass("State"), "state")
						.addStatement("return $N(input, state)", dispatch.method())
						.build());
				for (StorageKernel kernel : StorageKernel.specialized()) {
					classBuilder.addMethod(MethodSpec.methodBuilder("readV" + dispatch.version())
							.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
							.returns(currentType.getJTypeName(basePackageName))
							.addParameter(kernel.inputType(), "input")
							.addParameter(planClassName.nestedClass("State"), "state")
							.addStatement("return $N(input, state)", kernel.method(dispatch.method()))
							.build());
				}
			}
			addRequiredReaders(dispatches);
			generatePendingSkippers();
			classBuilder.addType(stateBuilder.build());
			return new GeneratedClass(planClassName.packageName(), classBuilder);
		}

		private ArrayList<VersionDispatch> coalesceAdjacent(ArrayList<VersionDispatch> dispatches) {
			var result = new ArrayList<VersionDispatch>(dispatches.size());
			VersionDispatch previous = null;
			for (VersionDispatch dispatch : dispatches) {
				if (previous != null && previous.plan() == dispatch.plan()) {
					dispatch = new VersionDispatch(dispatch.version(), previous.method(), previous.plan());
				}
				result.add(dispatch);
				previous = dispatch;
			}
			return result;
		}

		private void addRequiredReaders(List<VersionDispatch> dispatches) {
			Set<String> required = new java.util.LinkedHashSet<>();
			dispatches.stream().map(VersionDispatch::method).forEach(required::add);
			required.addAll(externallyRequiredReaders);
			required.addAll(generatedFieldReaders.keySet());
			var pending = new ArrayDeque<>(required);
			while (!pending.isEmpty()) {
				for (String dependency : readerDependencies.getOrDefault(pending.removeFirst(), Set.of())) {
					if (required.add(dependency)) pending.addLast(dependency);
				}
			}
			for (var entry : generatedReaders.entrySet()) {
				if (required.contains(entry.getKey())) entry.getValue().forEach(classBuilder::addMethod);
			}
		}

		private String ensureReader(ComputedType inputType, ComputedType targetType) {
			if (!canFuse(inputType, targetType)) {
				throw generationError("cannot fuse " + inputType + " into " + targetType);
			}
			ReaderKey key = new ReaderKey(inputType, targetType);
			String existing = readerMethods.get(key);
			String method;
			if (existing != null) {
				method = existing;
			} else {
				method = "readPlan" + nextReaderId++;
				readerMethods.put(key, method);
				pendingReaders.addLast(key);
			}
			if (activeGeneratedMethod != null && !activeGeneratedMethod.equals(method)) {
				readerDependencies.computeIfAbsent(activeGeneratedMethod,
						ignored -> new java.util.LinkedHashSet<>()).add(method);
			}
			return method;
		}

		private void generatePendingReaders() {
			while (!pendingReaders.isEmpty()) {
				ReaderKey key = pendingReaders.removeFirst();
				String baseMethodName = readerMethods.get(key);
				var variants = new ArrayList<MethodSpec>(StorageKernel.values().length);
				for (StorageKernel kernel : StorageKernel.values()) {
					var method = MethodSpec.methodBuilder(kernel.method(baseMethodName))
							.addModifiers(Modifier.PRIVATE, Modifier.STATIC)
							.returns(key.target().getJTypeName(basePackageName))
							.addParameter(kernel.inputType(), "input")
							.addParameter(planClassName.nestedClass("State"), "state");
					boolean vectorArrayOwnsLevel = key.input() instanceof ComputedTypeArray inputArray
							&& key.target() instanceof ComputedTypeArray targetArray
							&& vectorArrayMethod(inputArray, targetArray) != null;
					boolean ownsStructuralLevel = isStructural(key.input())
							&& !key.input().equals(key.target())
							&& !vectorArrayOwnsLevel;
					if (ownsStructuralLevel) {
						method.addStatement("input.decodeBudget().enterStructure()")
								.beginControlFlow("try");
					}
					String previousMethod = activeGeneratedMethod;
					StorageKernel previousKernel = activeKernel;
					activeGeneratedMethod = baseMethodName;
					activeKernel = kernel;
					try {
						emitReader(method, key.input(), key.target());
					} finally {
						activeGeneratedMethod = previousMethod;
						activeKernel = previousKernel;
					}
					if (ownsStructuralLevel) {
						method.nextControlFlow("finally")
								.addStatement("input.decodeBudget().exitStructure()")
								.endControlFlow();
					}
					variants.add(method.build());
				}
				generatedReaders.put(baseMethodName, List.copyOf(variants));
			}
		}

		private String ensureFieldReader(ComputedTypeBase inputType,
				ComputedTypeBase targetOwner,
				String targetField) {
			ComputedType resultType = targetOwner.getData().get(targetField);
			if (resultType == null) {
				throw generationError("missing lazy field " + targetOwner.getName() + "." + targetField);
			}
			return ensureFieldReader(inputType, targetOwner, targetField, resultType);
		}

		private String ensureFieldReader(ComputedTypeBase inputType,
				ComputedTypeBase targetOwner,
				String targetField,
				ComputedType resultType) {
			ComputedType declaredType = targetOwner.getData().get(targetField);
			if (declaredType == null) {
				throw generationError("missing lazy field " + targetOwner.getName() + "." + targetField);
			}
			if (!canFuse(declaredType, resultType)) {
				throw generationError("lazy field " + targetOwner.getName() + "." + targetField
						+ " cannot read " + declaredType + " as " + resultType);
			}
			FieldReaderKey key = new FieldReaderKey(inputType, targetOwner, targetField, resultType);
			String existing = fieldReaderMethods.get(key);
			if (existing != null) return existing;
			String method = "readField" + nextFieldReaderId++;
			fieldReaderMethods.put(key, method);
			pendingFieldReaders.addLast(key);
			return method;
		}

		private void generatePendingFieldReaders() {
			while (!pendingFieldReaders.isEmpty()) {
				FieldReaderKey key = pendingFieldReaders.removeFirst();
				String methodName = fieldReaderMethods.get(key);
				var method = MethodSpec.methodBuilder(methodName)
						.addModifiers(Modifier.PRIVATE, Modifier.STATIC)
						.returns(key.resultType().getJTypeName(basePackageName))
						.addParameter(SafeDataInput.class, "input")
						.addParameter(planClassName.nestedClass("State"), "state")
						.addStatement("input.decodeBudget().enterStructure()")
						.beginControlFlow("try");
				String previous = activeGeneratedMethod;
				activeGeneratedMethod = methodName;
				try {
					RecordPlan plan = new RecordPlan(key.input(), key.targetOwner(),
							List.of(key.targetField()), List.of(key.resultType()));
					plan.compile();
					plan.emit(method);
				} finally {
					activeGeneratedMethod = previous;
				}
				method.nextControlFlow("finally")
						.addStatement("input.decodeBudget().exitStructure()")
						.endControlFlow();
				MethodSpec generated = method.build();
				generatedFieldReaders.put(generated.name(), generated);
				classBuilder.addMethod(generated);
			}
		}

		private static boolean isStructural(ComputedType type) {
			return type instanceof ComputedTypeBase
					|| type instanceof ComputedTypeSuper
					|| type instanceof ComputedTypeArray
					|| type instanceof ComputedTypeNullable;
		}

		private void emitReader(MethodSpec.Builder method, ComputedType inputType, ComputedType targetType) {
			if (inputType.equals(targetType)) {
				method.addStatement("return $L", readExact(inputType));
				return;
			}
			if (inputType instanceof ComputedTypeBase inputBase && targetType instanceof ComputedTypeBase targetBase) {
				if (targetBase.getVersion().isCurrent()
						&& !targetBase.getName().equals(currentType.getName())) {
					ClassName targetPlan = GenReadPlan.className(basePackageName,
							dataModel.getCurrentVersion().getPackage(basePackageName), targetBase.getName());
					method.addStatement("return $T.readV$L(input, state.$N())", targetPlan,
							inputBase.getVersion().getVersion(), ensureSharedStateAccessor(targetBase));
					return;
				}
				emitRecordReader(method, inputBase, targetBase);
				return;
			}
			if (inputType instanceof ComputedTypeNullable inputNullable
					&& targetType instanceof ComputedTypeNullable targetNullable) {
				NullableWireEmitter.emitPresence(method, inputNullable, CodeBlock.of("input"),
						"nullablePresent", "nullableFirst");
				CodeBlock value = NullableWireEmitter.valueExpression(inputNullable, binaryStrings,
						CodeBlock.of("input"), "nullableFirst",
						readFused(inputNullable.getBase(), targetNullable.getBase()));
				method.addStatement("return nullablePresent ? $T.of(($T) $L) : $T.empty()",
						targetType.getJTypeName(basePackageName), targetNullable.getBase().getJTypeName(basePackageName),
						value, targetType.getJTypeName(basePackageName));
				return;
			}
			if (inputType instanceof ComputedTypeArray inputArray
					&& targetType instanceof ComputedTypeArray targetArray) {
				String vectorMethod = vectorArrayMethod(inputArray, targetArray);
				if (vectorMethod != null) {
					method.addStatement("return $T.$N(input)", VECTOR_ARRAY_SUPPORT, vectorMethod);
					return;
				}
				ClassName targetCodec = targetArray.getJSerializerName(basePackageName);
				method.addStatement("int size = $T.readLength(input)", ProjectionReadSupport.class)
						.addStatement("$T.prepareArrayAllocation(input, size, $L)", ProjectionReadSupport.class,
								readPlanCompiler.minimumSerializedSize(inputArray.getBase()))
						.beginControlFlow("if (size == 0)")
						.addStatement("return $T.emptyArray()", targetCodec)
						.endControlFlow()
						.addStatement("$T values = new $T[size]", targetType.getJTypeName(basePackageName),
								targetArray.getBase().getJTypeName(basePackageName))
						.beginControlFlow("for (int i = 0; i < size; i++)")
						.addStatement("values[i] = $L", readFused(inputArray.getBase(), targetArray.getBase()))
						.endControlFlow()
						.addStatement("return values");
				return;
			}
			if (inputType instanceof ComputedTypeSuper inputUnion
					&& targetType instanceof ComputedTypeSuper targetUnion) {
				method.addStatement("int id = input.readUnsignedByte()")
						.beginControlFlow("return switch (id)");
				for (int i = 0; i < inputUnion.subTypes().size(); i++) {
					ComputedType inputSubtype = inputUnion.subTypes().get(i);
					ComputedType targetSubtype = targetUnion.subTypes().stream()
							.filter(type -> type.getName().equals(inputSubtype.getName()))
							.findFirst()
							.orElseThrow(() -> generationError("union subtype " + inputSubtype.getName()
									+ " was removed from " + targetUnion.getName()));
					method.addStatement("case $L -> ($T) $L", i, targetType.getJTypeName(basePackageName),
							readFused(inputSubtype, targetSubtype));
				}
				method.addStatement("default -> throw new $T($S + id)", MalformedDataException.class,
						"Invalid union discriminator: ")
						.addCode("$<};\n");
				return;
			}
			throw generationError("unsupported fused pair " + inputType + " -> " + targetType);
		}

		private String ensureObjectMapper(ComputedType inputType, ComputedType targetType) {
			if (inputType.equals(targetType) || !canFuse(inputType, targetType)) {
				throw generationError("cannot fuse materialized " + inputType + " into " + targetType);
			}
			ObjectMapperKey key = new ObjectMapperKey(inputType, targetType);
			String existing = objectMapperMethods.get(key);
			if (existing != null) return existing;
			String method = "upgradePlan" + nextObjectMapperId++;
			objectMapperMethods.put(key, method);
			pendingObjectMappers.addLast(key);
			return method;
		}

		private void generatePendingObjectMappers() {
			while (!pendingObjectMappers.isEmpty()) {
				ObjectMapperKey key = pendingObjectMappers.removeFirst();
				var method = MethodSpec.methodBuilder(objectMapperMethods.get(key))
						.addModifiers(Modifier.PRIVATE, Modifier.STATIC)
						.returns(key.target().getJTypeName(basePackageName))
						.addParameter(key.input().getJTypeNameGeneric(basePackageName), "source");
				emitObjectMapper(method, key.input(), key.target());
				classBuilder.addMethod(method.build());
			}
		}

		private void emitObjectMapper(MethodSpec.Builder method,
				ComputedType inputType,
				ComputedType targetType) {
			if (inputType instanceof ComputedTypeBase inputBase && targetType instanceof ComputedTypeBase targetBase) {
				if (!inputBase.getName().equals(targetBase.getName())) {
					throw generationError("materialized record changed identity from " + inputBase.getName()
							+ " to " + targetBase.getName());
				}
				ObjectRecordPlan plan = new ObjectRecordPlan(inputBase, targetBase);
				plan.compile();
				plan.emit(method);
				return;
			}
			if (inputType instanceof ComputedTypeNullable inputNullable
					&& targetType instanceof ComputedTypeNullable targetNullable) {
				method.addStatement("$T value = source.getNullable()",
						inputNullable.getBase().getJTypeName(basePackageName))
						.beginControlFlow("if (value == null)")
						.addStatement("return $T.empty()", targetType.getJTypeName(basePackageName))
						.endControlFlow()
						.addStatement("return $T.of(($T) $L)", targetType.getJTypeName(basePackageName),
							targetNullable.getBase().getJTypeName(basePackageName),
							upgradeObject(CodeBlock.of("value"), inputNullable.getBase(), targetNullable.getBase()));
				return;
			}
			if (inputType instanceof ComputedTypeArray inputArray
					&& targetType instanceof ComputedTypeArray targetArray) {
				ClassName targetCodec = targetArray.getJSerializerName(basePackageName);
				method.addStatement("int size = source.length")
						.beginControlFlow("if (size == 0)")
						.addStatement("return $T.emptyArray()", targetCodec)
						.endControlFlow()
						.addStatement("$T values = new $T[size]", targetType.getJTypeName(basePackageName),
								targetArray.getBase().getJTypeName(basePackageName))
						.beginControlFlow("for (int i = 0; i < size; i++)")
						.addStatement("values[i] = $L", upgradeObject(CodeBlock.of("source[i]"),
								inputArray.getBase(), targetArray.getBase()))
						.endControlFlow()
						.addStatement("return values");
				return;
			}
			if (inputType instanceof ComputedTypeSuper inputUnion
					&& targetType instanceof ComputedTypeSuper targetUnion) {
				method.beginControlFlow("return switch (source.getMetaId$$$N())", inputUnion.getName());
				for (int i = 0; i < inputUnion.subTypes().size(); i++) {
					ComputedType inputSubtype = inputUnion.subTypes().get(i);
					ComputedType targetSubtype = targetUnion.subTypes().stream()
							.filter(type -> type.getName().equals(inputSubtype.getName()))
							.findFirst()
							.orElseThrow(() -> generationError("union subtype " + inputSubtype.getName()
									+ " was removed from " + targetUnion.getName()));
					method.addStatement("case $L -> ($T) $L", i, targetType.getJTypeName(basePackageName),
							upgradeObject(CodeBlock.of("($T) source", inputSubtype.getJTypeName(basePackageName)),
									inputSubtype, targetSubtype));
				}
				method.addStatement("default -> throw new $T($S + source.getMetaId$$$N())",
						IllegalArgumentException.class, "Invalid union discriminator: ", inputUnion.getName())
						.addCode("$<};\n");
				return;
			}
			throw generationError("unsupported materialized fused pair " + inputType + " -> " + targetType);
		}

		private void emitRecordReader(MethodSpec.Builder method,
				ComputedTypeBase inputBase,
				ComputedTypeBase targetBase) {
			if (!inputBase.getName().equals(targetBase.getName())) {
				throw generationError("record changed identity from " + inputBase.getName() + " to " + targetBase.getName());
			}
			RecordPlan plan = new RecordPlan(inputBase, targetBase);
			plan.compile();
			plan.emit(method);
		}

		private CodeBlock readFused(ComputedType inputType, ComputedType targetType) {
			if (inputType.equals(targetType)) return readExact(inputType);
			if (inputType instanceof ComputedTypeBase inputBase
					&& targetType instanceof ComputedTypeBase targetBase
					&& targetBase.getVersion().isCurrent()
					&& !targetBase.getName().equals(currentType.getName())) {
				ClassName targetPlan = GenReadPlan.className(basePackageName,
						dataModel.getCurrentVersion().getPackage(basePackageName), targetBase.getName());
				return CodeBlock.of("$T.readV$L(input, state.$N())", targetPlan,
						inputBase.getVersion().getVersion(), ensureSharedStateAccessor(targetBase));
			}
			return CodeBlock.of("$N(input, state)", activeKernel.method(ensureReader(inputType, targetType)));
		}

		private String ensureSharedStateAccessor(ComputedTypeBase targetType) {
			String existing = sharedStateAccessors.get(targetType.getName());
			if (existing != null) return existing;
			String name = "sharedState" + sharedStateAccessors.size();
			sharedStateAccessors.put(targetType.getName(), name);
			ClassName targetPlan = GenReadPlan.className(basePackageName,
					dataModel.getCurrentVersion().getPackage(basePackageName), targetType.getName());
			TypeName targetState = targetPlan.nestedClass("State");
			stateBuilder.addField(targetState, name, Modifier.PRIVATE)
					.addMethod(MethodSpec.methodBuilder(name)
							.returns(targetState)
							.beginControlFlow("if ($N == null)", name)
							.addStatement("$N = $T.sharedState(sharedStates())", name, targetState)
							.endControlFlow()
							.addStatement("return $N", name)
							.build());
			return name;
		}

		private String ensureWireTransformCursor(WireTransformCursorKey key) {
			String existing = wireTransformCursors.get(key);
			if (existing != null) return existing;
			String name = "wireTransformCursor" + nextWireTransformCursorId++;
			wireTransformCursors.put(key, name);
			TypeName cursorType = key.kernel().randomAccess() ? key.kernel().inputType() : ClassName.get(BufDataCursor.class);
			stateBuilder.addField(FieldSpec.builder(cursorType, name, Modifier.PRIVATE, Modifier.FINAL)
					.initializer("$T.borrowed()", cursorType)
					.build());
			return name;
		}

		private CodeBlock readExact(ComputedType type) {
			if (vectorKernels && type instanceof ComputedTypeArray array) {
				String vectorMethod = vectorArrayMethod(array, array);
				if (vectorMethod != null) return CodeBlock.of("$T.$N(input)", VECTOR_ARRAY_SUPPORT, vectorMethod);
			}
			if (type instanceof ComputedTypeNative nativeType && nativeType.isPrimitive()) {
				return CodeBlock.of("input.read$N()", capitalize(nativeType.getName()));
			}
			if (type instanceof ComputedTypeCustom custom) {
				return CodeBlock.of("($T) $L.read(input)", type.getJTypeName(basePackageName),
						customSession(custom, CodeBlock.of("input")));
			}
			FieldLocation serializer = type.getJSerializerInstance(basePackageName);
			return CodeBlock.of("($T) $T.$N.read(input)", type.getJTypeName(basePackageName),
					serializer.className(), serializer.fieldName());
		}

		private CodeBlock customSession(ComputedTypeCustom custom, CodeBlock input) {
			FieldLocation codec = custom.getJSerializerInstance(basePackageName);
			return CodeBlock.of("$L.decodeBudget().codecReadState().session($S, $T.$N)", input,
					custom.getName(), codec.className(), codec.fieldName());
		}

		private String vectorArrayMethod(ComputedTypeArray inputArray, ComputedTypeArray targetArray) {
			if (!vectorKernels
					|| !(inputArray.getBase() instanceof ComputedTypeNative inputNative)
					|| !(targetArray.getBase() instanceof ComputedTypeNative targetNative)
					|| !inputNative.getName().equals(targetNative.getName())) {
				return null;
			}
			return switch (inputNative.getName()) {
				case "boolean" -> "readBooleanArray";
				case "byte" -> "readByteArray";
				case "short" -> "readShortArray";
				case "char" -> "readCharArray";
				case "int" -> "readIntArray";
				case "long" -> "readLongArray";
				case "float" -> "readFloatArray";
				case "double" -> "readDoubleArray";
				case "Int52" -> "readInt52Array";
				default -> null;
			};
		}

		private ComputedType currentRepresentation(ComputedType oldType) {
			return dataModel.getCurrentStructuralRepresentation(oldType);
		}

		private boolean canFuse(ComputedType inputType, ComputedType targetType) {
			return DataModel.canStructurallyFuse(inputType, targetType);
		}

		private final class RecordPlan {

			private final ComputedTypeBase inputBase;
			private final ComputedTypeBase targetBase;
			private final int inputVersion;
			private final int targetVersion;
			private final List<String> outputFields;
			private final List<ComputedType> outputTypes;
			private final boolean constructTarget;
			private final Map<WireResolveKey, ResolvedValue> resolved = new HashMap<>();
			private final LinkedHashMap<String, RawValue> rawValues = new LinkedHashMap<>();
			private final List<PreparedComputation> preparedValues = new ArrayList<>();
			private boolean usesReadUpgrade;
			private boolean usesRecordRegion;
			private int nextTransformLocalId;
			private List<ResolvedValue> outputs;
			private String activeOutputField;
			private ComputedType activeOutputType;

			private RecordPlan(ComputedTypeBase inputBase, ComputedTypeBase targetBase) {
				this(inputBase, targetBase, List.copyOf(targetBase.getData().keySet()),
						List.copyOf(targetBase.getData().values()));
			}

			private RecordPlan(ComputedTypeBase inputBase,
					ComputedTypeBase targetBase,
					List<String> outputFields,
					List<ComputedType> outputTypes) {
				this.inputBase = inputBase;
				this.targetBase = targetBase;
				this.inputVersion = inputBase.getVersion().getVersion();
				this.targetVersion = targetBase.getVersion().getVersion();
				this.outputFields = List.copyOf(outputFields);
				this.outputTypes = List.copyOf(outputTypes);
				if (this.outputFields.size() != this.outputTypes.size()) {
					throw generationError("read-plan output field/type count mismatch");
				}
				this.constructTarget = this.outputFields.equals(List.copyOf(targetBase.getData().keySet()));
			}

			private void compile() {
				var compiledOutputs = new ArrayList<ResolvedValue>(outputFields.size());
				for (int i = 0; i < outputFields.size(); i++) {
					String field = outputFields.get(i);
					activeOutputField = field;
					activeOutputType = outputTypes.get(i);
					ResolvedValue output = resolveAt(targetVersion, field, true);
					ComputedType requestedType = activeOutputType;
					if (!output.type().equals(requestedType)) {
						output = upgradeValue(output, output.type(), requestedType);
					}
					compiledOutputs.add(output);
				}
				activeOutputField = null;
				activeOutputType = null;
				outputs = List.copyOf(compiledOutputs);
				for (int index = 0; index < outputFields.size(); index++) {
					String field = outputFields.get(index);
					ComputedType expected = outputTypes.get(index);
					ResolvedValue output = outputs.get(index);
					if (!output.type().equals(expected)) {
						throw generationError("field plan resolved " + output.type() + " instead of " + expected);
					}
				}
			}

			private ResolvedValue resolveAt(int logicalVersion, String fieldName, boolean allowTerminal) {
				WireResolveKey key = new WireResolveKey(logicalVersion, fieldName, activeOutputField, allowTerminal);
				ResolvedValue cached = resolved.get(key);
				if (cached != null) return cached;
				ResolvedValue value;
				if (logicalVersion == inputVersion) {
					ComputedType sourceType = inputBase.getData().get(fieldName);
					if (sourceType == null) {
						throw generationError("missing input field " + inputBase.getName() + "." + fieldName);
					}
					RawValue raw = rawValues.computeIfAbsent(fieldName,
							ignored -> new RawValue("raw" + rawValues.size(), fieldName, sourceType));
					value = new ResolvedValue(sourceType, raw, null);
				} else {
					ComputedTypeBase nextOwner = requireBase(logicalVersion, inputBase.getName());
					ComputedTypeBase previousOwner = requireBase(logicalVersion - 1, inputBase.getName());
					ComputedType nextFieldType = nextOwner.getData().get(fieldName);
					if (nextFieldType == null) {
						throw generationError("missing target field " + nextOwner.getName() + "." + fieldName);
					}
					FieldOrigin origin = traceFieldOrigin(logicalVersion, inputBase.getName(), fieldName);
					ComputedType operationType;
					if (origin.initializer() != null) {
						operationType = typeNamed(logicalVersion, DataModel.fixType(origin.initializer().type));
						value = applyInitializer(logicalVersion, previousOwner, origin.initializer(), operationType,
								allowTerminal);
					} else {
						operationType = previousOwner.getData().get(origin.previousName());
						if (operationType == null) {
							throw generationError("missing previous field " + previousOwner.getName() + "."
									+ origin.previousName());
						}
						value = resolveAt(logicalVersion - 1, origin.previousName(), allowTerminal);
					}
					if (value.terminal()) {
						resolved.put(key, value);
						return value;
					}

					for (UpgradeDataConfiguration upgrade : origin.upgrades()) {
						ComputedType newType = typeNamed(logicalVersion, DataModel.fixType(upgrade.type));
						value = applyExplicitUpgrade(logicalVersion, previousOwner, upgrade,
								operationType, newType, value, allowTerminal);
						operationType = newType;
						if (value.terminal()) break;
					}
					if (!value.terminal()) value = upgradeValue(value, operationType, nextFieldType);
				}
				resolved.put(key, value);
				return value;
			}

			private ResolvedValue applyInitializer(int logicalVersion,
					ComputedTypeBase previousOwner,
					NewDataConfiguration initializer,
					ComputedType newType,
					boolean allowTerminal) {
				if (initializer.hasReadTransform()) {
					ComputedType readResultType = initializer.hasReadTransformTypeOverride()
							? typeNamed(dataModel.getCurrentVersion().getVersion(),
									DataModel.fixType(initializer.getReadTransformType()))
							: newType;
					ComputedType expectedOutput = activeOutputType;
					boolean terminal = allowTerminal && initializer.hasReadTransformTypeOverride()
							&& readResultType.equals(expectedOutput);
					if (allowTerminal && initializer.hasReadTransformTypeOverride()
							&& targetVersion == dataModel.getCurrentVersion().getVersion() && !terminal) {
						throw generationError("readTransform.type " + initializer.getReadTransformType()
								+ " does not match current field " + targetBase.getName() + "."
								+ activeOutputField + " of type " + expectedOutput);
					}
					if (!initializer.hasReadTransformTypeOverride() || terminal) {
						if (initializer.getReadTransform().isCustom()) {
							return applyReadInitializer(logicalVersion, previousOwner, initializer,
									readResultType, terminal);
						}
						return applyDeclarativeReadTransform(logicalVersion, previousOwner,
								initializer.getContextParameters(), initializer.getReadTransform(),
								readResultType, null, null, terminal);
					}
				}
				TransformSupport support = transformSupports.computeIfAbsent(initializer,
						ignored -> createInitializerSupport(initializer, previousOwner, newType));
				CodeBlock context = contextCall(logicalVersion, previousOwner,
						initializer.to, initializer.getContextParameters());
				return prepare(newType, CodeBlock.of("$N.initialize($L)", support.fieldName(), context));
			}

			private ResolvedValue applyReadInitializer(int logicalVersion,
					ComputedTypeBase previousOwner,
					NewDataConfiguration initializer,
					ComputedType readResultType,
					boolean terminal) {
				TransformSupport support = readTransformSupports.computeIfAbsent(initializer,
						ignored -> createReadInitializerSupport(initializer, previousOwner));
				List<LazyContext> contexts = resolveLazyContexts(logicalVersion, previousOwner,
						initializer.getContextParameters());
				if (contexts.isEmpty()) {
					int id = nextReadFrameId++;
					String emptyInputName = "EmptyReadInitializerInput" + id;
					TypeName inputType = previousOwner.getJUpgraderName(basePackageName)
							.nestedClass(GenUpgraderBaseX.readInitializerInputInterfaceName(initializer.to));
					classBuilder.addType(TypeSpec.enumBuilder(emptyInputName)
							.addModifiers(Modifier.PRIVATE)
							.addSuperinterface(inputType)
							.addEnumConstant("INSTANCE")
							.build());
					return prepare(readResultType,
							CodeBlock.of("$N.initialize($N.INSTANCE)", support.fieldName(), emptyInputName), terminal);
				}
				usesReadUpgrade = true;
				boolean needsRecordRegion = contexts.stream().anyMatch(context -> !context.direct());
				usesRecordRegion |= needsRecordRegion;
				String helper = generateReadInitializerFrame(initializer, support, previousOwner,
						readResultType, contexts, needsRecordRegion);
				var call = CodeBlock.builder().add("$N(randomInput, state", helper);
				if (needsRecordRegion) call.add(", recordStart, recordLength");
				for (LazyContext context : contexts) {
					if (context.direct()) {
						call.add(", $N, $N", context.regionStartVariable(), context.regionLengthVariable());
					}
				}
				return prepare(readResultType, call.add(")").build(), terminal);
			}

			private List<LazyContext> resolveLazyContexts(int logicalVersion,
					ComputedTypeBase previousOwner,
					List<String> parameters) {
				var contexts = new ArrayList<LazyContext>(parameters.size());
				for (String parameter : parameters) {
					ComputedType type = previousOwner.getData().get(parameter);
					if (type == null) {
						throw generationError("unknown optimized context field " + previousOwner.getName()
								+ "." + parameter);
					}
					ComputedType currentContextType = currentRepresentation(type);
					String currentContextReader;
					DirectField direct = traceDirectField(inputBase, previousOwner, parameter);
					if (direct != null) {
						RawValue contextRaw = rawValues.computeIfAbsent(direct.inputField(),
								ignored -> new RawValue("raw" + rawValues.size(), direct.inputField(),
										direct.sourceType()));
						contextRaw.requestRegion();
						String reader = ensureReader(direct.sourceType(), type);
						externallyRequiredReaders.add(reader);
						if (currentContextType != null) {
							currentContextReader = ensureReader(direct.sourceType(), currentContextType);
							externallyRequiredReaders.add(currentContextReader);
						} else {
							currentContextReader = null;
						}
						contexts.add(new LazyContext(parameter, type, currentContextType, null, reader,
								currentContextReader, contextRaw.regionStartVariable(),
								contextRaw.regionLengthVariable()));
					} else {
						currentContextReader = currentContextType == null ? null
								: ensureFieldReader(inputBase, previousOwner, parameter, currentContextType);
						contexts.add(new LazyContext(parameter, type, currentContextType,
								ensureFieldReader(inputBase, previousOwner, parameter), null,
								currentContextReader, null, null));
					}
				}
				return List.copyOf(contexts);
			}

			private ResolvedValue applyDeclarativeReadTransform(int logicalVersion,
					ComputedTypeBase previousOwner,
					List<String> contextParameters,
					ReadTransformConfiguration transform,
					ComputedType resultType,
					ResolvedValue oldValue,
					ComputedType oldType,
					boolean terminal) {
				// readTransform is a random-access fast path. Plain streams retain the declared
				// object initializer/upgrader semantics and therefore take RecordPlan's fallback.
				usesReadUpgrade = true;
				if (transform.kind() == ReadTransformConfiguration.Kind.IDENTITY) {
					ResolvedValue result = resolveReadReference(transform.identity.source, logicalVersion,
							previousOwner, contextParameters, oldValue, oldType);
					if (!result.type().equals(resultType)) result = upgradeValue(result, result.type(), resultType);
					return terminal
							? new ResolvedValue(resultType, result.raw(), result.fixedCode, true)
							: result;
				}
				TransformExpression expression = compileReadTransformExpression(transform, resultType, logicalVersion,
						previousOwner, contextParameters, oldValue, oldType);
				if (!expression.type().equals(resultType)) {
					throw generationError("readTransform produced " + expression.type() + " instead of " + resultType);
				}
				return prepareTransform(resultType, expression, terminal);
			}

			private TransformExpression compileReadTransformExpression(ReadTransformConfiguration transform,
					ComputedType expectedType,
					int logicalVersion,
					ComputedTypeBase previousOwner,
					List<String> contextParameters,
					ResolvedValue oldValue,
					ComputedType oldType) {
				ComputedType configuredType = readTransformType(transform, expectedType);
				return switch (transform.kind()) {
					case CUSTOM -> throw generationError("custom readTransform is only valid at a transformation root");
					case IDENTITY -> {
						ResolvedValue referenced = resolveReadReference(transform.identity.source, logicalVersion,
								previousOwner, contextParameters, oldValue, oldType);
						if (configuredType != null && !referenced.type().equals(configuredType)) {
							referenced = upgradeValue(referenced, referenced.type(), configuredType);
						}
						ComputedType type = configuredType == null ? referenced.type() : configuredType;
						yield new ImmediateTransformExpression(type, materialize(referenced, type));
					}
					case CONSTANT -> {
						if (configuredType == null) {
							throw generationError("constant readTransform requires an expected or explicit type");
						}
						yield new ImmediateTransformExpression(configuredType,
								literal(transform.constant.value, configuredType));
					}
					case INVOKE_STATIC -> {
						if (configuredType == null) {
							throw generationError("invokeStatic readTransform requires an expected or explicit type");
						}
						StaticMethod method = staticMethod(transform.invokeStatic.method);
						var arguments = new ArrayList<TransformExpression>();
						for (ReadTransformConfiguration argument : transform.invokeStatic.getArguments()) {
							arguments.add(compileReadTransformExpression(argument, null, logicalVersion,
									previousOwner, contextParameters, oldValue, oldType));
						}
						yield new CallTransformExpression(configuredType, TransformCallKind.STATIC,
								method.owner(), method.method(), List.copyOf(arguments));
					}
					case CONSTRUCT -> {
						ComputedType constructedType = constructType(transform, configuredType);
						var arguments = new ArrayList<TransformExpression>();
						for (ReadTransformConfiguration argument : transform.construct.getArguments()) {
							arguments.add(compileReadTransformExpression(argument, null, logicalVersion,
									previousOwner, contextParameters, oldValue, oldType));
						}
						ClassName owner = transform.construct.className == null
								? (ClassName) constructedType.getJTypeName(basePackageName)
								: ClassName.bestGuess(transform.construct.className);
						TransformCallKind callKind;
						String method;
						if (transform.construct.factory != null && !transform.construct.factory.isBlank()) {
							callKind = TransformCallKind.STATIC;
							method = transform.construct.factory;
						} else if (transform.construct.className != null) {
							callKind = TransformCallKind.CONSTRUCTOR;
							method = null;
						} else if (constructedType instanceof ComputedTypeBase) {
							callKind = TransformCallKind.STATIC;
							method = "unsafeOfOwned";
						} else if (constructedType instanceof ComputedTypeNullable) {
							callKind = TransformCallKind.STATIC;
							method = "of";
						} else {
							throw generationError("construct readTransform for " + constructedType
									+ " requires className or factory");
						}
						yield new CallTransformExpression(constructedType, callKind, owner, method,
								List.copyOf(arguments));
					}
					case MAP_ARRAY -> compileArrayMap(transform, configuredType, logicalVersion, previousOwner,
							contextParameters, oldValue, oldType);
					case MAP_NULLABLE -> compileNullableMap(transform, configuredType, logicalVersion, previousOwner,
							contextParameters, oldValue, oldType);
				};
			}

			private TransformExpression compileArrayMap(ReadTransformConfiguration transform,
					ComputedType configuredType,
					int logicalVersion,
					ComputedTypeBase previousOwner,
					List<String> contextParameters,
					ResolvedValue oldValue,
					ComputedType oldType) {
				if (!(configuredType instanceof ComputedTypeArray targetArray)) {
					throw generationError("mapArray readTransform requires an array result type");
				}
				if (oldValue != null && oldValue.raw() != null && oldType instanceof ComputedTypeArray sourceArray
						&& isValueIdentity(transform.mapArray.source)) {
					RawValue raw = oldValue.raw();
					raw.requestRegion();
					int id = nextTransformLocalId++;
					String cursorName = "wireMapArrayCursor" + id;
					String targetName = "wireMapArrayTarget" + id;
					String indexName = "wireMapArrayIndex" + id;
					String elementName = "wireMapArrayElement" + id;
					WireRecordElementReadPlan wireElementReadPlan = null;
					ResolvedValue element;
					if (sourceArray.getBase() instanceof ComputedTypeBase recordElement
							&& canCompileWireRecordElementTransform(transform.mapArray.transform)) {
						wireElementReadPlan = new WireRecordElementReadPlan(recordElement, cursorName,
								previousOwner.getName() + "." + activeOutputField + "[]");
						element = new ResolvedValue(sourceArray.getBase(), null, null, false, wireElementReadPlan);
					} else {
						element = new ResolvedValue(sourceArray.getBase(), null, CodeBlock.of("$N", elementName));
					}
					TransformExpression elementTransform = compileReadTransformExpression(
							transform.mapArray.transform, targetArray.getBase(), logicalVersion, previousOwner,
							contextParameters, element, sourceArray.getBase());
					CodeBlock readElement = null;
					if (wireElementReadPlan == null) {
						if (sourceArray.getBase() instanceof ComputedTypeNative nativeType && nativeType.isPrimitive()) {
							readElement = CodeBlock.of("$N.read$N()", cursorName, capitalize(nativeType.getName()));
						} else {
							String reader = ensureReader(sourceArray.getBase(), sourceArray.getBase());
							externallyRequiredReaders.add(reader);
							readElement = CodeBlock.of("$N($N, state)", reader, cursorName);
						}
					}
					String stateCursor = ensureWireTransformCursor(new WireTransformCursorKey("array", inputBase,
							raw.fieldName, transform, activeKernel));
					TypeName cursorType = activeKernel.randomAccess() ? activeKernel.inputType()
							: ClassName.get(BufDataCursor.class);
					String elementSkipper = ensureSkipper(sourceArray.getBase());
					return new WireArrayMapTransformExpression(targetArray, sourceArray, elementTransform,
							cursorName, stateCursor, targetName, indexName, elementName, readElement,
							wireElementReadPlan,
							cursorType,
							CodeBlock.of("$N", raw.regionStartVariable()),
							CodeBlock.of("$N", raw.regionLengthVariable()),
							targetArray.getJSerializerName(basePackageName),
							fixedSerializedSize(sourceArray.getBase()),
							readPlanCompiler.minimumSerializedSize(sourceArray.getBase()), elementSkipper);
				}
				TransformExpression source = compileReadTransformExpression(transform.mapArray.source, null,
						logicalVersion, previousOwner, contextParameters, oldValue, oldType);
				if (!(source.type() instanceof ComputedTypeArray sourceArray)) {
					throw generationError("mapArray source produced " + source.type() + " instead of an array");
				}
				int id = nextTransformLocalId++;
				String sourceName = "mapArraySource" + id;
				String targetName = "mapArrayTarget" + id;
				String indexName = "mapArrayIndex" + id;
				ResolvedValue element = new ResolvedValue(sourceArray.getBase(), null,
						CodeBlock.of("$N[$N]", sourceName, indexName));
				TransformExpression elementTransform = compileReadTransformExpression(transform.mapArray.transform,
						targetArray.getBase(), logicalVersion, previousOwner, contextParameters,
						element, sourceArray.getBase());
				if (!elementTransform.type().equals(targetArray.getBase())) {
					throw generationError("mapArray element transform produced " + elementTransform.type()
							+ " instead of " + targetArray.getBase());
				}
				return new ArrayMapTransformExpression(targetArray, sourceArray, source, elementTransform,
						sourceName, targetName, indexName, targetArray.getJSerializerName(basePackageName));
			}

			private boolean isValueIdentity(ReadTransformConfiguration transform) {
				return transform.kind() == ReadTransformConfiguration.Kind.IDENTITY
						&& "value".equals(transform.identity.source)
						&& (transform.type == null || transform.type.isBlank());
			}

			private boolean canCompileWireRecordElementTransform(ReadTransformConfiguration transform) {
				var valuePaths = new ArrayList<String>();
				if (!collectWireRecordValuePaths(transform, valuePaths)) return false;
				for (int left = 0; left < valuePaths.size(); left++) {
					for (int right = left + 1; right < valuePaths.size(); right++) {
						String a = valuePaths.get(left);
						String b = valuePaths.get(right);
						if (!a.equals(b) && (a.startsWith(b + ".") || b.startsWith(a + "."))) return false;
					}
				}
				return true;
			}

			private boolean collectWireRecordValuePaths(ReadTransformConfiguration transform,
					List<String> valuePaths) {
				return switch (transform.kind()) {
					case CONSTANT -> true;
					case IDENTITY -> {
						String source = transform.identity.source;
						if (source.equals("value") || source.startsWith("currentValue")) yield false;
						if (source.startsWith("value.")) valuePaths.add(source.substring("value.".length()));
						yield true;
					}
					case INVOKE_STATIC -> transform.invokeStatic.getArguments().stream()
							.allMatch(argument -> collectWireRecordValuePaths(argument, valuePaths));
					case CONSTRUCT -> transform.construct.getArguments().stream()
							.allMatch(argument -> collectWireRecordValuePaths(argument, valuePaths));
					case CUSTOM, MAP_ARRAY, MAP_NULLABLE -> false;
				};
			}

			private TransformExpression compileNullableMap(ReadTransformConfiguration transform,
					ComputedType configuredType,
					int logicalVersion,
					ComputedTypeBase previousOwner,
					List<String> contextParameters,
					ResolvedValue oldValue,
					ComputedType oldType) {
				if (!(configuredType instanceof ComputedTypeNullable targetNullable)) {
					throw generationError("mapNullable readTransform requires a nullable result type");
				}
				if (oldValue != null && oldValue.raw() != null && oldType instanceof ComputedTypeNullable sourceNullable
						&& isValueIdentity(transform.mapNullable.source)) {
					RawValue raw = oldValue.raw();
					raw.requestRegion();
					int id = nextTransformLocalId++;
					String cursorName = "wireMapNullableCursor" + id;
					String targetName = "wireMapNullableTarget" + id;
					String elementName = "wireMapNullableElement" + id;
					WireRecordElementReadPlan wireElementReadPlan = null;
					ResolvedValue element;
					if (sourceNullable.getBase() instanceof ComputedTypeBase recordElement
							&& canCompileWireRecordElementTransform(transform.mapNullable.transform)) {
						wireElementReadPlan = new WireRecordElementReadPlan(recordElement, cursorName,
								previousOwner.getName() + "." + activeOutputField + "?");
						element = new ResolvedValue(sourceNullable.getBase(), null, null, false,
								wireElementReadPlan);
					} else {
						element = new ResolvedValue(sourceNullable.getBase(), null,
								CodeBlock.of("$N", elementName));
					}
					TransformExpression elementTransform = compileReadTransformExpression(
							transform.mapNullable.transform, targetNullable.getBase(), logicalVersion, previousOwner,
							contextParameters, element, sourceNullable.getBase());
					CodeBlock readElement = null;
					if (wireElementReadPlan == null) {
						if (sourceNullable.getBase() instanceof ComputedTypeNative nativeType && nativeType.isPrimitive()) {
							readElement = CodeBlock.of("$N.read$N()", cursorName, capitalize(nativeType.getName()));
						} else {
							String reader = ensureReader(sourceNullable.getBase(), sourceNullable.getBase());
							externallyRequiredReaders.add(reader);
							readElement = CodeBlock.of("$N($N, state)", reader, cursorName);
						}
					}
					String stateCursor = ensureWireTransformCursor(new WireTransformCursorKey("nullable", inputBase,
							raw.fieldName, transform, activeKernel));
					TypeName cursorType = activeKernel.randomAccess() ? activeKernel.inputType()
							: ClassName.get(BufDataCursor.class);
					return new WireNullableMapTransformExpression(targetNullable, sourceNullable, elementTransform,
							cursorName, stateCursor, targetName, elementName, readElement,
							wireElementReadPlan,
							binaryStrings,
							cursorType,
							CodeBlock.of("$N", raw.regionStartVariable()),
							CodeBlock.of("$N", raw.regionLengthVariable()));
				}
				TransformExpression source = compileReadTransformExpression(transform.mapNullable.source, null,
						logicalVersion, previousOwner, contextParameters, oldValue, oldType);
				if (!(source.type() instanceof ComputedTypeNullable sourceNullable)) {
					throw generationError("mapNullable source produced " + source.type() + " instead of nullable");
				}
				int id = nextTransformLocalId++;
				String sourceName = "mapNullableSource" + id;
				String targetName = "mapNullableTarget" + id;
				ResolvedValue element = new ResolvedValue(sourceNullable.getBase(), null,
						CodeBlock.of("$N.get()", sourceName));
				TransformExpression elementTransform = compileReadTransformExpression(transform.mapNullable.transform,
						targetNullable.getBase(), logicalVersion, previousOwner, contextParameters,
						element, sourceNullable.getBase());
				if (!elementTransform.type().equals(targetNullable.getBase())) {
					throw generationError("mapNullable element transform produced " + elementTransform.type()
							+ " instead of " + targetNullable.getBase());
				}
				return new NullableMapTransformExpression(targetNullable, sourceNullable, source, elementTransform,
						sourceName, targetName);
			}

			private ComputedType readTransformType(ReadTransformConfiguration transform, ComputedType expectedType) {
				if (transform.type == null || transform.type.isBlank()) return expectedType;
				ComputedType configured = typeNamed(dataModel.getCurrentVersion().getVersion(),
						DataModel.fixType(transform.type));
				if (expectedType != null && !configured.equals(expectedType)) {
					throw generationError("nested readTransform type " + configured + " does not match expected "
							+ expectedType);
				}
				return configured;
			}

			private ComputedType constructType(ReadTransformConfiguration transform, ComputedType configuredType) {
				if (transform.construct.type == null || transform.construct.type.isBlank()) {
					if (configuredType == null) throw generationError("construct readTransform has no result type");
					return configuredType;
				}
				ComputedType constructed = typeNamed(dataModel.getCurrentVersion().getVersion(),
						DataModel.fixType(transform.construct.type));
				if (configuredType != null && !constructed.equals(configuredType)) {
					throw generationError("construct type " + constructed + " does not match " + configuredType);
				}
				return constructed;
			}

			private ResolvedValue resolveReadReference(String reference,
					int logicalVersion,
					ComputedTypeBase previousOwner,
					List<String> contextParameters,
					ResolvedValue oldValue,
					ComputedType oldType) {
				String[] path = reference.split("\\.");
				if (path[0].equals("value") && oldValue != null && oldValue.wireRecordAccess() != null) {
					if (path.length == 1) {
						throw generationError("wire record transform must reference a field of value");
					}
					return oldValue.wireRecordAccess().resolve(path, 1);
				}
				ResolvedValue value;
				int pathStart;
				switch (path[0]) {
					case "value" -> {
						if (oldValue == null || oldType == null) {
							throw generationError("read initializer cannot reference value");
						}
						value = oldValue;
						pathStart = 1;
					}
					case "currentValue" -> {
						if (oldValue == null || oldType == null) {
							throw generationError("read initializer cannot reference currentValue");
						}
						ComputedType current = Objects.requireNonNullElse(currentRepresentation(oldType), oldType);
						value = oldType.equals(current) ? oldValue : upgradeValue(oldValue, oldType, current);
						pathStart = 1;
					}
					case "context", "currentContext" -> {
						if (path.length < 2) throw generationError(reference + " must name a context field");
						String field = path[1];
						if (!contextParameters.contains(field)) {
							throw generationError(reference + " is not declared in contextParameters");
						}
						ComputedType declared = previousOwner.getData().get(field);
						if (declared == null) throw generationError("unknown context field " + previousOwner.getName()
								+ "." + field);
						value = resolveAt(logicalVersion - 1, field, false);
						if (path[0].equals("currentContext")) {
							ComputedType current = Objects.requireNonNullElse(currentRepresentation(declared), declared);
							if (!declared.equals(current)) value = upgradeValue(value, declared, current);
						}
						pathStart = 2;
					}
					default -> throw generationError("unknown readTransform reference " + reference);
				}
				for (int index = pathStart; index < path.length; index++) {
					if (!(value.type() instanceof ComputedTypeBase record)) {
						throw generationError("readTransform path " + reference + " crosses non-record " + value.type());
					}
					String field = path[index];
					ComputedType fieldType = record.getData().get(field);
					if (fieldType == null) throw generationError("unknown readTransform path " + reference);
					CodeBlock owner = materialize(value, record);
					CodeBlock code = fieldType instanceof ComputedTypeArray
							? CodeBlock.of("(($T) $L).$NUnsafeArray()", record.getJTypeName(basePackageName), owner, field)
							: fieldType instanceof ComputedTypeNullable
									? CodeBlock.of("(($T) $L).has$N() ? $T.of((($T) $L).$N()) : $T.empty()",
											record.getJTypeName(basePackageName), owner, capitalize(field),
											fieldType.getJTypeName(basePackageName), record.getJTypeName(basePackageName),
											owner, field, fieldType.getJTypeName(basePackageName))
									: CodeBlock.of("(($T) $L).$N()", record.getJTypeName(basePackageName), owner, field);
					value = new ResolvedValue(fieldType, null, code);
				}
				return value;
			}

			private CodeBlock literal(Object value, ComputedType type) {
				if (type instanceof ComputedTypeNullable nullable) {
					return value == null ? CodeBlock.of("$T.empty()", type.getJTypeName(basePackageName))
							: CodeBlock.of("$T.of($L)", type.getJTypeName(basePackageName),
									literal(value, nullable.getBase()));
				}
				if (value == null) {
					if (type.getJTypeName(basePackageName).isPrimitive()) {
						throw generationError("null constant cannot target primitive " + type);
					}
					return CodeBlock.of("null");
				}
				if (type instanceof ComputedTypeNative nativeType) {
					return switch (nativeType.getName()) {
						case "boolean" -> CodeBlock.of("$L", (Boolean) value);
						case "byte" -> CodeBlock.of("(byte) $L", ((Number) value).byteValue());
						case "short" -> CodeBlock.of("(short) $L", ((Number) value).shortValue());
						case "char" -> value instanceof String text && text.length() == 1
								? CodeBlock.of("$S.charAt(0)", text)
								: CodeBlock.of("(char) $L", ((Number) value).intValue());
						case "int" -> CodeBlock.of("$L", ((Number) value).intValue());
						case "long" -> CodeBlock.of("$LL", ((Number) value).longValue());
						case "float" -> CodeBlock.of("$Lf", ((Number) value).floatValue());
						case "double" -> CodeBlock.of("$L", ((Number) value).doubleValue());
						case "String" -> CodeBlock.of("$S", Objects.toString(value));
						default -> throw generationError("constant readTransform does not support " + type);
					};
				}
				throw generationError("constant readTransform does not support " + type);
			}

			private StaticMethod staticMethod(String location) {
				int separator = Math.max(location.lastIndexOf('#'), location.lastIndexOf('.'));
				if (separator <= 0 || separator == location.length() - 1) {
					throw generationError("invalid invokeStatic method " + location);
				}
				return new StaticMethod(ClassName.bestGuess(location.substring(0, separator)),
						location.substring(separator + 1));
			}

			private ResolvedValue applyExplicitUpgrade(int logicalVersion,
					ComputedTypeBase previousOwner,
					UpgradeDataConfiguration upgrade,
					ComputedType oldType,
					ComputedType newType,
					ResolvedValue oldValue,
					boolean allowTerminal) {
				if (upgrade.hasReadTransform()) {
					ComputedType readResultType = upgrade.hasReadTransformTypeOverride()
							? typeNamed(dataModel.getCurrentVersion().getVersion(),
									DataModel.fixType(upgrade.getReadTransformType()))
							: newType;
					ComputedType expectedOutput = activeOutputType;
					boolean terminal = allowTerminal && upgrade.hasReadTransformTypeOverride()
							&& readResultType.equals(expectedOutput);
					if (allowTerminal && upgrade.hasReadTransformTypeOverride()
							&& targetVersion == dataModel.getCurrentVersion().getVersion() && !terminal) {
						throw generationError("readTransform.type " + upgrade.getReadTransformType()
								+ " does not match current field " + targetBase.getName() + "."
								+ activeOutputField + " of type " + expectedOutput);
					}
					if (!upgrade.hasReadTransformTypeOverride() || terminal) {
						if (upgrade.getReadTransform().isCustom()) {
							return applyReadUpgrade(logicalVersion, previousOwner, upgrade, oldType,
									readResultType, oldValue, terminal);
						}
						return applyDeclarativeReadTransform(logicalVersion, previousOwner,
								upgrade.getContextParameters(), upgrade.getReadTransform(), readResultType,
								oldValue, oldType, terminal);
					}
				}
				TransformSupport support = transformSupports.computeIfAbsent(upgrade,
						ignored -> createUpgraderSupport(upgrade, previousOwner, oldType, newType));
				CodeBlock context = contextCall(logicalVersion, previousOwner,
						upgrade.from, upgrade.getContextParameters());
				CodeBlock oldExpression = materialize(oldValue, oldType);
				return prepare(newType,
						CodeBlock.of("($T) $N.upgrade($L, ($T) $L)", newType.getJTypeName(basePackageName),
								support.fieldName(), context, oldType.getJTypeName(basePackageName), oldExpression));
			}

			private ResolvedValue applyReadUpgrade(int logicalVersion,
					ComputedTypeBase previousOwner,
					UpgradeDataConfiguration upgrade,
					ComputedType oldType,
					ComputedType readResultType,
					ResolvedValue oldValue,
					boolean terminal) {
				usesReadUpgrade = true;
				TransformSupport support = readTransformSupports.computeIfAbsent(upgrade,
						ignored -> createReadUpgraderSupport(upgrade, previousOwner));
				RawValue raw = oldValue.raw();
				boolean serializedAvailable = raw != null;
				String sourceReader = null;
				ComputedType currentValueType = currentRepresentation(oldType);
				String currentSourceReader = null;
				String currentObjectMapper = null;
				CodeBlock eagerValue = null;
				if (serializedAvailable) {
					raw.requestRegion();
					sourceReader = ensureReader(raw.sourceType(), oldType);
					externallyRequiredReaders.add(sourceReader);
					if (currentValueType != null) {
						currentSourceReader = ensureReader(raw.sourceType(), currentValueType);
						externallyRequiredReaders.add(currentSourceReader);
					}
				} else {
					eagerValue = materialize(oldValue, oldType);
					if (currentValueType != null) {
						currentObjectMapper = ensureObjectMapper(oldType, currentValueType);
					}
				}
				List<LazyContext> contexts = resolveLazyContexts(logicalVersion, previousOwner,
						upgrade.getContextParameters());
				boolean needsRecordRegion = contexts.stream().anyMatch(context -> !context.direct());
				usesRecordRegion |= needsRecordRegion;
				String helper = generateReadUpgradeFrame(upgrade, support, inputBase, previousOwner, oldType, readResultType,
						serializedAvailable, sourceReader, currentValueType, currentSourceReader, currentObjectMapper,
						contexts, needsRecordRegion);
				var call = CodeBlock.builder().add("$N(randomInput, state", helper);
				if (needsRecordRegion) {
					call.add(", recordStart, recordLength");
				}
				if (serializedAvailable) {
					call.add(", $N, $N", raw.regionStartVariable(), raw.regionLengthVariable());
				} else {
					call.add(", ($T) $L", oldType.getJTypeName(basePackageName), eagerValue);
				}
				for (LazyContext context : contexts) {
					if (context.direct()) {
						call.add(", $N, $N", context.regionStartVariable(), context.regionLengthVariable());
					}
				}
				call.add(")");
				return prepare(readResultType, call.build(), terminal);
			}

			private DirectField traceDirectField(ComputedTypeBase serializedOwner,
					ComputedTypeBase targetOwner,
					String targetField) {
				String field = targetField;
				for (int version = targetOwner.getVersion().getVersion();
						version > serializedOwner.getVersion().getVersion(); version--) {
					FieldOrigin origin = traceFieldOrigin(version, serializedOwner.getName(), field);
					if (origin.initializer() != null || !origin.upgrades().isEmpty()) return null;
					field = origin.previousName();
				}
				ComputedType sourceType = serializedOwner.getData().get(field);
				ComputedType targetType = targetOwner.getData().get(targetField);
				if (sourceType == null || targetType == null || !canFuse(sourceType, targetType)) return null;
				return new DirectField(field, sourceType);
			}

			private CodeBlock contextCall(int logicalVersion,
					ComputedTypeBase previousOwner,
					String fieldName,
					List<String> parameters) {
				if (parameters.isEmpty()) return CodeBlock.of("$T.INSTANCE", DataContextNone.class);
				TypeName contextType = previousOwner.getJUpgraderName(basePackageName)
						.nestedClass("Context" + capitalize(fieldName));
				var result = CodeBlock.builder().add("new $T(", contextType);
				for (int i = 0; i < parameters.size(); i++) {
					if (i != 0) result.add(", ");
					String parameter = parameters.get(i);
					ComputedType declaredType = previousOwner.getData().get(parameter);
					if (declaredType == null) {
						throw generationError("unknown context field " + previousOwner.getName() + "." + parameter);
					}
					result.add("$L", materialize(resolveAt(logicalVersion - 1, parameter, false), declaredType));
				}
				return result.add(")").build();
			}

			private ResolvedValue upgradeValue(ResolvedValue value,
					ComputedType oldType,
					ComputedType newType) {
				if (oldType.equals(newType)) return value.withType(newType);
				if (value.raw() != null && canFuse(oldType, newType)) {
					value.raw().requestFused(newType);
					return new ResolvedValue(newType, value.raw(), null);
				}
				return new ResolvedValue(newType, null, upgradeObject(value.code(), oldType, newType));
			}

			private CodeBlock materialize(ResolvedValue value, ComputedType declaredType) {
				if (!value.type().equals(declaredType)) {
					throw generationError("opaque operation expected " + declaredType + " but received " + value.type());
				}
				if (value.raw() != null) value.raw().requestMaterialized(declaredType);
				return value.code();
			}

			private ResolvedValue prepare(ComputedType type, CodeBlock expression) {
				return prepare(type, expression, false);
			}

			private ResolvedValue prepare(ComputedType type, CodeBlock expression, boolean terminal) {
				String name = "prepared" + preparedValues.size();
				preparedValues.add(new PreparedValue(name, type, expression));
				return new ResolvedValue(type, null, CodeBlock.of("$N", name), terminal);
			}

			private ResolvedValue prepareTransform(ComputedType type,
					TransformExpression expression,
					boolean terminal) {
				String name = "prepared" + preparedValues.size();
				if (type instanceof ComputedTypeNullable nullable
						&& expression instanceof WireNullableMapTransformExpression wireNullable) {
					String presentName = name + "Present";
					String valueName = name + "Value";
					preparedValues.add(new PreparedNullableTransform(presentName, valueName, nullable, wireNullable));
					return new ResolvedValue(type, null, null, terminal, null,
							new FlattenedNullableValue(presentName, valueName, nullable));
				}
				preparedValues.add(new PreparedTransform(name, type, expression));
				return new ResolvedValue(type, null, CodeBlock.of("$N", name), terminal);
			}

			private void emit(MethodSpec.Builder method) {
				for (ResolvedValue output : outputs) output.requireValue();
				if (usesReadUpgrade) {
					if (activeKernel.randomAccess()) {
						method.addStatement("final $T randomInput = input", RandomAccessDataInput.class);
					} else {
						method.beginControlFlow("if (!(input instanceof $T randomInput))", RandomAccessDataInput.class);
						if (constructTarget) {
							method.addStatement("return $L", upgradeObject(readExact(inputBase), inputBase, targetBase));
						} else if (outputs.size() == 1) {
							String fieldName = outputFields.getFirst();
							ComputedType declaredType = Objects.requireNonNull(targetBase.getData().get(fieldName));
							CodeBlock upgradedOwner = upgradeObject(readExact(inputBase), inputBase, targetBase);
							CodeBlock fieldValue = declaredType instanceof ComputedTypeArray
									? CodeBlock.of("(($T) $L).$NUnsafeArray()",
											targetBase.getJTypeName(basePackageName), upgradedOwner, fieldName)
									: declaredType instanceof ComputedTypeNullable
											? CodeBlock.of("(($T) $L).has$N() ? $T.of((($T) $L).$N()) : $T.empty()",
													targetBase.getJTypeName(basePackageName), upgradedOwner, capitalize(fieldName),
													declaredType.getJTypeName(basePackageName), targetBase.getJTypeName(basePackageName),
													upgradedOwner, fieldName, declaredType.getJTypeName(basePackageName))
											: CodeBlock.of("(($T) $L).$N()",
													targetBase.getJTypeName(basePackageName), upgradedOwner, fieldName);
							ComputedType requestedType = outputTypes.getFirst();
							method.addStatement("return $L", declaredType.equals(requestedType) ? fieldValue
									: upgradeObject(fieldValue, declaredType, requestedType));
						} else {
							throw generationError("lazy fallback must have exactly one output");
						}
						method.endControlFlow();
					}
					if (usesRecordRegion) {
						method.addStatement("final int recordStart = randomInput.position()");
					}
				}
				emitWireScan(method, scheduleWireScan());
				if (usesRecordRegion) {
					method.addStatement("final int recordLength = randomInput.position() - recordStart");
				}
				if (!inputBase.getData().isEmpty()) method.addCode("\n");

				for (PreparedComputation prepared : preparedValues) prepared.emit(method, basePackageName);
				if (!preparedValues.isEmpty()) method.addCode("\n");

				if (constructTarget) {
					var nullableLocals = new HashMap<Integer, String>();
					for (int i = 0; i < outputs.size(); i++) {
						ComputedType outputType = outputTypes.get(i);
						if (outputType instanceof ComputedTypeNullable
								&& outputs.get(i).flattenedNullable() == null) {
							String local = "outputNullable" + nullableLocals.size();
							nullableLocals.put(i, local);
							method.addStatement("final $T $N = ($T) $L", outputType.getJTypeName(basePackageName), local,
									outputType.getJTypeName(basePackageName), outputs.get(i).code());
						}
					}
					var arguments = CodeBlock.builder();
					boolean firstArgument = true;
					for (int i = 0; i < outputs.size(); i++) {
						ComputedType outputType = outputTypes.get(i);
						if (!firstArgument) arguments.add(", ");
						firstArgument = false;
						if (outputType instanceof ComputedTypeNullable nullable) {
							TypeName valueType = nullable.getBase().getJTypeName(basePackageName);
							FlattenedNullableValue flattened = outputs.get(i).flattenedNullable();
							if (flattened != null && valueType.isPrimitive()) {
								arguments.add("$N, $N", flattened.presentName(), flattened.valueName());
							} else if (flattened != null) {
								arguments.add("$N ? ($T) $N : null", flattened.presentName(), valueType,
										flattened.valueName());
							} else if (valueType.isPrimitive()) {
								String local = nullableLocals.get(i);
								arguments.add("$N.getNullable() != null, $N.getNullable() != null ? $N.get() : $L",
										local, local, local, defaultValue(valueType));
							} else {
								String local = nullableLocals.get(i);
								arguments.add("($T) $N.getNullable()", valueType, local);
							}
						} else {
							arguments.add("$L", outputs.get(i).code());
						}
					}
					method.addStatement("return $T.unsafeOfOwned($L)", targetBase.getJTypeName(basePackageName),
							arguments.build());
				} else if (outputs.size() == 1) {
					method.addStatement("return $L", outputs.getFirst().code());
				} else {
					throw generationError("lazy field reader must have exactly one output");
				}
			}

			private List<WireScanStep> scheduleWireScan() {
				var steps = new ArrayList<WireScanStep>();
				var fixed = new ArrayList<FixedScanField>();
				int fixedBytes = 0;
				for (var field : inputBase.getData().entrySet()) {
					String fieldName = field.getKey();
					ComputedType fieldType = field.getValue();
					RawValue raw = rawValues.get(fieldName);
					Integer size = fixedSerializedSize(fieldType);
					FixedScanAction action = fixedAction(fieldType, raw);
					if (size != null && action != null) {
						fixed.add(new FixedScanField(fieldName, fieldType, fixedBytes, size, action));
						fixedBytes = addFixedSkip(fixedBytes, size);
						continue;
					}
					if (!fixed.isEmpty()) {
						steps.add(new FixedScanRun(fixedBytes, List.copyOf(fixed)));
						fixed.clear();
						fixedBytes = 0;
					}
					steps.add(new SequentialScanField(fieldName, fieldType));
				}
				if (!fixed.isEmpty()) steps.add(new FixedScanRun(fixedBytes, List.copyOf(fixed)));
				return List.copyOf(steps);
			}

			private FixedScanAction fixedAction(ComputedType fieldType, RawValue raw) {
				if (raw == null || !raw.requiresValue()) {
					return raw != null && raw.regionRequested() ? FixedScanAction.CAPTURE : FixedScanAction.SKIP;
				}
				boolean primitive = fieldType instanceof ComputedTypeNative nativeType && nativeType.isPrimitive();
				boolean fixedCustom = fieldType instanceof ComputedTypeCustom custom
						&& custom.getFixedSize() != null;
				if ((!primitive && !fixedCustom) || !raw.readTarget().equals(fieldType)) {
					return null;
				}
				return raw.regionRequested() ? FixedScanAction.CAPTURE_AND_READ : FixedScanAction.READ;
			}

			private void emitWireScan(MethodSpec.Builder method, List<WireScanStep> steps) {
				int runId = 0;
				for (WireScanStep step : steps) {
					switch (step) {
						case FixedScanRun run -> emitFixedScanRun(method, run, runId++);
						case SequentialScanField field -> emitSequentialScanField(method, field);
					}
				}
			}

			private void emitFixedScanRun(MethodSpec.Builder method, FixedScanRun run, int runId) {
				boolean reads = run.fields().stream().anyMatch(field -> field.action().reads());
				boolean captures = run.fields().stream().anyMatch(field -> field.action().captures());
				if (!reads && !captures) {
					if (activeKernel.randomAccess()) {
						method.addStatement("input.skipExact($L)", run.byteSize());
					} else {
						emitFixedSkip(method, run.byteSize(), usesReadUpgrade);
					}
					return;
				}
				if (usesReadUpgrade) {
					emitReservedFixedRun(method, run, "randomInput", "fixedRun" + runId, true);
					return;
				}
				if (captures) {
					throw generationError("a captured fixed run requires random-access input");
				}
				if (activeKernel.randomAccess()) {
					emitReservedFixedRun(method, run, "input", "fixedRun" + runId, true);
					return;
				}
				for (FixedScanField field : run.fields()) {
					if (!field.action().reads()) continue;
					RawValue raw = rawValues.get(field.fieldName());
					method.addStatement("final $T $N", raw.readTarget().getJTypeName(basePackageName), raw.variable());
				}
				String random = "fixedInput" + runId;
				method.beginControlFlow("if (input instanceof $T $N)", RandomAccessDataInput.class, random);
				emitReservedFixedRun(method, run, random, "fixedRun" + runId, false);
				method.nextControlFlow("else");
				emitSequentialFixedRun(method, run);
				method.endControlFlow();
			}

			private void emitReservedFixedRun(MethodSpec.Builder method,
					FixedScanRun run,
					String randomInput,
					String base,
					boolean declareValues) {
				method.addStatement("final int $N = $N.reserve($L)", base, randomInput, run.byteSize());
				for (FixedScanField field : run.fields()) {
					RawValue raw = rawValues.get(field.fieldName());
					if (field.action().captures()) {
						method.addStatement("final int $N = $N + $L", raw.regionStartVariable(), base,
								field.byteOffset());
						method.addStatement("final int $N = $L", raw.regionLengthVariable(), field.byteSize());
					}
					if (field.action().reads()) {
						String prefix = declareValues ? "final $T $N = " : "$N = ";
						if (field.sourceType() instanceof ComputedTypeCustom custom) {
							CodeBlock reservedRead = CodeBlock.of("$L.readReserved($N, $N + $L, $L)",
									customSession(custom, CodeBlock.of("$N", randomInput)), randomInput, base,
									field.byteOffset(), field.byteSize());
							if (declareValues) {
								method.addStatement(prefix + "$L", raw.readTarget().getJTypeName(basePackageName),
										raw.variable(), reservedRead);
							} else {
								method.addStatement(prefix + "$L", raw.variable(), reservedRead);
							}
						} else if (declareValues) {
							method.addStatement(prefix + "$N.get$NAt($N + $L)",
									raw.readTarget().getJTypeName(basePackageName), raw.variable(), randomInput,
									capitalize(field.sourceType().getName()), base, field.byteOffset());
						} else {
							method.addStatement(prefix + "$N.get$NAt($N + $L)", raw.variable(), randomInput,
									capitalize(field.sourceType().getName()), base, field.byteOffset());
						}
					}
				}
			}

			private void emitSequentialFixedRun(MethodSpec.Builder method, FixedScanRun run) {
				int pendingSkip = 0;
				for (FixedScanField field : run.fields()) {
					if (!field.action().reads()) {
						pendingSkip = addFixedSkip(pendingSkip, field.byteSize());
						continue;
					}
					emitFixedSkip(method, pendingSkip);
					pendingSkip = 0;
					RawValue raw = rawValues.get(field.fieldName());
					if (field.sourceType() instanceof ComputedTypeCustom custom) {
						method.addStatement("$N = $L.read(input)", raw.variable(),
								customSession(custom, CodeBlock.of("input")));
					} else {
						method.addStatement("$N = input.read$N()", raw.variable(),
								capitalize(field.sourceType().getName()));
					}
				}
				emitFixedSkip(method, pendingSkip);
			}

			private void emitSequentialScanField(MethodSpec.Builder method, SequentialScanField field) {
				RawValue raw = rawValues.get(field.fieldName());
				if (raw == null) {
					method.addStatement("$N(input)", ensureSkipper(field.sourceType()));
					return;
				}
				if (raw.regionRequested()) {
					method.addStatement("final int $N = randomInput.position()", raw.regionStartVariable());
					if (raw.requiresValue()) {
						emitSequentialValueRead(method, field.sourceType(), raw);
					} else {
						method.addStatement("$N(input)", ensureSkipper(field.sourceType()));
					}
					method.addStatement("final int $N = randomInput.position() - $N",
							raw.regionLengthVariable(), raw.regionStartVariable());
					return;
				}
				if (raw.requiresValue()) {
					emitSequentialValueRead(method, field.sourceType(), raw);
				} else {
					method.addStatement("$N(input)", ensureSkipper(field.sourceType()));
				}
			}

			private void emitSequentialValueRead(MethodSpec.Builder method,
					ComputedType sourceType,
					RawValue raw) {
				FlattenedNullableValue flattened = raw.flattenedNullable();
				if (flattened == null) {
					ComputedType readTarget = raw.readTarget();
					method.addStatement("final $T $N = $L", readTarget.getJTypeName(basePackageName),
							raw.variable(), readFused(sourceType, readTarget));
					return;
				}

				ComputedTypeNullable sourceNullable = (ComputedTypeNullable) sourceType;
				ComputedType sourceValueType = sourceNullable.getBase();
				ComputedType targetValueType = flattened.type().getBase();
				TypeName targetJavaType = targetValueType.getJTypeName(basePackageName);
				String presentName = flattened.presentName();
				String valueName = flattened.valueName();
				CodeBlock value;
				String firstName = raw.variable() + "First";
				NullableWireEmitter.emitPresence(method, sourceNullable, CodeBlock.of("input"), presentName,
						firstName);
				if (WireLayout.of(sourceNullable) == WireLayout.BOOLEAN_TAGGED
						&& sourceValueType instanceof ComputedTypeCustom custom
						&& custom.getFixedSize() != null) {
					String randomInput = raw.variable() + "RandomInput";
					String valueStart = raw.variable() + "ValueStart";
					TypeName sourceJavaType = sourceValueType.getJTypeName(basePackageName);
					method.addStatement("final $T $N", targetJavaType, valueName)
							.beginControlFlow("if ($N)", presentName);
					if (activeKernel.randomAccess()) {
						method.addStatement("final int $N = input.reserve($L)", valueStart, custom.getFixedSize());
						CodeBlock decoded = CodeBlock.of("($T) $L.readReserved(input, $N, $L)", sourceJavaType,
								customSession(custom, CodeBlock.of("input")), valueStart, custom.getFixedSize());
						CodeBlock converted = sourceValueType.equals(targetValueType) ? decoded
								: upgradeObject(decoded, sourceValueType, targetValueType);
						method.addStatement("$N = ($T) $L", valueName, targetJavaType, converted);
					} else {
						method.beginControlFlow("if (input instanceof $T $N)", RandomAccessDataInput.class,
								randomInput)
								.addStatement("final int $N = $N.reserve($L)", valueStart, randomInput,
										custom.getFixedSize());
						CodeBlock reserved = CodeBlock.of("($T) $L.readReserved($N, $N, $L)", sourceJavaType,
								customSession(custom, CodeBlock.of("$N", randomInput)), randomInput, valueStart,
								custom.getFixedSize());
						CodeBlock convertedReserved = sourceValueType.equals(targetValueType) ? reserved
								: upgradeObject(reserved, sourceValueType, targetValueType);
						method.addStatement("$N = ($T) $L", valueName, targetJavaType, convertedReserved)
								.nextControlFlow("else");
						CodeBlock sequential = CodeBlock.of("($T) $L.read(input)", sourceJavaType,
								customSession(custom, CodeBlock.of("input")));
						CodeBlock convertedSequential = sourceValueType.equals(targetValueType) ? sequential
								: upgradeObject(sequential, sourceValueType, targetValueType);
						method.addStatement("$N = ($T) $L", valueName, targetJavaType, convertedSequential)
								.endControlFlow();
					}
					method.nextControlFlow("else")
							.addStatement("$N = $L", valueName, defaultValue(targetJavaType))
							.endControlFlow();
					return;
				}
				if (WireLayout.of(sourceNullable) == WireLayout.BOOLEAN_TAGGED) {
					value = readFused(sourceValueType, targetValueType);
				} else {
					CodeBlock sourceValue = NullableWireEmitter.valueExpression(sourceNullable, binaryStrings,
							CodeBlock.of("input"), firstName, readExact(sourceValueType));
					value = sourceValueType.equals(targetValueType) ? sourceValue
							: upgradeObject(sourceValue, sourceValueType, targetValueType);
				}
				method.addStatement("final $T $N = $N ? ($T) $L : $L", targetJavaType, valueName,
						presentName, targetJavaType, value, defaultValue(targetJavaType));
			}

			private final class RawValue {

				private final String variable;
				private final String fieldName;
				private final ComputedType sourceType;
				private ComputedType fusedTarget;
				private ComputedType materializedTarget;
				private boolean wrapperCodeRequested;

				private RawValue(String variable, String fieldName, ComputedType sourceType) {
					this.variable = variable;
					this.fieldName = fieldName;
					this.sourceType = sourceType;
				}

				private String variable() {
					return variable;
				}

				private void requestFused(ComputedType target) {
					if (!canFuse(sourceType, target)) {
						throw generationError("field " + fieldName + " cannot read " + sourceType + " as " + target);
					}
					if (fusedTarget == null || versionOf(target) > versionOf(fusedTarget)) fusedTarget = target;
				}

				private void requestMaterialized(ComputedType target) {
					if (!canFuse(sourceType, target)) {
						throw generationError("field " + fieldName + " cannot materialize " + target);
					}
					if (materializedTarget == null || versionOf(target) < versionOf(materializedTarget)) {
						materializedTarget = target;
					}
				}

				private ComputedType readTarget() {
					if (!requiresValue()) throw generationError("field " + fieldName + " was not requested as a value");
					return materializedTarget != null ? materializedTarget
							: fusedTarget != null ? fusedTarget : sourceType;
				}

				private ComputedType sourceType() {
					return sourceType;
				}

				private void requestWrapperCode() {
					wrapperCodeRequested = true;
				}

				private FlattenedNullableValue flattenedNullable() {
					if (!constructTarget || wrapperCodeRequested || materializedTarget != null
							|| !(sourceType instanceof ComputedTypeNullable)
							|| !(fusedTarget instanceof ComputedTypeNullable targetNullable)) {
						return null;
					}
					return new FlattenedNullableValue(variable + "Present", variable + "Value", targetNullable);
				}

				private boolean requiresValue() {
					return materializedTarget != null || fusedTarget != null;
				}

				private boolean regionRequested;

				private void requestRegion() {
					regionRequested = true;
				}

				private boolean regionRequested() {
					return regionRequested;
				}

				private String regionStartVariable() {
					return variable + "Start";
				}

				private String regionLengthVariable() {
					return variable + "Length";
				}
			}

			private final class WireRecordElementReadPlan implements WireElementReadPlan {

				private final ComputedTypeBase recordType;
				private final String cursorName;
				private final String coordinate;
				private final Map<String, WireRecordFieldRead> fields = new LinkedHashMap<>();

				private WireRecordElementReadPlan(ComputedTypeBase recordType,
						String cursorName,
						String coordinate) {
					this.recordType = recordType;
					this.cursorName = cursorName;
					this.coordinate = coordinate;
				}

				private ResolvedValue resolve(String[] path, int pathIndex) {
					String fieldName = path[pathIndex];
					ComputedType fieldType = recordType.getData().get(fieldName);
					if (fieldType == null) {
						throw generationError("unknown readTransform wire path " + String.join(".", path));
					}
					WireRecordFieldRead existing = fields.get(fieldName);
					if (pathIndex == path.length - 1) {
						if (existing != null && existing.nested != null) {
							throw generationError("readTransform wire path requests both a value and its child at "
									+ coordinate + "." + fieldName);
						}
						if (existing == null) {
							String localName = "wireField" + nextTransformLocalId++;
							existing = new WireRecordFieldRead(fieldType, localName, null);
							fields.put(fieldName, existing);
						}
						return new ResolvedValue(fieldType, null, CodeBlock.of("$N", existing.localName));
					}
					if (!(fieldType instanceof ComputedTypeBase nestedType)) {
						throw generationError("readTransform wire path crosses non-record " + fieldType);
					}
					if (existing != null && existing.localName != null) {
						throw generationError("readTransform wire path requests both a value and its child at "
								+ coordinate + "." + fieldName);
					}
					if (existing == null) {
						existing = new WireRecordFieldRead(fieldType, null,
								new WireRecordElementReadPlan(nestedType, cursorName, coordinate + "." + fieldName));
						fields.put(fieldName, existing);
					}
					return existing.nested.resolve(path, pathIndex + 1);
				}

				@Override
				public void emit(MethodSpec.Builder method, String basePackageName) {
					for (var field : recordType.getData().entrySet()) {
						WireRecordFieldRead access = fields.get(field.getKey());
						if (access == null) {
							method.addStatement("$N($N)", ensureSkipper(field.getValue()), cursorName);
							continue;
						}
						if (access.nested != null) {
							access.nested.emit(method, basePackageName);
							continue;
						}
						ComputedType fieldType = field.getValue();
						TypeName javaType = fieldType.getJTypeName(basePackageName);
						if (fieldType instanceof ComputedTypeNative nativeType && nativeType.isPrimitive()) {
							method.addStatement("final $T $N = $N.read$N()", javaType, access.localName,
									cursorName, capitalize(nativeType.getName()));
						} else {
							String reader = ensureReader(fieldType, fieldType);
							externallyRequiredReaders.add(reader);
							method.addStatement("final $T $N = $N($N, state)", javaType, access.localName,
									reader, cursorName);
						}
					}
				}
			}

			private final class WireRecordFieldRead {

				private final ComputedType type;
				private final String localName;
				private final WireRecordElementReadPlan nested;

				private WireRecordFieldRead(ComputedType type,
						String localName,
						WireRecordElementReadPlan nested) {
					this.type = type;
					this.localName = localName;
					this.nested = nested;
				}
			}

			private final class ResolvedValue {

				private final ComputedType type;
				private final RawValue raw;
				private final CodeBlock fixedCode;
				private final boolean terminal;
				private final WireRecordElementReadPlan wireRecordAccess;
				private final FlattenedNullableValue flattenedNullable;

				private ResolvedValue(ComputedType type, RawValue raw, CodeBlock fixedCode) {
					this(type, raw, fixedCode, false, null);
				}

				private ResolvedValue(ComputedType type, RawValue raw, CodeBlock fixedCode, boolean terminal) {
					this(type, raw, fixedCode, terminal, null);
				}

				private ResolvedValue(ComputedType type,
						RawValue raw,
						CodeBlock fixedCode,
						boolean terminal,
						WireRecordElementReadPlan wireRecordAccess) {
					this(type, raw, fixedCode, terminal, wireRecordAccess, null);
				}

				private ResolvedValue(ComputedType type,
						RawValue raw,
						CodeBlock fixedCode,
						boolean terminal,
						WireRecordElementReadPlan wireRecordAccess,
						FlattenedNullableValue flattenedNullable) {
					this.type = type;
					this.raw = raw;
					this.fixedCode = fixedCode;
					this.terminal = terminal;
					this.wireRecordAccess = wireRecordAccess;
					this.flattenedNullable = flattenedNullable;
				}

				private ComputedType type() {
					return type;
				}

				private RawValue raw() {
					return raw;
				}

				private WireRecordElementReadPlan wireRecordAccess() {
					return wireRecordAccess;
				}

				private FlattenedNullableValue flattenedNullable() {
					return flattenedNullable != null ? flattenedNullable
							: raw != null ? raw.flattenedNullable() : null;
				}

				private ResolvedValue withType(ComputedType newType) {
					return new ResolvedValue(newType, raw, fixedCode, terminal, wireRecordAccess,
							flattenedNullable);
				}

				private boolean terminal() {
					return terminal;
				}

				private void requireValue() {
					if (raw != null) raw.requestFused(type);
				}

				private CodeBlock code() {
					if (wireRecordAccess != null) {
						throw generationError("wire record element must be consumed through value.<path>");
					}
					if (flattenedNullable != null) {
						return CodeBlock.of("$N ? $T.of($N) : $T.empty()", flattenedNullable.presentName(),
								flattenedNullable.type().getJTypeName(basePackageName), flattenedNullable.valueName(),
								flattenedNullable.type().getJTypeName(basePackageName));
					}
					if (raw == null) return fixedCode;
					raw.requestWrapperCode();
					ComputedType actual = raw.readTarget();
					CodeBlock value = CodeBlock.of("$N", raw.variable());
					return actual.equals(type) ? value : upgradeObject(value, actual, type);
				}
			}
		}

		/** Compiles a materialized historical structural value straight into its requested target version. */
		private final class ObjectRecordPlan {

			private final ComputedTypeBase inputBase;
			private final ComputedTypeBase targetBase;
			private final int inputVersion;
			private final int targetVersion;
			private final Map<ResolveKey, ObjectResolvedValue> resolved = new HashMap<>();
			private final LinkedHashMap<String, ObjectRawValue> rawValues = new LinkedHashMap<>();
			private final List<PreparedComputation> preparedValues = new ArrayList<>();
			private List<ObjectResolvedValue> outputs;

			private ObjectRecordPlan(ComputedTypeBase inputBase, ComputedTypeBase targetBase) {
				this.inputBase = inputBase;
				this.targetBase = targetBase;
				this.inputVersion = inputBase.getVersion().getVersion();
				this.targetVersion = targetBase.getVersion().getVersion();
			}

			private void compile() {
				outputs = targetBase.getData().keySet().stream()
						.map(field -> resolveAt(targetVersion, field))
						.toList();
				int index = 0;
				for (ComputedType expected : targetBase.getData().values()) {
					ObjectResolvedValue output = outputs.get(index++);
					if (!output.type().equals(expected)) {
						throw generationError("materialized field plan resolved " + output.type()
								+ " instead of " + expected);
					}
				}
			}

			private ObjectResolvedValue resolveAt(int logicalVersion, String fieldName) {
				ResolveKey key = new ResolveKey(logicalVersion, fieldName);
				ObjectResolvedValue cached = resolved.get(key);
				if (cached != null) return cached;
				ObjectResolvedValue value;
				if (logicalVersion == inputVersion) {
					ComputedType sourceType = inputBase.getData().get(fieldName);
					if (sourceType == null) {
						throw generationError("missing materialized input field " + inputBase.getName() + "." + fieldName);
					}
					ObjectRawValue raw = rawValues.computeIfAbsent(fieldName,
							ignored -> new ObjectRawValue("raw" + rawValues.size(), fieldName, sourceType));
					value = new ObjectResolvedValue(sourceType, raw, null);
				} else {
					ComputedTypeBase nextOwner = requireBase(logicalVersion, inputBase.getName());
					ComputedTypeBase previousOwner = requireBase(logicalVersion - 1, inputBase.getName());
					ComputedType nextFieldType = nextOwner.getData().get(fieldName);
					if (nextFieldType == null) {
						throw generationError("missing materialized target field " + nextOwner.getName() + "." + fieldName);
					}
					FieldOrigin origin = traceFieldOrigin(logicalVersion, inputBase.getName(), fieldName);
					ComputedType operationType;
					if (origin.initializer() != null) {
						operationType = typeNamed(logicalVersion, DataModel.fixType(origin.initializer().type));
						value = applyInitializer(logicalVersion, previousOwner, origin.initializer(), operationType);
					} else {
						operationType = previousOwner.getData().get(origin.previousName());
						if (operationType == null) {
							throw generationError("missing previous materialized field " + previousOwner.getName()
									+ "." + origin.previousName());
						}
						value = resolveAt(logicalVersion - 1, origin.previousName());
					}

					for (UpgradeDataConfiguration upgrade : origin.upgrades()) {
						ComputedType newType = typeNamed(logicalVersion, DataModel.fixType(upgrade.type));
						value = applyExplicitUpgrade(logicalVersion, previousOwner, upgrade,
								operationType, newType, value);
						operationType = newType;
					}
					value = upgradeValue(value, operationType, nextFieldType);
				}
				resolved.put(key, value);
				return value;
			}

			private ObjectResolvedValue applyInitializer(int logicalVersion,
					ComputedTypeBase previousOwner,
					NewDataConfiguration initializer,
					ComputedType newType) {
				TransformSupport support = transformSupports.computeIfAbsent(initializer,
						ignored -> createInitializerSupport(initializer, previousOwner, newType));
				CodeBlock context = contextCall(logicalVersion, previousOwner,
						initializer.to, initializer.getContextParameters());
				return prepare(newType, CodeBlock.of("$N.initialize($L)", support.fieldName(), context));
			}

			private ObjectResolvedValue applyExplicitUpgrade(int logicalVersion,
					ComputedTypeBase previousOwner,
					UpgradeDataConfiguration upgrade,
					ComputedType oldType,
					ComputedType newType,
					ObjectResolvedValue oldValue) {
				TransformSupport support = transformSupports.computeIfAbsent(upgrade,
						ignored -> createUpgraderSupport(upgrade, previousOwner, oldType, newType));
				CodeBlock context = contextCall(logicalVersion, previousOwner,
						upgrade.from, upgrade.getContextParameters());
				CodeBlock oldExpression = materialize(oldValue, oldType);
				return prepare(newType,
						CodeBlock.of("($T) $N.upgrade($L, ($T) $L)", newType.getJTypeName(basePackageName),
								support.fieldName(), context, oldType.getJTypeName(basePackageName), oldExpression));
			}

			private CodeBlock contextCall(int logicalVersion,
					ComputedTypeBase previousOwner,
					String fieldName,
					List<String> parameters) {
				if (parameters.isEmpty()) return CodeBlock.of("$T.INSTANCE", DataContextNone.class);
				TypeName contextType = previousOwner.getJUpgraderName(basePackageName)
						.nestedClass("Context" + capitalize(fieldName));
				var result = CodeBlock.builder().add("new $T(", contextType);
				for (int i = 0; i < parameters.size(); i++) {
					if (i != 0) result.add(", ");
					String parameter = parameters.get(i);
					ComputedType declaredType = previousOwner.getData().get(parameter);
					if (declaredType == null) {
						throw generationError("unknown materialized context field " + previousOwner.getName()
								+ "." + parameter);
					}
					result.add("$L", materialize(resolveAt(logicalVersion - 1, parameter), declaredType));
				}
				return result.add(")").build();
			}

			private ObjectResolvedValue upgradeValue(ObjectResolvedValue value,
					ComputedType oldType,
					ComputedType newType) {
				if (oldType.equals(newType)) return value.withType(newType);
				if (value.raw() != null && canFuse(oldType, newType)) {
					value.raw().requestFused(newType);
					return new ObjectResolvedValue(newType, value.raw(), null);
				}
				return new ObjectResolvedValue(newType, null, upgradeObject(value.code(), oldType, newType));
			}

			private CodeBlock materialize(ObjectResolvedValue value, ComputedType declaredType) {
				if (!value.type().equals(declaredType)) {
					throw generationError("opaque materialized operation expected " + declaredType
							+ " but received " + value.type());
				}
				if (value.raw() != null) value.raw().requestMaterialized(declaredType);
				return value.code();
			}

			private ObjectResolvedValue prepare(ComputedType type, CodeBlock expression) {
				String name = "prepared" + preparedValues.size();
				preparedValues.add(new PreparedValue(name, type, expression));
				return new ObjectResolvedValue(type, null, CodeBlock.of("$N", name));
			}

			private void emit(MethodSpec.Builder method) {
				for (var field : inputBase.getData().entrySet()) {
					ObjectRawValue raw = rawValues.get(field.getKey());
					if (raw == null) continue;
					ComputedType target = raw.target();
					CodeBlock source = field.getValue() instanceof ComputedTypeArray
							? CodeBlock.of("source.$NUnsafeArray()", field.getKey())
							: field.getValue() instanceof ComputedTypeNullable
									? CodeBlock.of("source.has$N() ? $T.of(source.$N()) : $T.empty()",
											capitalize(field.getKey()), field.getValue().getJTypeName(basePackageName),
											field.getKey(), field.getValue().getJTypeName(basePackageName))
									: CodeBlock.of("source.$N()", field.getKey());
					CodeBlock expression = field.getValue().equals(target)
							? source : upgradeObject(source, field.getValue(), target);
					method.addStatement("final $T $N = $L", target.getJTypeName(basePackageName),
							raw.variable(), expression);
				}
				if (!rawValues.isEmpty()) method.addCode("\n");

				for (PreparedComputation prepared : preparedValues) prepared.emit(method, basePackageName);
				if (!preparedValues.isEmpty()) method.addCode("\n");

				var nullableLocals = new HashMap<Integer, String>();
				for (int i = 0; i < outputs.size(); i++) {
					ComputedType outputType = targetBase.getData().values().stream().skip(i).findFirst().orElseThrow();
					if (outputType instanceof ComputedTypeNullable) {
						String local = "outputNullable" + nullableLocals.size();
						nullableLocals.put(i, local);
						method.addStatement("final $T $N = ($T) $L", outputType.getJTypeName(basePackageName), local,
								outputType.getJTypeName(basePackageName), outputs.get(i).code());
					}
				}
				var targetTypes = List.copyOf(targetBase.getData().values());
				var arguments = CodeBlock.builder();
				boolean firstArgument = true;
				for (int i = 0; i < outputs.size(); i++) {
					ComputedType outputType = targetTypes.get(i);
					if (!firstArgument) arguments.add(", ");
					firstArgument = false;
					if (outputType instanceof ComputedTypeNullable nullable) {
						String local = nullableLocals.get(i);
						TypeName valueType = nullable.getBase().getJTypeName(basePackageName);
						if (valueType.isPrimitive()) {
							arguments.add("$N.getNullable() != null, $N.getNullable() != null ? $N.get() : $L",
									local, local, local, defaultValue(valueType));
						} else {
							arguments.add("($T) $N.getNullable()", valueType, local);
						}
					} else {
						arguments.add("$L", outputs.get(i).code());
					}
				}
				method.addStatement("return $T.unsafeOfOwned($L)", targetBase.getJTypeName(basePackageName),
						arguments.build());
			}

			private final class ObjectRawValue {

				private final String variable;
				private final String fieldName;
				private final ComputedType sourceType;
				private ComputedType fusedTarget;
				private ComputedType materializedTarget;

				private ObjectRawValue(String variable, String fieldName, ComputedType sourceType) {
					this.variable = variable;
					this.fieldName = fieldName;
					this.sourceType = sourceType;
				}

				private String variable() {
					return variable;
				}

				private void requestFused(ComputedType target) {
					if (!canFuse(sourceType, target)) {
						throw generationError("materialized field " + fieldName + " cannot fuse "
								+ sourceType + " as " + target);
					}
					if (fusedTarget == null || versionOf(target) > versionOf(fusedTarget)) fusedTarget = target;
				}

				private void requestMaterialized(ComputedType target) {
					if (!canFuse(sourceType, target)) {
						throw generationError("materialized field " + fieldName + " cannot produce " + target);
					}
					if (materializedTarget == null || versionOf(target) < versionOf(materializedTarget)) {
						materializedTarget = target;
					}
				}

				private ComputedType target() {
					return materializedTarget != null ? materializedTarget
							: fusedTarget != null ? fusedTarget : sourceType;
				}
			}

			private final class ObjectResolvedValue {

				private final ComputedType type;
				private final ObjectRawValue raw;
				private final CodeBlock fixedCode;

				private ObjectResolvedValue(ComputedType type, ObjectRawValue raw, CodeBlock fixedCode) {
					this.type = type;
					this.raw = raw;
					this.fixedCode = fixedCode;
				}

				private ComputedType type() {
					return type;
				}

				private ObjectRawValue raw() {
					return raw;
				}

				private ObjectResolvedValue withType(ComputedType newType) {
					return new ObjectResolvedValue(newType, raw, fixedCode);
				}

				private CodeBlock code() {
					if (raw == null) return fixedCode;
					ComputedType actual = raw.target();
					CodeBlock value = CodeBlock.of("$N", raw.variable());
					return actual.equals(type) ? value : upgradeObject(value, actual, type);
				}
			}
		}

		private CodeBlock upgradeObject(CodeBlock value, ComputedType inputType, ComputedType targetType) {
			if (inputType.equals(targetType)) return value;
			if (!canFuse(inputType, targetType)) {
				throw generationError("cannot structurally upgrade " + inputType + " into " + targetType);
			}
			return CodeBlock.of("$N($L)", ensureObjectMapper(inputType, targetType), value);
		}

		private int versionOf(ComputedType type) {
			return type instanceof VersionedComputedType versioned ? versioned.getVersion().getVersion() : -1;
		}

		private String ensureSkipper(ComputedType type) {
			SkipperKey key = new SkipperKey(type.getClass().getName(), type.getName(), versionOf(type));
			String existing = skipperMethods.get(key);
			if (existing != null) return existing;
			String method = "skip" + nextSkipperId++;
			skipperMethods.put(key, method);
			pendingSkippers.addLast(Map.entry(key, type));
			return method;
		}

		private void generatePendingSkippers() {
			while (!pendingSkippers.isEmpty()) {
				var pending = pendingSkippers.removeFirst();
				ComputedType type = pending.getValue();
				var method = MethodSpec.methodBuilder(skipperMethods.get(pending.getKey()))
						.addModifiers(Modifier.PRIVATE, Modifier.STATIC)
						.addParameter(SafeDataInput.class, "input");
				boolean nativeArrayOwnsStructure = type instanceof ComputedTypeArrayNative nativeArray
						&& nativeArray.hasContainerSpecificElementWireFormat();
				boolean structural = isStructural(type) && !nativeArrayOwnsStructure;
				if (structural) {
					method.addStatement("input.decodeBudget().enterStructure()")
							.beginControlFlow("try");
				}
				emitSkip(method, type);
				if (structural) {
					method.nextControlFlow("finally")
							.addStatement("input.decodeBudget().exitStructure()")
							.endControlFlow();
				}
				classBuilder.addMethod(method.build());
			}
		}

		private void emitSkip(MethodSpec.Builder method, ComputedType type) {
			if (type instanceof ComputedTypeNative nativeType) {
				int fixed = switch (nativeType.getName()) {
					case "boolean", "byte" -> 1;
					case "short", "char" -> 2;
					case "int", "float" -> 4;
					case "long", "double" -> 8;
					case "Int52" -> 7;
					case "String" -> -1;
					default -> throw new IllegalStateException(nativeType.getName());
				};
				if (fixed >= 0) method.addStatement("$T.skipBytes(input, $L)", ProjectionReadSupport.class, fixed);
				else method.addStatement("$T.skipPayload(input, $T.readLength(input))",
						ProjectionReadSupport.class, ProjectionReadSupport.class);
				return;
			}
			if (type instanceof ComputedTypeCustom custom) {
				if (custom.getFixedSize() != null) {
					method.addStatement("$T.skipBytes(input, $L)", ProjectionReadSupport.class,
							custom.getFixedSize());
				} else {
					method.addStatement("$L.skip(input)", customSession(custom, CodeBlock.of("input")));
				}
				return;
			}
			if (type instanceof ComputedTypeNullable nullable) {
				NullableWireEmitter.emitSkip(method, nullable, CodeBlock.of("input"),
						"nullablePresent", "nullableFirst",
						CodeBlock.of("$N(input)", ensureSkipper(nullable.getBase())));
				return;
			}
			if (type instanceof ComputedTypeArrayNative nativeArray
					&& nativeArray.hasContainerSpecificElementWireFormat()) {
				FieldLocation serializer = nativeArray.getJSerializerInstance(basePackageName);
				method.addStatement("$T.$N.skip(input)", serializer.className(), serializer.fieldName());
				return;
			}
			if (type instanceof ComputedTypeArray array) {
				Integer elementSize = fixedSerializedSize(array.getBase());
				method.addStatement("int size = $T.readLength(input)", ProjectionReadSupport.class)
						.addStatement("$T.prepareArrayAllocation(input, size, $L)", ProjectionReadSupport.class,
								readPlanCompiler.minimumSerializedSize(array.getBase()));
				if (elementSize != null) {
					method.addStatement("$T.skipBytes(input, $T.checkedArrayBytes(size, $L))",
							ProjectionReadSupport.class, ProjectionReadSupport.class, elementSize);
				} else {
					method.beginControlFlow("for (int i = 0; i < size; i++)")
							.addStatement("$N(input)", ensureSkipper(array.getBase()))
							.endControlFlow();
				}
				return;
			}
			if (type instanceof ComputedTypeBase base) {
				int pendingFixedSkip = 0;
				for (ComputedType field : base.getData().values()) {
					Integer fixedSize = fixedSerializedSize(field);
					if (fixedSize != null) {
						pendingFixedSkip = addFixedSkip(pendingFixedSkip, fixedSize);
					} else {
						emitFixedSkip(method, pendingFixedSkip);
						pendingFixedSkip = 0;
						method.addStatement("$N(input)", ensureSkipper(field));
					}
				}
				emitFixedSkip(method, pendingFixedSkip);
				return;
			}
			if (type instanceof ComputedTypeSuper union) {
				method.addStatement("int id = input.readUnsignedByte()")
						.beginControlFlow("switch (id)");
				for (int i = 0; i < union.subTypes().size(); i++) {
					method.addStatement("case $L -> $N(input)", i, ensureSkipper(union.subTypes().get(i)));
				}
				method.addStatement("default -> throw new $T($S + id)", MalformedDataException.class,
						"Invalid union discriminator: ")
						.endControlFlow();
				return;
			}
			throw new IllegalStateException("Unsupported skip type: " + type);
		}

		private Integer fixedSerializedSize(ComputedType type) {
			return fixedSerializedSize(type, new IdentityHashMap<>());
		}

		private Integer fixedSerializedSize(ComputedType type,
				IdentityHashMap<ComputedType, Boolean> visiting) {
			if (type instanceof ComputedTypeNative nativeType) {
				return switch (nativeType.getName()) {
					case "boolean", "byte" -> 1;
					case "short", "char" -> 2;
					case "int", "float" -> 4;
					case "long", "double" -> 8;
					case "Int52" -> 7;
					case "String" -> null;
					default -> throw new IllegalStateException(nativeType.getName());
				};
			}
			if (type instanceof ComputedTypeCustom custom) {
				return custom.getFixedSize();
			}
			if (type instanceof ComputedTypeNullable
					|| type instanceof ComputedTypeArray) {
				return null;
			}
			if (visiting.put(type, Boolean.TRUE) != null) return null;
			try {
				if (type instanceof ComputedTypeBase base) {
					int size = 0;
					for (ComputedType field : base.getData().values()) {
						Integer fieldSize = fixedSerializedSize(field, visiting);
						if (fieldSize == null) return null;
						size = addFixedSkip(size, fieldSize);
					}
					return size;
				}
				if (type instanceof ComputedTypeSuper union) {
					Integer payloadSize = null;
					for (ComputedType subtype : union.subTypes()) {
						Integer subtypeSize = fixedSerializedSize(subtype, visiting);
						if (subtypeSize == null || payloadSize != null && !payloadSize.equals(subtypeSize)) {
							return null;
						}
						payloadSize = subtypeSize;
					}
					return payloadSize == null ? null : addFixedSkip(1, payloadSize);
				}
				return null;
			} finally {
				visiting.remove(type);
			}
		}

		private int addFixedSkip(int current, int addition) {
			try {
				return Math.addExact(current, addition);
			} catch (ArithmeticException e) {
				throw generationError("fixed-width skip span exceeds the supported input size");
			}
		}

		private void emitFixedSkip(MethodSpec.Builder method, int size) {
			if (size != 0) {
				method.addStatement("$T.skipBytes(input, $L)", ProjectionReadSupport.class, size);
			}
		}

		private void emitFixedSkip(MethodSpec.Builder method, int size, boolean direct) {
			if (size == 0) return;
			if (direct) {
				method.addStatement("randomInput.skipExact($L)", size);
			} else {
				emitFixedSkip(method, size);
			}
		}

		private TransformSupport createInitializerSupport(NewDataConfiguration initializer,
				ComputedTypeBase previousOwner,
				ComputedType newType) {
			TypeName context = contextType(previousOwner, initializer.to, initializer.getContextParameters());
			TypeName type = ParameterizedTypeName.get(ClassName.get(DataInitializer.class), context,
					newType.getJTypeName(basePackageName).box());
			String name = "INITIALIZER_" + nextTransformId++;
			classBuilder.addField(interfaceField(type, name, initializer.getInitializerLocation()));
			return new TransformSupport(name);
		}

		private TransformSupport createUpgraderSupport(UpgradeDataConfiguration upgrade,
				ComputedTypeBase previousOwner,
				ComputedType oldType,
				ComputedType newType) {
			TypeName context = contextType(previousOwner, upgrade.from, upgrade.getContextParameters());
			TypeName type = ParameterizedTypeName.get(ClassName.get(DataUpgrader.class), context,
					oldType.getJTypeName(basePackageName).box(), newType.getJTypeName(basePackageName).box());
			String name = "UPGRADER_" + nextTransformId++;
			classBuilder.addField(interfaceField(type, name, upgrade.getUpgraderLocation()));
			return new TransformSupport(name);
		}

		private TransformSupport createReadUpgraderSupport(UpgradeDataConfiguration upgrade,
				ComputedTypeBase previousOwner) {
			TypeName type = previousOwner.getJUpgraderName(basePackageName)
					.nestedClass(GenUpgraderBaseX.readUpgraderInterfaceName(upgrade.from));
			String name = "READ_UPGRADER_" + nextTransformId++;
			classBuilder.addField(interfaceField(type, name,
					upgrade.getReadTransform().custom.location("upgradeData.readTransform.custom")));
			return new TransformSupport(name);
		}

		private TransformSupport createReadInitializerSupport(NewDataConfiguration initializer,
				ComputedTypeBase previousOwner) {
			TypeName type = previousOwner.getJUpgraderName(basePackageName)
					.nestedClass(GenUpgraderBaseX.readInitializerInterfaceName(initializer.to));
			String name = "READ_INITIALIZER_" + nextTransformId++;
			classBuilder.addField(interfaceField(type, name,
					initializer.getReadTransform().custom.location("newData.readTransform.custom")));
			return new TransformSupport(name);
		}

		private String generateReadInitializerFrame(NewDataConfiguration initializer,
				TransformSupport support,
				ComputedTypeBase apiOwner,
				ComputedType resultType,
				List<LazyContext> contexts,
				boolean needsRecordRegion) {
			var frameKey = new ReadInitializerFrameKey(initializer, support, apiOwner, resultType,
					List.copyOf(contexts), needsRecordRegion);
			String existingFrame = readInitializerFrames.get(frameKey);
			if (existingFrame != null) return existingFrame;
			int id = nextReadFrameId++;
			String frameName = "ReadInitializerFrame" + id;
			String helperName = "applyReadInitializer" + id;
			String stateField = "readInitializerFrame" + id;
			ClassName frameType = planClassName.nestedClass(frameName);
			ClassName stateType = planClassName.nestedClass("State");
			TypeName inputType = apiOwner.getJUpgraderName(basePackageName)
					.nestedClass(GenUpgraderBaseX.readInitializerInputInterfaceName(initializer.to));

			stateBuilder.addField(FieldSpec.builder(frameType, stateField, Modifier.PRIVATE, Modifier.FINAL)
					.initializer("new $T()", frameType)
					.build());
			TypeSpec.Builder frame = TypeSpec.classBuilder(frameName)
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
					.addSuperinterface(inputType)
					.addField(frameType, "next", Modifier.PRIVATE)
					.addField(TypeName.BOOLEAN, "inUse", Modifier.PRIVATE)
					.addField(RandomAccessDataInput.class, "parent", Modifier.PRIVATE)
					.addField(stateType, "state", Modifier.PRIVATE)
					.addField(BufDataCursor.class, "contextCursor", Modifier.PRIVATE, Modifier.FINAL);
			var contextViews = new LinkedHashMap<String, WireViewBinding>();
			for (LazyContext context : contexts) {
				if (!isWireViewType(context.type())) continue;
				WireViewBinding binding = context.direct()
						? generateRegionWireView(apiOwner, context.type(),
								GenUpgraderBaseX.contextWireViewInterfaceName(initializer.to, context.fieldName()),
								frameType, CodeBlock.of("owner.$N", context.frameStartName()),
								CodeBlock.of("owner.$N", context.frameLengthName()))
						: generateDelegatingWireView(apiOwner, context.type(),
								GenUpgraderBaseX.contextWireViewInterfaceName(initializer.to, context.fieldName()),
								frameType, CodeBlock.of("owner.$N()", context.methodName()));
				contextViews.put(context.fieldName(), binding);
				frame.addField(binding.implementationType(), binding.fieldName(), Modifier.PRIVATE, Modifier.FINAL);
			}
			MethodSpec.Builder frameConstructor = MethodSpec.constructorBuilder()
					.addModifiers(Modifier.PRIVATE)
					.addStatement("this.contextCursor = $T.borrowed()", BufDataCursor.class);
			for (WireViewBinding binding : contextViews.values()) {
				frameConstructor.addStatement("this.$N = new $T(this)", binding.fieldName(),
						binding.implementationType());
			}
			frame.addMethod(frameConstructor.build());
			if (needsRecordRegion) {
				frame.addField(TypeName.INT, "recordStart", Modifier.PRIVATE)
						.addField(TypeName.INT, "recordLength", Modifier.PRIVATE);
			}
			for (LazyContext context : contexts) {
				frame.addField(TypeName.BOOLEAN, context.cacheSetName(), Modifier.PRIVATE)
						.addField(context.type().getJTypeName(basePackageName), context.cacheName(), Modifier.PRIVATE);
				if (context.hasCurrent()) {
					frame.addField(TypeName.BOOLEAN, context.currentCacheSetName(), Modifier.PRIVATE)
							.addField(context.currentType().getJTypeName(basePackageName),
									context.currentCacheName(), Modifier.PRIVATE);
				}
				if (context.direct()) {
					frame.addField(TypeName.INT, context.frameStartName(), Modifier.PRIVATE)
							.addField(TypeName.INT, context.frameLengthName(), Modifier.PRIVATE);
				}
			}

			frame.addMethod(MethodSpec.methodBuilder("acquire")
					.addModifiers(Modifier.PRIVATE)
					.returns(frameType)
					.addStatement("$T frame = this", frameType)
					.beginControlFlow("while (frame.inUse)")
					.beginControlFlow("if (frame.next == null)")
					.addStatement("frame.next = new $T()", frameType)
					.endControlFlow()
					.addStatement("frame = frame.next")
					.endControlFlow()
					.addStatement("frame.inUse = true")
					.addStatement("return frame")
					.build());

			MethodSpec.Builder bind = MethodSpec.methodBuilder("bind")
					.addModifiers(Modifier.PRIVATE)
					.addParameter(RandomAccessDataInput.class, "parent")
					.addParameter(stateType, "state")
					.addStatement("this.parent = parent")
					.addStatement("this.state = state");
			if (needsRecordRegion) {
				bind.addParameter(TypeName.INT, "recordStart")
						.addParameter(TypeName.INT, "recordLength")
						.addStatement("this.recordStart = recordStart")
						.addStatement("this.recordLength = recordLength");
			}
			for (LazyContext context : contexts) {
				if (context.direct()) {
					bind.addParameter(TypeName.INT, context.frameStartName())
							.addParameter(TypeName.INT, context.frameLengthName())
							.addStatement("this.$N = $N", context.frameStartName(), context.frameStartName())
							.addStatement("this.$N = $N", context.frameLengthName(), context.frameLengthName());
				}
			}
			frame.addMethod(bind.build());
			for (LazyContext context : contexts) {
				addContextGetter(frame, context, false);
				if (context.hasCurrent()) addContextGetter(frame, context, true);
				WireViewBinding binding = contextViews.get(context.fieldName());
				if (binding != null) {
					String suffix = capitalize(context.fieldName()) + "View";
					frame.addMethod(MethodSpec.methodBuilder("hasContext" + suffix)
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PUBLIC)
							.returns(TypeName.BOOLEAN)
							.addStatement("return true")
							.build());
					frame.addMethod(MethodSpec.methodBuilder("context" + suffix)
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PUBLIC)
							.returns(binding.interfaceType())
							.addStatement("return $N", binding.fieldName())
							.build());
				}
			}

			MethodSpec.Builder release = MethodSpec.methodBuilder("release")
					.addModifiers(Modifier.PRIVATE)
					.beginControlFlow("try")
					.beginControlFlow("if (contextCursor.isBound())")
					.addStatement("contextCursor.unbind()")
					.endControlFlow();
			for (WireViewBinding binding : contextViews.values()) {
				release.addStatement("$N.clear()", binding.fieldName());
			}
			release.nextControlFlow("finally")
					.addStatement("parent = null")
					.addStatement("state = null");
			if (needsRecordRegion) {
				release.addStatement("recordStart = 0").addStatement("recordLength = 0");
			}
			for (LazyContext context : contexts) {
				release.addStatement("$N = false", context.cacheSetName())
						.addStatement("$N = $L", context.cacheName(),
								defaultValue(context.type().getJTypeName(basePackageName)));
				if (context.hasCurrent()) {
					release.addStatement("$N = false", context.currentCacheSetName())
							.addStatement("$N = $L", context.currentCacheName(),
									defaultValue(context.currentType().getJTypeName(basePackageName)));
				}
				if (context.direct()) {
					release.addStatement("$N = 0", context.frameStartName())
							.addStatement("$N = 0", context.frameLengthName());
				}
			}
			release.addStatement("inUse = false").endControlFlow();
			frame.addMethod(release.build());
			classBuilder.addType(frame.build());

			MethodSpec.Builder helper = MethodSpec.methodBuilder(helperName)
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC)
					.returns(resultType.getJTypeName(basePackageName))
					.addParameter(RandomAccessDataInput.class, "input")
					.addParameter(stateType, "state");
			if (needsRecordRegion) {
				helper.addParameter(TypeName.INT, "recordStart")
						.addParameter(TypeName.INT, "recordLength");
			}
			for (LazyContext context : contexts) {
				if (context.direct()) {
					helper.addParameter(TypeName.INT, context.frameStartName())
							.addParameter(TypeName.INT, context.frameLengthName());
				}
			}
			helper.addStatement("$T frame = state.$N.acquire()", frameType, stateField);
			var bindCall = CodeBlock.builder().add("frame.bind(input, state");
			if (needsRecordRegion) bindCall.add(", recordStart, recordLength");
			for (LazyContext context : contexts) {
				if (context.direct()) {
					bindCall.add(", $N, $N", context.frameStartName(), context.frameLengthName());
				}
			}
			helper.addStatement("$L)", bindCall.build())
					.beginControlFlow("try")
					.addStatement("return $N.initialize(frame)", support.fieldName())
					.nextControlFlow("finally")
					.addStatement("frame.release()")
					.endControlFlow();
			classBuilder.addMethod(helper.build());
			readInitializerFrames.put(frameKey, helperName);
			return helperName;
		}

		private String generateReadUpgradeFrame(UpgradeDataConfiguration upgrade,
				TransformSupport support,
				ComputedTypeBase serializedOwner,
				ComputedTypeBase apiOwner,
				ComputedType oldType,
				ComputedType newType,
				boolean serializedAvailable,
				String sourceReader,
				ComputedType currentValueType,
				String currentSourceReader,
				String currentObjectMapper,
				List<LazyContext> contexts,
				boolean needsRecordRegion) {
			var frameKey = new ReadUpgradeFrameKey(upgrade, support, serializedOwner, apiOwner, oldType, newType,
					serializedAvailable, sourceReader, currentValueType, currentSourceReader, currentObjectMapper,
					List.copyOf(contexts), needsRecordRegion);
			String existingFrame = readUpgradeFrames.get(frameKey);
			if (existingFrame != null) return existingFrame;
			int id = nextReadFrameId++;
			String frameName = "ReadFrame" + id;
			String helperName = "applyReadUpgrade" + id;
			String stateField = "readFrame" + id;
			ClassName frameType = planClassName.nestedClass(frameName);
			ClassName stateType = planClassName.nestedClass("State");
			TypeName inputType = apiOwner.getJUpgraderName(basePackageName)
					.nestedClass(GenUpgraderBaseX.readInputInterfaceName(upgrade.from));
			boolean hasContexts = !contexts.isEmpty();
			boolean needsParent = serializedAvailable || hasContexts;
			boolean needsFrameState = serializedAvailable || hasContexts;

			stateBuilder.addField(FieldSpec.builder(frameType, stateField, Modifier.PRIVATE, Modifier.FINAL)
					.initializer("new $T()", frameType)
					.build());

			TypeSpec.Builder frame = TypeSpec.classBuilder(frameName)
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
					.addSuperinterface(inputType)
					.addField(frameType, "next", Modifier.PRIVATE)
					.addField(TypeName.BOOLEAN, "inUse", Modifier.PRIVATE)
					.addField(oldType.getJTypeName(basePackageName), "value", Modifier.PRIVATE);
			WireViewBinding valueView;
			if (serializedAvailable && isWireViewType(oldType)) {
				valueView = generateRegionWireView(apiOwner, oldType,
						GenUpgraderBaseX.valueWireViewInterfaceName(upgrade.from), frameType,
						CodeBlock.of("owner.sourceStart"), CodeBlock.of("owner.sourceLength"));
			} else if (isWireViewType(oldType)) {
				valueView = generateDelegatingWireView(apiOwner, oldType,
						GenUpgraderBaseX.valueWireViewInterfaceName(upgrade.from), frameType,
						CodeBlock.of("owner.value()"));
			} else {
				valueView = null;
			}
			if (valueView != null) {
				frame.addField(valueView.implementationType(), valueView.fieldName(), Modifier.PRIVATE, Modifier.FINAL);
			}
			var contextViews = new LinkedHashMap<String, WireViewBinding>();
			for (LazyContext context : contexts) {
				if (!isWireViewType(context.type())) continue;
				WireViewBinding binding = context.direct()
						? generateRegionWireView(apiOwner, context.type(),
								GenUpgraderBaseX.contextWireViewInterfaceName(upgrade.from, context.fieldName()),
								frameType, CodeBlock.of("owner.$N", context.frameStartName()),
								CodeBlock.of("owner.$N", context.frameLengthName()))
						: generateDelegatingWireView(apiOwner, context.type(),
								GenUpgraderBaseX.contextWireViewInterfaceName(upgrade.from, context.fieldName()),
								frameType, CodeBlock.of("owner.$N()", context.methodName()));
				contextViews.put(context.fieldName(), binding);
				frame.addField(binding.implementationType(), binding.fieldName(), Modifier.PRIVATE, Modifier.FINAL);
			}
			if (needsParent) frame.addField(RandomAccessDataInput.class, "parent", Modifier.PRIVATE);
			if (needsFrameState) frame.addField(stateType, "state", Modifier.PRIVATE);
			if (needsRecordRegion) {
				frame.addField(TypeName.INT, "recordStart", Modifier.PRIVATE)
						.addField(TypeName.INT, "recordLength", Modifier.PRIVATE);
			}
			if (serializedAvailable) {
				frame.addField(BufDataCursor.class, "valueCursor", Modifier.PRIVATE, Modifier.FINAL)
						.addField(TypeName.BOOLEAN, "valueSet", Modifier.PRIVATE)
						.addField(TypeName.BOOLEAN, "serializedOpen", Modifier.PRIVATE);
			}
			if (currentValueType != null) {
				frame.addField(currentValueType.getJTypeName(basePackageName), "currentValue", Modifier.PRIVATE)
						.addField(TypeName.BOOLEAN, "currentValueSet", Modifier.PRIVATE)
						.addField(TypeName.BOOLEAN, "valueUsed", Modifier.PRIVATE);
			}
			if (hasContexts) {
				frame.addField(BufDataCursor.class, "contextCursor", Modifier.PRIVATE, Modifier.FINAL);
			}
			MethodSpec.Builder frameConstructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE);
			if (valueView != null) {
				frameConstructor.addStatement("this.$N = new $T(this)", valueView.fieldName(),
						valueView.implementationType());
			}
			for (WireViewBinding binding : contextViews.values()) {
				frameConstructor.addStatement("this.$N = new $T(this)", binding.fieldName(),
						binding.implementationType());
			}
			if (serializedAvailable) {
				frameConstructor.addStatement("this.valueCursor = $T.borrowed()", BufDataCursor.class);
			}
			if (hasContexts) {
				frameConstructor.addStatement("this.contextCursor = $T.borrowed()", BufDataCursor.class);
			}
			frame.addMethod(frameConstructor.build());
			if (serializedAvailable) {
				frame.addField(TypeName.INT, "sourceStart", Modifier.PRIVATE)
						.addField(TypeName.INT, "sourceLength", Modifier.PRIVATE);
			}
			for (LazyContext context : contexts) {
				frame.addField(TypeName.BOOLEAN, context.cacheSetName(), Modifier.PRIVATE)
						.addField(context.type().getJTypeName(basePackageName), context.cacheName(), Modifier.PRIVATE);
				if (context.hasCurrent()) {
					frame.addField(TypeName.BOOLEAN, context.currentCacheSetName(), Modifier.PRIVATE)
							.addField(context.currentType().getJTypeName(basePackageName),
									context.currentCacheName(), Modifier.PRIVATE);
				}
				if (context.direct()) {
					frame.addField(TypeName.INT, context.frameStartName(), Modifier.PRIVATE)
							.addField(TypeName.INT, context.frameLengthName(), Modifier.PRIVATE);
				}
			}

			frame.addMethod(MethodSpec.methodBuilder("acquire")
					.addModifiers(Modifier.PRIVATE)
					.returns(frameType)
					.addStatement("$T frame = this", frameType)
					.beginControlFlow("while (frame.inUse)")
					.beginControlFlow("if (frame.next == null)")
					.addStatement("frame.next = new $T()", frameType)
					.endControlFlow()
					.addStatement("frame = frame.next")
					.endControlFlow()
					.addStatement("frame.inUse = true")
					.addStatement("return frame")
					.build());

			MethodSpec.Builder bind = MethodSpec.methodBuilder("bind")
					.addModifiers(Modifier.PRIVATE);
			if (needsParent) {
				bind.addParameter(RandomAccessDataInput.class, "parent")
						.addStatement("this.parent = parent");
			}
			if (needsFrameState) {
				bind.addParameter(stateType, "state")
						.addStatement("this.state = state");
			}
			if (needsRecordRegion) {
				bind.addParameter(TypeName.INT, "recordStart")
						.addParameter(TypeName.INT, "recordLength")
						.addStatement("this.recordStart = recordStart")
						.addStatement("this.recordLength = recordLength");
			}
			if (serializedAvailable) {
				bind.addParameter(TypeName.INT, "sourceStart")
						.addParameter(TypeName.INT, "sourceLength")
						.addStatement("this.sourceStart = sourceStart")
						.addStatement("this.sourceLength = sourceLength");
			} else {
				bind.addParameter(oldType.getJTypeName(basePackageName), "value")
						.addStatement("this.value = value");
			}
			for (LazyContext context : contexts) {
				if (context.direct()) {
					bind.addParameter(TypeName.INT, context.frameStartName())
							.addParameter(TypeName.INT, context.frameLengthName())
							.addStatement("this.$N = $N", context.frameStartName(), context.frameStartName())
							.addStatement("this.$N = $N", context.frameLengthName(), context.frameLengthName());
				}
			}
			frame.addMethod(bind.build());

			MethodSpec.Builder value = MethodSpec.methodBuilder("value")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(oldType.getJTypeName(basePackageName));
			if (currentValueType != null) {
				value.beginControlFlow("if (currentValueSet)")
						.addStatement("throw new $T($S)", IllegalStateException.class,
								"currentValue() and value() are mutually exclusive")
						.endControlFlow();
			}
			if (serializedAvailable) {
				value.beginControlFlow("if (!valueSet)")
						.beginControlFlow("if (serializedOpen)")
						.addStatement("throw new $T($S)", IllegalStateException.class,
								"serializedValue() and value() are mutually exclusive")
						.endControlFlow()
						.addStatement("parent.bindRegion(valueCursor, sourceStart, sourceLength)")
						.beginControlFlow("try")
						.addStatement("value = $N(valueCursor, state)", sourceReader)
						.addStatement("int trailing = valueCursor.remainingIncludingClosed()")
						.beginControlFlow("if (trailing != 0)")
						.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
								"Trailing bytes in lazy upgrade value: ")
						.endControlFlow()
						.addStatement("valueSet = true")
						.nextControlFlow("finally")
						.addStatement("valueCursor.unbind()")
						.endControlFlow()
						.endControlFlow();
			}
			if (currentValueType != null) value.addStatement("valueUsed = true");
			value.addStatement("return value");
			frame.addMethod(value.build());
			if (valueView != null) {
				frame.addMethod(MethodSpec.methodBuilder("hasValueView")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(TypeName.BOOLEAN)
						.addStatement("return true")
						.build());
				frame.addMethod(MethodSpec.methodBuilder("valueView")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(valueView.interfaceType())
						.addStatement("return $N", valueView.fieldName())
						.build());
			}

			if (currentValueType != null) {
				MethodSpec.Builder currentValue = MethodSpec.methodBuilder("currentValue")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(currentValueType.getJTypeName(basePackageName))
						.beginControlFlow("if (!currentValueSet)");
				if (serializedAvailable) {
					currentValue.beginControlFlow("if (serializedOpen || valueUsed)")
							.addStatement("throw new $T($S)", IllegalStateException.class,
									"value(), currentValue(), and serializedValue() are mutually exclusive")
							.endControlFlow()
							.addStatement("parent.bindRegion(valueCursor, sourceStart, sourceLength)")
							.beginControlFlow("try")
							.addStatement("currentValue = $N(valueCursor, state)", currentSourceReader)
							.addStatement("int trailing = valueCursor.remainingIncludingClosed()")
							.beginControlFlow("if (trailing != 0)")
							.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
									"Trailing bytes in lazy current upgrade value: ")
							.endControlFlow()
							.addStatement("currentValueSet = true")
							.nextControlFlow("finally")
							.addStatement("valueCursor.unbind()")
							.endControlFlow();
				} else {
					currentValue.beginControlFlow("if (valueUsed)")
							.addStatement("throw new $T($S)", IllegalStateException.class,
									"value() and currentValue() are mutually exclusive")
							.endControlFlow();
					currentValue.addStatement("currentValue = $N(value)", currentObjectMapper)
							.addStatement("currentValueSet = true");
				}
				currentValue.endControlFlow().addStatement("return currentValue");
				frame.addMethod(currentValue.build());
			}

			frame.addMethod(MethodSpec.methodBuilder("hasSerializedValue")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(TypeName.BOOLEAN)
					.addStatement("return $L", serializedAvailable)
					.build());
			MethodSpec.Builder serializedVersion = MethodSpec.methodBuilder("serializedVersion")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(TypeName.INT);
			if (serializedAvailable) {
				serializedVersion.addStatement("return $L", serializedOwner.getVersion().getVersion());
			} else {
				serializedVersion.addStatement("throw new $T($S)", IllegalStateException.class,
						"No serialized value is available for this upgrade");
			}
			frame.addMethod(serializedVersion.build());

			MethodSpec.Builder serializedValue = MethodSpec.methodBuilder("serializedValue")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(SafeDataInput.class);
			if (serializedAvailable) {
				if (currentValueType != null) {
					serializedValue.beginControlFlow("if (currentValueSet)")
							.addStatement("throw new $T($S)", IllegalStateException.class,
									"currentValue() and serializedValue() are mutually exclusive")
							.endControlFlow();
				}
				serializedValue.beginControlFlow("if (valueSet)")
						.addStatement("throw new $T($S)", IllegalStateException.class,
								"value() and serializedValue() are mutually exclusive")
						.endControlFlow()
						.beginControlFlow("if (!serializedOpen)")
						.addStatement("parent.bindRegion(valueCursor, sourceStart, sourceLength)")
						.addStatement("serializedOpen = true")
						.endControlFlow()
						.addStatement("return valueCursor");
			} else {
				serializedValue.addStatement("throw new $T($S)", IllegalStateException.class,
						"No serialized value is available for this upgrade");
			}
			frame.addMethod(serializedValue.build());

			for (LazyContext context : contexts) {
				addContextGetter(frame, context, false);
				if (context.hasCurrent()) addContextGetter(frame, context, true);
				WireViewBinding binding = contextViews.get(context.fieldName());
				if (binding != null) {
					String suffix = capitalize(context.fieldName()) + "View";
					frame.addMethod(MethodSpec.methodBuilder("hasContext" + suffix)
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PUBLIC)
							.returns(TypeName.BOOLEAN)
							.addStatement("return true")
							.build());
					frame.addMethod(MethodSpec.methodBuilder("context" + suffix)
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PUBLIC)
							.returns(binding.interfaceType())
							.addStatement("return $N", binding.fieldName())
							.build());
				}
			}

			MethodSpec.Builder release = MethodSpec.methodBuilder("release")
					.addModifiers(Modifier.PRIVATE)
					.addParameter(TypeName.BOOLEAN, "successful");
			if (serializedAvailable) {
				release.addStatement("int trailing = successful && serializedOpen ? "
						+ "valueCursor.remainingIncludingClosed() : 0");
			}
			release.beginControlFlow("try");
			if (serializedAvailable) {
				release.beginControlFlow("if (valueCursor.isBound())")
						.addStatement("valueCursor.unbind()")
						.endControlFlow();
			}
			if (hasContexts) {
				release.beginControlFlow("if (contextCursor.isBound())")
						.addStatement("contextCursor.unbind()")
						.endControlFlow();
			}
			if (valueView != null) release.addStatement("$N.clear()", valueView.fieldName());
			for (WireViewBinding binding : contextViews.values()) {
				release.addStatement("$N.clear()", binding.fieldName());
			}
			release.nextControlFlow("finally");
			if (needsParent) release.addStatement("parent = null");
			if (needsFrameState) release.addStatement("state = null");
			if (needsRecordRegion) {
				release.addStatement("recordStart = 0").addStatement("recordLength = 0");
			}
			if (serializedAvailable) {
				release.addStatement("valueSet = false")
						.addStatement("serializedOpen = false")
						.addStatement("sourceStart = 0")
						.addStatement("sourceLength = 0");
			}
			if (currentValueType != null) {
				release.addStatement("currentValueSet = false")
						.addStatement("valueUsed = false")
						.addStatement("currentValue = $L",
								defaultValue(currentValueType.getJTypeName(basePackageName)));
			}
			release.addStatement("value = $L", defaultValue(oldType.getJTypeName(basePackageName)));
			for (LazyContext context : contexts) {
				release.addStatement("$N = false", context.cacheSetName())
						.addStatement("$N = $L", context.cacheName(),
								defaultValue(context.type().getJTypeName(basePackageName)));
				if (context.hasCurrent()) {
					release.addStatement("$N = false", context.currentCacheSetName())
							.addStatement("$N = $L", context.currentCacheName(),
									defaultValue(context.currentType().getJTypeName(basePackageName)));
				}
				if (context.direct()) {
					release.addStatement("$N = 0", context.frameStartName())
							.addStatement("$N = 0", context.frameLengthName());
				}
			}
			release.addStatement("inUse = false")
					.endControlFlow();
			if (serializedAvailable) {
				release.beginControlFlow("if (trailing != 0)")
						.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
								"Trailing bytes in serialized upgrade value: ")
						.endControlFlow();
			}
			frame.addMethod(release.build());
			classBuilder.addType(frame.build());

			MethodSpec.Builder helper = MethodSpec.methodBuilder(helperName)
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC)
					.returns(newType.getJTypeName(basePackageName))
					.addParameter(RandomAccessDataInput.class, "input")
					.addParameter(stateType, "state");
			if (needsRecordRegion) {
				helper.addParameter(TypeName.INT, "recordStart")
						.addParameter(TypeName.INT, "recordLength");
			}
			if (serializedAvailable) {
				helper.addParameter(TypeName.INT, "sourceStart").addParameter(TypeName.INT, "sourceLength");
			} else {
				helper.addParameter(oldType.getJTypeName(basePackageName), "value");
			}
			for (LazyContext context : contexts) {
				if (context.direct()) {
					helper.addParameter(TypeName.INT, context.frameStartName())
							.addParameter(TypeName.INT, context.frameLengthName());
				}
			}
			helper.addStatement("$T frame = state.$N.acquire()", frameType, stateField);
			var bindCall = CodeBlock.builder().add("frame.bind(");
			boolean firstBindArgument = true;
			if (needsParent) {
				bindCall.add("input");
				firstBindArgument = false;
			}
			if (needsFrameState) {
				if (!firstBindArgument) bindCall.add(", ");
				bindCall.add("state");
				firstBindArgument = false;
			}
			if (needsRecordRegion) {
				if (!firstBindArgument) bindCall.add(", ");
				bindCall.add("recordStart, recordLength");
				firstBindArgument = false;
			}
			if (serializedAvailable) {
				if (!firstBindArgument) bindCall.add(", ");
				bindCall.add("sourceStart, sourceLength");
				firstBindArgument = false;
			} else {
				if (!firstBindArgument) bindCall.add(", ");
				bindCall.add("value");
				firstBindArgument = false;
			}
			for (LazyContext context : contexts) {
				if (context.direct()) {
					if (!firstBindArgument) bindCall.add(", ");
					bindCall.add("$N, $N", context.frameStartName(), context.frameLengthName());
					firstBindArgument = false;
				}
			}
			helper.addStatement("$L)", bindCall.build());
			helper.addStatement("boolean successful = false")
					.beginControlFlow("try")
					.addStatement("$T result = $N.upgrade(frame)", newType.getJTypeName(basePackageName), support.fieldName())
					.addStatement("successful = true")
					.addStatement("return result")
					.nextControlFlow("finally")
					.addStatement("frame.release(successful)")
					.endControlFlow();
			classBuilder.addMethod(helper.build());
			readUpgradeFrames.put(frameKey, helperName);
			return helperName;
		}

		private void addContextGetter(TypeSpec.Builder frame, LazyContext context, boolean current) {
			String methodName = current ? context.currentMethodName() : context.methodName();
			String cacheName = current ? context.currentCacheName() : context.cacheName();
			String cacheSetName = current ? context.currentCacheSetName() : context.cacheSetName();
			ComputedType resultType = current ? context.currentType() : context.type();
			String reader = current ? context.currentReaderMethod()
					: context.direct() ? context.directReaderMethod() : context.readerMethod();
			MethodSpec.Builder getter = MethodSpec.methodBuilder(methodName)
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(resultType.getJTypeName(basePackageName))
					.beginControlFlow("if (!$N)", cacheSetName);
			if (context.direct()) {
				getter.addStatement("parent.bindRegion(contextCursor, $N, $N)",
						context.frameStartName(), context.frameLengthName());
			} else {
				getter.addStatement("parent.bindRegion(contextCursor, recordStart, recordLength)");
			}
			getter.beginControlFlow("try")
					.addStatement("$N = $N(contextCursor, state)", cacheName, reader)
					.addStatement("int trailing = contextCursor.remainingIncludingClosed()")
					.beginControlFlow("if (trailing != 0)")
					.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
							"Trailing bytes in lazy upgrade context: ")
					.endControlFlow()
					.addStatement("$N = true", cacheSetName)
					.nextControlFlow("finally")
					.beginControlFlow("if (contextCursor.isBound())")
					.addStatement("contextCursor.unbind()")
					.endControlFlow()
					.endControlFlow()
					.endControlFlow()
					.addStatement("return $N", cacheName);
			frame.addMethod(getter.build());
		}

		private WireViewBinding generateDelegatingWireView(ComputedTypeBase apiOwner,
				ComputedType viewedType,
				String interfaceName,
				ClassName frameType,
				CodeBlock ownerValue) {
			WireViewBinding active = activeDelegatingWireViews.get(viewedType);
			if (active != null) {
				String fieldName = "wireView" + nextWireViewId++;
				recursiveDelegatingWireViewFields.add(fieldName);
				return new WireViewBinding(fieldName, active.implementationType(), active.interfaceType());
			}
			int id = nextWireViewId++;
			var result = new WireViewBinding("wireView" + id, planClassName.nestedClass("WireView" + id),
					apiOwner.getJUpgraderName(basePackageName).nestedClass(interfaceName));
			activeDelegatingWireViews.put(viewedType, result);
			try {
				return generateDelegatingWireViewBody(apiOwner, viewedType, interfaceName, frameType, ownerValue,
						result);
			} finally {
				activeDelegatingWireViews.remove(viewedType);
			}
		}

		private WireViewBinding generateDelegatingWireViewBody(ComputedTypeBase apiOwner,
				ComputedType viewedType,
				String interfaceName,
				ClassName frameType,
				CodeBlock ownerValue,
				WireViewBinding result) {
			String implementationName = result.implementationType().simpleName();
			String fieldName = result.fieldName();
			ClassName implementationType = result.implementationType();
			TypeName interfaceType = result.interfaceType();
			TypeName viewedJavaType = viewedType.getJTypeName(basePackageName);
			CodeBlock rootOwnerValue = ownerValue;
			TypeSpec.Builder view = TypeSpec.classBuilder(implementationName)
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
					.addSuperinterface(interfaceType)
					.addField(frameType, "owner", Modifier.PRIVATE, Modifier.FINAL)
					.addField(TypeName.BOOLEAN, "recursiveBound", Modifier.PRIVATE)
					.addField(viewedJavaType, "recursiveValue", Modifier.PRIVATE)
					.addMethod(MethodSpec.constructorBuilder()
							.addModifiers(Modifier.PRIVATE)
							.addParameter(frameType, "owner")
							.addStatement("this.owner = owner")
							.build())
					.addMethod(MethodSpec.constructorBuilder()
							.addModifiers(Modifier.PRIVATE)
							.addStatement("this.owner = null")
							.build())
					.addMethod(MethodSpec.methodBuilder("wireValue")
							.addModifiers(Modifier.PRIVATE)
							.returns(viewedJavaType)
							.beginControlFlow("if (recursiveBound)")
							.addStatement("return recursiveValue")
							.endControlFlow()
							.addStatement("return ($T) $L", viewedJavaType, rootOwnerValue)
							.build())
					.addMethod(MethodSpec.methodBuilder("rebind")
							.addModifiers(Modifier.PRIVATE)
							.addParameter(viewedJavaType, "recursiveValue")
							.addStatement("clear()")
							.addStatement("this.recursiveBound = true")
							.addStatement("this.recursiveValue = recursiveValue")
							.build());
			ownerValue = CodeBlock.of("wireValue()");
			WireViewBinding reusableElementView = null;
			WireViewBinding reusableNullableValueView = null;
			var reusableFieldViews = new LinkedHashMap<String, WireViewBinding>();
			var reusableSubtypeViews = new LinkedHashMap<Integer, WireViewBinding>();
			if (viewedType instanceof ComputedTypeBase record) {
				for (var field : record.getData().entrySet()) {
					if (!isWireViewType(field.getValue())) continue;
					String valueName = "field" + capitalize(field.getKey()) + "ViewValue";
					view.addField(field.getValue().getJTypeName(basePackageName), valueName, Modifier.PRIVATE);
					WireViewBinding binding = generateDelegatingWireView(apiOwner, field.getValue(),
							GenUpgraderBaseX.wireRecordFieldViewInterfaceName(interfaceName, field.getKey()),
							implementationType, CodeBlock.of("owner.$N", valueName));
					reusableFieldViews.put(field.getKey(), binding);
					boolean recursive = recursiveDelegatingWireViewFields.contains(binding.fieldName());
					var fieldBuilder = FieldSpec.builder(binding.implementationType(), binding.fieldName(),
							Modifier.PRIVATE);
					if (!recursive) {
						fieldBuilder.addModifiers(Modifier.FINAL)
								.initializer("new $T(this)", binding.implementationType());
					}
					view.addField(fieldBuilder.build());
				}
				for (var field : record.getData().entrySet()) {
					view.addMethod(MethodSpec.methodBuilder(field.getKey())
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PUBLIC)
							.returns(field.getValue().getJTypeName(basePackageName))
							.addStatement("return $L", wireViewField(ownerValue, record, field.getKey(), field.getValue()))
							.build());
					WireViewBinding fieldView = reusableFieldViews.get(field.getKey());
					if (fieldView != null) {
						String valueName = "field" + capitalize(field.getKey()) + "ViewValue";
						MethodSpec.Builder fieldViewGetter = MethodSpec.methodBuilder(field.getKey() + "View")
								.addAnnotation(Override.class)
								.addModifiers(Modifier.PUBLIC)
								.returns(fieldView.interfaceType())
								.addStatement("$N = $L", valueName,
										wireViewField(ownerValue, record, field.getKey(), field.getValue()));
						if (recursiveDelegatingWireViewFields.contains(fieldView.fieldName())) {
							fieldViewGetter.beginControlFlow("if ($N == null)", fieldView.fieldName())
									.addStatement("$N = new $T()", fieldView.fieldName(), fieldView.implementationType())
									.endControlFlow()
									.addStatement("$N.rebind($N)", fieldView.fieldName(), valueName);
						} else {
							fieldViewGetter.addStatement("$N.clear()", fieldView.fieldName());
						}
						fieldViewGetter.addStatement("return $N", fieldView.fieldName());
						view.addMethod(fieldViewGetter.build());
					}
				}
			} else if (viewedType instanceof ComputedTypeArray array) {
				TypeName cursorType = apiOwner.getJUpgraderName(basePackageName).nestedClass(
						GenUpgraderBaseX.wireArrayCursorInterfaceName(interfaceName));
				view.addSuperinterface(cursorType)
						.addField(TypeName.INT, "cursorIndex", Modifier.PRIVATE);
				if (isWireViewType(array.getBase())) {
					view.addField(array.getBase().getJTypeName(basePackageName), "elementValue", Modifier.PRIVATE);
					reusableElementView = generateDelegatingWireView(apiOwner, array.getBase(),
							GenUpgraderBaseX.wireElementViewInterfaceName(interfaceName), implementationType,
							CodeBlock.of("owner.elementValue"));
					boolean recursive = recursiveDelegatingWireViewFields.contains(reusableElementView.fieldName());
					var fieldBuilder = FieldSpec.builder(reusableElementView.implementationType(),
							reusableElementView.fieldName(), Modifier.PRIVATE);
					if (!recursive) {
						fieldBuilder.addModifiers(Modifier.FINAL)
								.initializer("new $T(this)", reusableElementView.implementationType());
					}
					view.addField(fieldBuilder.build());
				}
				view.addMethod(MethodSpec.methodBuilder("size")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(TypeName.INT)
						.addStatement("return (($T) $L).length", viewedType.getJTypeName(basePackageName), ownerValue)
						.build());
				view.addMethod(MethodSpec.methodBuilder("get")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(array.getBase().getJTypeName(basePackageName))
						.addParameter(TypeName.INT, "index")
						.addStatement("$T source = ($T) $L", viewedType.getJTypeName(basePackageName),
								viewedType.getJTypeName(basePackageName), ownerValue)
						.addStatement("return source[$T.checkIndex(index, source.length)]", Objects.class)
						.build());
				view.addMethod(MethodSpec.methodBuilder("copy")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(viewedType.getJTypeName(basePackageName))
						.addStatement("$T source = ($T) $L", viewedType.getJTypeName(basePackageName),
								viewedType.getJTypeName(basePackageName), ownerValue)
						.addStatement("return source.length == 0 ? $T.emptyArray() : source.clone()",
								array.getJSerializerName(basePackageName))
						.build());
				if (reusableElementView != null) {
					MethodSpec.Builder elementView = MethodSpec.methodBuilder("elementView")
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PUBLIC)
							.returns(reusableElementView.interfaceType())
							.addParameter(TypeName.INT, "index")
							.addStatement("$T source = ($T) $L", viewedType.getJTypeName(basePackageName),
									viewedType.getJTypeName(basePackageName), ownerValue)
							.addStatement("$T.checkIndex(index, source.length)", Objects.class)
							.addStatement("elementValue = source[index]");
					if (recursiveDelegatingWireViewFields.contains(reusableElementView.fieldName())) {
						elementView.beginControlFlow("if ($N == null)", reusableElementView.fieldName())
								.addStatement("$N = new $T()", reusableElementView.fieldName(),
										reusableElementView.implementationType())
								.endControlFlow()
								.addStatement("$N.rebind(elementValue)", reusableElementView.fieldName());
					} else {
						elementView.addStatement("$N.clear()", reusableElementView.fieldName());
					}
					elementView.addStatement("return $N", reusableElementView.fieldName());
					view.addMethod(elementView.build());
				}
				view.addMethod(MethodSpec.methodBuilder("cursor")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(cursorType)
						.addStatement("cursorIndex = 0")
						.addStatement("return this")
						.build())
					.addMethod(MethodSpec.methodBuilder("hasNext")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(TypeName.BOOLEAN)
						.addStatement("return cursorIndex < size()")
						.build())
					.addMethod(MethodSpec.methodBuilder("next")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(array.getBase().getJTypeName(basePackageName))
						.beginControlFlow("if (!hasNext())")
						.addStatement("throw new $T()", java.util.NoSuchElementException.class)
						.endControlFlow()
						.addStatement("return get(cursorIndex++)")
						.build());
				if (reusableElementView != null) {
					view.addMethod(MethodSpec.methodBuilder("nextView")
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PUBLIC)
							.returns(reusableElementView.interfaceType())
							.beginControlFlow("if (!hasNext())")
							.addStatement("throw new $T()", java.util.NoSuchElementException.class)
							.endControlFlow()
							.addStatement("return elementView(cursorIndex++)")
							.build());
				}
			} else if (viewedType instanceof ComputedTypeNullable nullable) {
				if (isWireViewType(nullable.getBase())) {
					view.addField(nullable.getBase().getJTypeName(basePackageName), "nullableValue", Modifier.PRIVATE);
					reusableNullableValueView = generateDelegatingWireView(apiOwner, nullable.getBase(),
							GenUpgraderBaseX.wireNullableValueViewInterfaceName(interfaceName), implementationType,
							CodeBlock.of("owner.nullableValue"));
					boolean recursive = recursiveDelegatingWireViewFields.contains(reusableNullableValueView.fieldName());
					var fieldBuilder = FieldSpec.builder(reusableNullableValueView.implementationType(),
							reusableNullableValueView.fieldName(), Modifier.PRIVATE);
					if (!recursive) {
						fieldBuilder.addModifiers(Modifier.FINAL)
								.initializer("new $T(this)", reusableNullableValueView.implementationType());
					}
					view.addField(fieldBuilder.build());
				}
				view.addMethod(MethodSpec.methodBuilder("isPresent")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(TypeName.BOOLEAN)
						.addStatement("return (($T) $L).getNullable() != null",
								viewedType.getJTypeName(basePackageName), ownerValue)
						.build());
				view.addMethod(MethodSpec.methodBuilder("value")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(nullable.getBase().getJTypeName(basePackageName))
						.addStatement("return (($T) $L).get()", viewedType.getJTypeName(basePackageName), ownerValue)
						.build());
				if (reusableNullableValueView != null) {
					MethodSpec.Builder valueView = MethodSpec.methodBuilder("valueView")
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PUBLIC)
							.returns(reusableNullableValueView.interfaceType())
							.beginControlFlow("if (!isPresent())")
							.addStatement("throw new $T($S)", java.util.NoSuchElementException.class,
									"Nullable wire value is empty")
							.endControlFlow()
							.addStatement("nullableValue = (($T) $L).get()",
									viewedType.getJTypeName(basePackageName), ownerValue);
					if (recursiveDelegatingWireViewFields.contains(reusableNullableValueView.fieldName())) {
						valueView.beginControlFlow("if ($N == null)", reusableNullableValueView.fieldName())
								.addStatement("$N = new $T()", reusableNullableValueView.fieldName(),
										reusableNullableValueView.implementationType())
								.endControlFlow()
								.addStatement("$N.rebind(nullableValue)", reusableNullableValueView.fieldName());
					} else {
						valueView.addStatement("$N.clear()", reusableNullableValueView.fieldName());
					}
					valueView.addStatement("return $N", reusableNullableValueView.fieldName());
					view.addMethod(valueView.build());
				}
			} else if (viewedType instanceof ComputedTypeSuper union) {
				TypeName kindType = apiOwner.getJUpgraderName(basePackageName)
						.nestedClass(interfaceName + "Kind");
				for (int index = 0; index < union.subTypes().size(); index++) {
					ComputedType subtype = union.subTypes().get(index);
					if (!isWireViewType(subtype)) continue;
					String valueName = "variant" + capitalize(subtype.getName()) + "Value";
					view.addField(subtype.getJTypeName(basePackageName), valueName, Modifier.PRIVATE);
					WireViewBinding binding = generateDelegatingWireView(apiOwner, subtype,
							GenUpgraderBaseX.wireUnionSubtypeViewInterfaceName(interfaceName, subtype.getName()),
							implementationType, CodeBlock.of("owner.$N", valueName));
					reusableSubtypeViews.put(index, binding);
					boolean recursive = recursiveDelegatingWireViewFields.contains(binding.fieldName());
					var fieldBuilder = FieldSpec.builder(binding.implementationType(), binding.fieldName(),
							Modifier.PRIVATE);
					if (!recursive) {
						fieldBuilder.addModifiers(Modifier.FINAL)
								.initializer("new $T(this)", binding.implementationType());
					}
					view.addField(fieldBuilder.build());
				}
				MethodSpec.Builder kind = MethodSpec.methodBuilder("kind")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(kindType)
						.beginControlFlow("return switch ((($T) $L).getMetaId$$$N())",
								viewedType.getJTypeName(basePackageName), ownerValue, union.getName());
				for (int index = 0; index < union.subTypes().size(); index++) {
					kind.addStatement("case $L -> $T.$N", index, kindType, union.subTypes().get(index).getName());
				}
				kind.addStatement("default -> throw new $T()", AssertionError.class).addCode("$<};\n");
				view.addMethod(kind.build());
				for (int index = 0; index < union.subTypes().size(); index++) {
					ComputedType subtype = union.subTypes().get(index);
					view.addMethod(MethodSpec.methodBuilder("as" + capitalize(subtype.getName()))
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PUBLIC)
							.returns(subtype.getJTypeName(basePackageName))
							.addStatement("return ($T) (($T) $L)", subtype.getJTypeName(basePackageName),
									viewedType.getJTypeName(basePackageName), ownerValue)
							.build());
					WireViewBinding subtypeView = reusableSubtypeViews.get(index);
					if (subtypeView != null) {
						String valueName = "variant" + capitalize(subtype.getName()) + "Value";
						MethodSpec.Builder subtypeViewGetter = MethodSpec.methodBuilder(
								"as" + capitalize(subtype.getName()) + "View")
								.addAnnotation(Override.class)
								.addModifiers(Modifier.PUBLIC)
								.returns(subtypeView.interfaceType())
								.beginControlFlow("if (kind() != $T.$N)", kindType, subtype.getName())
								.addStatement("throw new $T($S + kind())", IllegalStateException.class,
										"Wire union is not " + subtype.getName() + ": ")
								.endControlFlow()
								.addStatement("$N = ($T) (($T) $L)", valueName,
										subtype.getJTypeName(basePackageName), viewedType.getJTypeName(basePackageName),
										ownerValue);
						if (recursiveDelegatingWireViewFields.contains(subtypeView.fieldName())) {
							subtypeViewGetter.beginControlFlow("if ($N == null)", subtypeView.fieldName())
									.addStatement("$N = new $T()", subtypeView.fieldName(),
											subtypeView.implementationType())
									.endControlFlow()
									.addStatement("$N.rebind($N)", subtypeView.fieldName(), valueName);
						} else {
							subtypeViewGetter.addStatement("$N.clear()", subtypeView.fieldName());
						}
						subtypeViewGetter.addStatement("return $N", subtypeView.fieldName());
						view.addMethod(subtypeViewGetter.build());
					}
				}
			} else {
				throw generationError("wire view requested for scalar " + viewedType);
			}
			MethodSpec.Builder clear = MethodSpec.methodBuilder("clear")
					.addModifiers(Modifier.PRIVATE);
			if (reusableElementView != null) {
				if (recursiveDelegatingWireViewFields.contains(reusableElementView.fieldName())) {
					clear.beginControlFlow("if ($N != null)", reusableElementView.fieldName())
							.addStatement("$N.clear()", reusableElementView.fieldName())
							.endControlFlow();
				} else {
					clear.addStatement("$N.clear()", reusableElementView.fieldName());
				}
				clear.addStatement("elementValue = null");
			}
			if (reusableNullableValueView != null) {
				if (recursiveDelegatingWireViewFields.contains(reusableNullableValueView.fieldName())) {
					clear.beginControlFlow("if ($N != null)", reusableNullableValueView.fieldName())
							.addStatement("$N.clear()", reusableNullableValueView.fieldName())
							.endControlFlow();
				} else {
					clear.addStatement("$N.clear()", reusableNullableValueView.fieldName());
				}
				clear.addStatement("nullableValue = null");
			}
			for (var entry : reusableFieldViews.entrySet()) {
				WireViewBinding binding = entry.getValue();
				if (recursiveDelegatingWireViewFields.contains(binding.fieldName())) {
					clear.beginControlFlow("if ($N != null)", binding.fieldName())
							.addStatement("$N.clear()", binding.fieldName())
							.endControlFlow();
				} else {
					clear.addStatement("$N.clear()", binding.fieldName());
				}
				clear.addStatement("$N = null", "field" + capitalize(entry.getKey()) + "ViewValue");
			}
			for (int index = 0; index < reusableSubtypeViews.size(); index++) {
				WireViewBinding binding = reusableSubtypeViews.get(index);
				if (binding == null) continue;
				ComputedType subtype = ((ComputedTypeSuper) viewedType).subTypes().get(index);
				if (recursiveDelegatingWireViewFields.contains(binding.fieldName())) {
					clear.beginControlFlow("if ($N != null)", binding.fieldName())
							.addStatement("$N.clear()", binding.fieldName())
							.endControlFlow();
				} else {
					clear.addStatement("$N.clear()", binding.fieldName());
				}
				clear.addStatement("$N = null", "variant" + capitalize(subtype.getName()) + "Value");
			}
			if (viewedType instanceof ComputedTypeArray) clear.addStatement("cursorIndex = 0");
			clear.addStatement("recursiveBound = false")
					.addStatement("recursiveValue = null");
			view.addMethod(clear.build());
			classBuilder.addType(view.build());
			return result;
		}

		private void addRegionViewInfrastructure(TypeSpec.Builder view,
				ClassName frameType,
				CodeBlock regionStart,
				CodeBlock regionLength) {
			ClassName ownerInterface = planClassName.nestedClass("WireRegionOwner");
			boolean ownerIsRegionView = frameType.simpleName().startsWith("WireView");
			view.addSuperinterface(ownerInterface)
					.addField(frameType, "owner", Modifier.PRIVATE, Modifier.FINAL)
					.addField(ownerInterface, "recursiveOwner", Modifier.PRIVATE)
					.addField(TypeName.INT, "recursiveStart", Modifier.PRIVATE)
					.addField(TypeName.INT, "recursiveLength", Modifier.PRIVATE)
					.addField(BufDataCursor.class, "scanCursor", Modifier.PRIVATE, Modifier.FINAL)
					.addField(BufDataCursor.class, "valueCursor", Modifier.PRIVATE, Modifier.FINAL)
					.addMethod(MethodSpec.constructorBuilder()
							.addModifiers(Modifier.PRIVATE)
							.addParameter(frameType, "owner")
							.addStatement("this.owner = owner")
							.addStatement("this.scanCursor = $T.borrowed()", BufDataCursor.class)
							.addStatement("this.valueCursor = $T.borrowed()", BufDataCursor.class)
							.build())
					.addMethod(MethodSpec.constructorBuilder()
							.addModifiers(Modifier.PRIVATE)
							.addStatement("this.owner = null")
							.addStatement("this.scanCursor = $T.borrowed()", BufDataCursor.class)
							.addStatement("this.valueCursor = $T.borrowed()", BufDataCursor.class)
							.build());
			MethodSpec.Builder parent = MethodSpec.methodBuilder("wireParent")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(RandomAccessDataInput.class)
					.beginControlFlow("if (recursiveOwner != null)")
					.addStatement("return recursiveOwner.wireParent()")
					.endControlFlow();
			parent.addStatement(ownerIsRegionView ? "return owner.wireParent()" : "return owner.parent");
			view.addMethod(parent.build());
			MethodSpec.Builder state = MethodSpec.methodBuilder("wireState")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(planClassName.nestedClass("State"))
					.beginControlFlow("if (recursiveOwner != null)")
					.addStatement("return recursiveOwner.wireState()")
					.endControlFlow();
			state.addStatement(ownerIsRegionView ? "return owner.wireState()" : "return owner.state");
			view.addMethod(state.build())
					.addMethod(MethodSpec.methodBuilder("wireStart")
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PUBLIC)
							.returns(TypeName.INT)
							.beginControlFlow("if (recursiveOwner != null)")
							.addStatement("return recursiveStart")
							.endControlFlow()
							.addStatement("return $L", regionStart)
							.build())
					.addMethod(MethodSpec.methodBuilder("wireLength")
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PUBLIC)
							.returns(TypeName.INT)
							.beginControlFlow("if (recursiveOwner != null)")
							.addStatement("return recursiveLength")
							.endControlFlow()
							.addStatement("return $L", regionLength)
							.build())
					.addMethod(MethodSpec.methodBuilder("rebind")
							.addModifiers(Modifier.PRIVATE)
							.addParameter(ownerInterface, "recursiveOwner")
							.addParameter(TypeName.INT, "recursiveStart")
							.addParameter(TypeName.INT, "recursiveLength")
							.addStatement("clear()")
							.addStatement("this.recursiveOwner = recursiveOwner")
							.addStatement("this.recursiveStart = recursiveStart")
							.addStatement("this.recursiveLength = recursiveLength")
							.build());
		}

		private WireViewBinding generateRecordRegionWireView(ComputedTypeBase apiOwner,
				ComputedTypeBase viewedType,
				String interfaceName,
				ClassName frameType,
				CodeBlock regionStart,
				CodeBlock regionLength,
				WireViewBinding result) {
			String implementationName = result.implementationType().simpleName();
			String fieldName = result.fieldName();
			ClassName implementationType = result.implementationType();
			TypeName interfaceType = result.interfaceType();
			TypeSpec.Builder view = TypeSpec.classBuilder(implementationName)
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
					.addSuperinterface(interfaceType)
					.addField(TypeName.BOOLEAN, "scanned", Modifier.PRIVATE);
			addRegionViewInfrastructure(view, frameType, regionStart, regionLength);
			var fieldViews = new LinkedHashMap<String, WireViewBinding>();
			for (var field : viewedType.getData().entrySet()) {
				if (!isWireViewType(field.getValue())) continue;
				String prefix = "field" + capitalize(field.getKey());
				WireViewBinding binding = generateRegionWireView(apiOwner, field.getValue(),
						GenUpgraderBaseX.wireRecordFieldViewInterfaceName(interfaceName, field.getKey()),
						implementationType, CodeBlock.of("owner.$N", prefix + "Start"),
						CodeBlock.of("owner.$N", prefix + "Length"));
				fieldViews.put(field.getKey(), binding);
				boolean recursive = recursiveRegionWireViewFields.contains(binding.fieldName());
				var fieldBuilder = FieldSpec.builder(binding.implementationType(), binding.fieldName(), Modifier.PRIVATE);
				if (!recursive) {
					fieldBuilder.addModifiers(Modifier.FINAL).initializer("new $T(this)", binding.implementationType());
				}
				view.addField(fieldBuilder.build());
			}
			for (var field : viewedType.getData().entrySet()) {
				String prefix = "field" + capitalize(field.getKey());
				view.addField(TypeName.INT, prefix + "Start", Modifier.PRIVATE)
						.addField(TypeName.INT, prefix + "Length", Modifier.PRIVATE);
				if (!(field.getValue() instanceof ComputedTypeNative nativeType && nativeType.isPrimitive())) {
					view.addField(TypeName.BOOLEAN, prefix + "Set", Modifier.PRIVATE)
							.addField(field.getValue().getJTypeName(basePackageName), prefix + "Value",
									Modifier.PRIVATE);
				}
			}
			MethodSpec.Builder scan = MethodSpec.methodBuilder("ensureScanned")
					.addModifiers(Modifier.PRIVATE)
					.beginControlFlow("if (!scanned)")
					.addStatement("wireParent().bindRegion(scanCursor, wireStart(), wireLength())")
					.beginControlFlow("try");
			for (var field : viewedType.getData().entrySet()) {
				String prefix = "field" + capitalize(field.getKey());
				scan.addStatement("$N = wireStart() + scanCursor.position()", prefix + "Start")
						.addStatement("$N(scanCursor)", ensureSkipper(field.getValue()))
						.addStatement("$N = (wireStart() + scanCursor.position()) - $N", prefix + "Length",
								prefix + "Start");
			}
			scan.addStatement("int trailing = scanCursor.remainingIncludingClosed()")
					.beginControlFlow("if (trailing != 0)")
					.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
							"Trailing bytes in wire record view: ")
					.endControlFlow()
					.addStatement("scanned = true")
					.nextControlFlow("finally")
					.beginControlFlow("if (scanCursor.isBound())")
					.addStatement("scanCursor.unbind()")
					.endControlFlow()
					.endControlFlow()
					.endControlFlow();
			view.addMethod(scan.build());

			for (var field : viewedType.getData().entrySet()) {
				String prefix = "field" + capitalize(field.getKey());
				ComputedType fieldType = field.getValue();
				MethodSpec.Builder getter = MethodSpec.methodBuilder(field.getKey())
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(fieldType.getJTypeName(basePackageName))
						.addStatement("ensureScanned()");
				if (fieldType instanceof ComputedTypeNative nativeType && nativeType.isPrimitive()) {
					getter.addStatement("return wireParent().get$NAt($N)", capitalize(nativeType.getName()),
							prefix + "Start");
				} else {
					String reader = ensureReader(fieldType, fieldType);
					externallyRequiredReaders.add(reader);
					getter.beginControlFlow("if (!$N)", prefix + "Set")
							.addStatement("wireParent().bindRegion(valueCursor, $N, $N)", prefix + "Start",
									prefix + "Length")
							.beginControlFlow("try")
							.addStatement("$N = $N(valueCursor, wireState())", prefix + "Value", reader)
							.addStatement("int trailing = valueCursor.remainingIncludingClosed()")
							.beginControlFlow("if (trailing != 0)")
							.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
									"Trailing bytes in wire-view field: ")
							.endControlFlow()
							.addStatement("$N = true", prefix + "Set")
							.nextControlFlow("finally")
							.beginControlFlow("if (valueCursor.isBound())")
							.addStatement("valueCursor.unbind()")
							.endControlFlow()
							.endControlFlow()
							.endControlFlow()
							.addStatement("return $N", prefix + "Value");
				}
				view.addMethod(getter.build());
				WireViewBinding fieldView = fieldViews.get(field.getKey());
				if (fieldView != null) {
					MethodSpec.Builder fieldViewGetter = MethodSpec.methodBuilder(field.getKey() + "View")
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PUBLIC)
							.returns(fieldView.interfaceType())
							.addStatement("ensureScanned()");
					if (recursiveRegionWireViewFields.contains(fieldView.fieldName())) {
						fieldViewGetter.beginControlFlow("if ($N == null)", fieldView.fieldName())
								.addStatement("$N = new $T()", fieldView.fieldName(), fieldView.implementationType())
								.endControlFlow()
								.addStatement("$N.rebind(this, $N, $N)", fieldView.fieldName(), prefix + "Start",
										prefix + "Length");
					} else {
						fieldViewGetter.addStatement("$N.clear()", fieldView.fieldName());
					}
					fieldViewGetter.addStatement("return $N", fieldView.fieldName());
					view.addMethod(fieldViewGetter.build());
				}
			}

			MethodSpec.Builder clear = MethodSpec.methodBuilder("clear")
					.addModifiers(Modifier.PRIVATE)
					.beginControlFlow("try")
					.beginControlFlow("if (scanCursor.isBound())")
					.addStatement("scanCursor.unbind()")
					.endControlFlow()
					.beginControlFlow("if (valueCursor.isBound())")
					.addStatement("valueCursor.unbind()")
					.endControlFlow();
			for (WireViewBinding binding : fieldViews.values()) {
				if (recursiveRegionWireViewFields.contains(binding.fieldName())) {
					clear.beginControlFlow("if ($N != null)", binding.fieldName())
							.addStatement("$N.clear()", binding.fieldName())
							.endControlFlow();
				} else {
					clear.addStatement("$N.clear()", binding.fieldName());
				}
			}
			clear.nextControlFlow("finally")
					.addStatement("scanned = false");
			for (var field : viewedType.getData().entrySet()) {
				String prefix = "field" + capitalize(field.getKey());
				clear.addStatement("$N = 0", prefix + "Start")
						.addStatement("$N = 0", prefix + "Length");
				if (!(field.getValue() instanceof ComputedTypeNative nativeType && nativeType.isPrimitive())) {
					clear.addStatement("$N = false", prefix + "Set")
							.addStatement("$N = $L", prefix + "Value",
									defaultValue(field.getValue().getJTypeName(basePackageName)));
				}
			}
			clear.addStatement("recursiveOwner = null")
					.addStatement("recursiveStart = 0")
					.addStatement("recursiveLength = 0");
			clear.endControlFlow();
			view.addMethod(clear.build());
			classBuilder.addType(view.build());
			return result;
		}

		private WireViewBinding generateRegionWireView(ComputedTypeBase apiOwner,
				ComputedType viewedType,
				String interfaceName,
				ClassName frameType,
				CodeBlock regionStart,
				CodeBlock regionLength) {
			WireViewBinding active = activeRegionWireViews.get(viewedType);
			if (active != null) {
				String fieldName = "wireView" + nextWireViewId++;
				recursiveRegionWireViewFields.add(fieldName);
				return new WireViewBinding(fieldName, active.implementationType(), active.interfaceType());
			}
			int id = nextWireViewId++;
			var result = new WireViewBinding("wireView" + id, planClassName.nestedClass("WireView" + id),
					apiOwner.getJUpgraderName(basePackageName).nestedClass(interfaceName));
			activeRegionWireViews.put(viewedType, result);
			try {
				return switch (viewedType) {
					case ComputedTypeBase record -> generateRecordRegionWireView(apiOwner, record, interfaceName,
							frameType, regionStart, regionLength, result);
					case ComputedTypeArray array -> generateArrayRegionWireView(apiOwner, array, interfaceName,
							frameType, regionStart, regionLength, result);
					case ComputedTypeNullable nullable -> generateNullableRegionWireView(apiOwner, nullable,
							interfaceName, frameType, regionStart, regionLength, result);
					case ComputedTypeSuper union -> generateUnionRegionWireView(apiOwner, union, interfaceName,
							frameType, regionStart, regionLength, result);
					default -> throw generationError("wire view requested for scalar " + viewedType);
				};
			} finally {
				activeRegionWireViews.remove(viewedType);
			}
		}

		private WireViewBinding generateArrayRegionWireView(ComputedTypeBase apiOwner,
				ComputedTypeArray viewedType,
				String interfaceName,
				ClassName frameType,
				CodeBlock regionStart,
				CodeBlock regionLength,
				WireViewBinding result) {
			String implementationName = result.implementationType().simpleName();
			String fieldName = result.fieldName();
			ClassName implementationType = result.implementationType();
			TypeName interfaceType = result.interfaceType();
			ComputedType elementType = viewedType.getBase();
			Integer fixedSize = fixedSerializedSize(elementType);
			TypeName cursorType = apiOwner.getJUpgraderName(basePackageName).nestedClass(
					GenUpgraderBaseX.wireArrayCursorInterfaceName(interfaceName));
			TypeSpec.Builder view = TypeSpec.classBuilder(implementationName)
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
					.addSuperinterface(interfaceType)
					.addSuperinterface(cursorType)
					.addField(TypeName.BOOLEAN, "scanned", Modifier.PRIVATE)
					.addField(TypeName.INT, "size", Modifier.PRIVATE)
					.addField(TypeName.INT, "payloadStart", Modifier.PRIVATE)
					.addField(TypeName.INT, "cursorIndex", Modifier.PRIVATE);
			addRegionViewInfrastructure(view, frameType, regionStart, regionLength);
			WireViewBinding reusableElementView = null;
			if (isWireViewType(elementType)) {
				view.addField(TypeName.INT, "elementViewStart", Modifier.PRIVATE)
						.addField(TypeName.INT, "elementViewLength", Modifier.PRIVATE);
				reusableElementView = generateRegionWireView(apiOwner, elementType,
						GenUpgraderBaseX.wireElementViewInterfaceName(interfaceName), implementationType,
						CodeBlock.of("owner.elementViewStart"), CodeBlock.of("owner.elementViewLength"));
				boolean recursive = recursiveRegionWireViewFields.contains(reusableElementView.fieldName());
				var fieldBuilder = FieldSpec.builder(reusableElementView.implementationType(),
						reusableElementView.fieldName(), Modifier.PRIVATE);
				if (!recursive) {
					fieldBuilder.addModifiers(Modifier.FINAL)
							.initializer("new $T(this)", reusableElementView.implementationType());
				}
				view.addField(fieldBuilder.build());
			}
			if (fixedSize == null) {
				view.addField(FieldSpec.builder(int[].class, "elementStarts", Modifier.PRIVATE)
						.initializer("new int[0]").build())
						.addField(FieldSpec.builder(int[].class, "elementLengths", Modifier.PRIVATE)
								.initializer("new int[0]").build());
			}
			MethodSpec.Builder scan = MethodSpec.methodBuilder("ensureScanned")
					.addModifiers(Modifier.PRIVATE)
					.beginControlFlow("if (!scanned)")
					.addStatement("wireParent().bindRegion(scanCursor, wireStart(), wireLength())")
					.beginControlFlow("try")
					.addStatement("size = $T.readLength(scanCursor)", ProjectionReadSupport.class)
					.addStatement("$T.prepareArrayAllocation(scanCursor, size, $L)", ProjectionReadSupport.class,
							readPlanCompiler.minimumSerializedSize(elementType))
					.addStatement("payloadStart = wireStart() + scanCursor.position()");
			if (fixedSize != null) {
				scan.addStatement("scanCursor.skipExact($T.checkedArrayBytes(size, $L))",
						ProjectionReadSupport.class, fixedSize);
			} else {
				scan.beginControlFlow("if (elementStarts.length < size)")
						.addStatement("elementStarts = $T.copyOf(elementStarts, size)", java.util.Arrays.class)
						.addStatement("elementLengths = $T.copyOf(elementLengths, size)", java.util.Arrays.class)
						.endControlFlow()
						.beginControlFlow("for (int index = 0; index < size; index++)")
						.addStatement("elementStarts[index] = wireStart() + scanCursor.position()")
						.addStatement("$N(scanCursor)", ensureSkipper(elementType))
						.addStatement("elementLengths[index] = (wireStart() + scanCursor.position()) - elementStarts[index]")
						.endControlFlow();
			}
			scan.addStatement("int trailing = scanCursor.remainingIncludingClosed()")
					.beginControlFlow("if (trailing != 0)")
					.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
							"Trailing bytes in wire array view: ")
					.endControlFlow()
					.addStatement("scanned = true")
					.nextControlFlow("finally")
					.beginControlFlow("if (scanCursor.isBound())")
					.addStatement("scanCursor.unbind()")
					.endControlFlow()
					.endControlFlow()
					.endControlFlow();
			view.addMethod(scan.build());
			view.addMethod(MethodSpec.methodBuilder("size")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(TypeName.INT)
					.addStatement("ensureScanned()")
					.addStatement("return size")
					.build());
			MethodSpec.Builder get = MethodSpec.methodBuilder("get")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(elementType.getJTypeName(basePackageName))
					.addParameter(TypeName.INT, "index")
					.addStatement("ensureScanned()")
					.addStatement("$T.checkIndex(index, size)", Objects.class);
			if (fixedSize != null) {
				get.addStatement("int elementStart = payloadStart + $T.multiplyExact(index, $L)", Math.class,
						fixedSize)
						.addStatement("int elementLength = $L", fixedSize);
			} else {
				get.addStatement("int elementStart = elementStarts[index]")
						.addStatement("int elementLength = elementLengths[index]");
			}
			if (elementType instanceof ComputedTypeNative nativeType && nativeType.isPrimitive()) {
				get.addStatement("return wireParent().get$NAt(elementStart)", capitalize(nativeType.getName()));
			} else {
				String reader = ensureReader(elementType, elementType);
				externallyRequiredReaders.add(reader);
				get.addStatement("wireParent().bindRegion(valueCursor, elementStart, elementLength)")
						.beginControlFlow("try")
						.addStatement("$T result = $N(valueCursor, wireState())",
								elementType.getJTypeName(basePackageName), reader)
						.addStatement("int trailing = valueCursor.remainingIncludingClosed()")
						.beginControlFlow("if (trailing != 0)")
						.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
								"Trailing bytes in wire-array element: ")
						.endControlFlow()
						.addStatement("return result")
						.nextControlFlow("finally")
						.beginControlFlow("if (valueCursor.isBound())")
						.addStatement("valueCursor.unbind()")
						.endControlFlow()
						.endControlFlow();
			}
			view.addMethod(get.build());
			if (reusableElementView != null) {
				MethodSpec.Builder elementView = MethodSpec.methodBuilder("elementView")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(reusableElementView.interfaceType())
						.addParameter(TypeName.INT, "index")
						.addStatement("ensureScanned()")
						.addStatement("$T.checkIndex(index, size)", Objects.class);
				if (fixedSize != null) {
					elementView.addStatement("elementViewStart = payloadStart + $T.multiplyExact(index, $L)",
							Math.class, fixedSize)
							.addStatement("elementViewLength = $L", fixedSize);
				} else {
					elementView.addStatement("elementViewStart = elementStarts[index]")
							.addStatement("elementViewLength = elementLengths[index]");
				}
				if (recursiveRegionWireViewFields.contains(reusableElementView.fieldName())) {
					elementView.beginControlFlow("if ($N == null)", reusableElementView.fieldName())
							.addStatement("$N = new $T()", reusableElementView.fieldName(),
									reusableElementView.implementationType())
							.endControlFlow()
							.addStatement("$N.rebind(this, elementViewStart, elementViewLength)",
									reusableElementView.fieldName());
				} else {
					elementView.addStatement("$N.clear()", reusableElementView.fieldName());
				}
				elementView.addStatement("return $N", reusableElementView.fieldName());
				view.addMethod(elementView.build());
			}
			view.addMethod(MethodSpec.methodBuilder("copy")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(viewedType.getJTypeName(basePackageName))
					.addStatement("ensureScanned()")
					.beginControlFlow("if (size == 0)")
					.addStatement("return $T.emptyArray()", viewedType.getJSerializerName(basePackageName))
					.endControlFlow()
					.addStatement("$T result = new $T[size]", viewedType.getJTypeName(basePackageName),
							elementType.getJTypeName(basePackageName))
					.beginControlFlow("for (int index = 0; index < size; index++)")
					.addStatement("result[index] = get(index)")
					.endControlFlow()
					.addStatement("return result")
					.build());
			view.addMethod(MethodSpec.methodBuilder("cursor")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(cursorType)
					.addStatement("ensureScanned()")
					.addStatement("cursorIndex = 0")
					.addStatement("return this")
					.build())
				.addMethod(MethodSpec.methodBuilder("hasNext")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(TypeName.BOOLEAN)
						.addStatement("ensureScanned()")
						.addStatement("return cursorIndex < size")
						.build())
				.addMethod(MethodSpec.methodBuilder("next")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(elementType.getJTypeName(basePackageName))
						.beginControlFlow("if (!hasNext())")
						.addStatement("throw new $T()", java.util.NoSuchElementException.class)
						.endControlFlow()
						.addStatement("return get(cursorIndex++)")
						.build());
			if (reusableElementView != null) {
				view.addMethod(MethodSpec.methodBuilder("nextView")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(reusableElementView.interfaceType())
						.beginControlFlow("if (!hasNext())")
						.addStatement("throw new $T()", java.util.NoSuchElementException.class)
						.endControlFlow()
						.addStatement("return elementView(cursorIndex++)")
						.build());
			}
			MethodSpec.Builder clear = MethodSpec.methodBuilder("clear")
					.addModifiers(Modifier.PRIVATE)
					.beginControlFlow("try")
					.beginControlFlow("if (scanCursor.isBound())")
					.addStatement("scanCursor.unbind()")
					.endControlFlow()
					.beginControlFlow("if (valueCursor.isBound())")
					.addStatement("valueCursor.unbind()")
					.endControlFlow();
			if (reusableElementView != null) {
				if (recursiveRegionWireViewFields.contains(reusableElementView.fieldName())) {
					clear.beginControlFlow("if ($N != null)", reusableElementView.fieldName())
							.addStatement("$N.clear()", reusableElementView.fieldName())
							.endControlFlow();
				} else {
					clear.addStatement("$N.clear()", reusableElementView.fieldName());
				}
			}
			clear.nextControlFlow("finally")
					.addStatement("scanned = false")
					.addStatement("size = 0")
					.addStatement("payloadStart = 0")
					.addStatement("cursorIndex = 0");
			if (reusableElementView != null) {
				clear.addStatement("elementViewStart = 0")
						.addStatement("elementViewLength = 0");
			}
			clear.addStatement("recursiveOwner = null")
					.addStatement("recursiveStart = 0")
					.addStatement("recursiveLength = 0");
			clear.endControlFlow();
			view.addMethod(clear.build());
			classBuilder.addType(view.build());
			return result;
		}

		private WireViewBinding generateNullableRegionWireView(ComputedTypeBase apiOwner,
				ComputedTypeNullable viewedType,
				String interfaceName,
				ClassName frameType,
				CodeBlock regionStart,
				CodeBlock regionLength,
				WireViewBinding result) {
			String implementationName = result.implementationType().simpleName();
			String fieldName = result.fieldName();
			ClassName implementationType = result.implementationType();
			TypeName interfaceType = result.interfaceType();
			ComputedType valueType = viewedType.getBase();
			boolean primitive = valueType instanceof ComputedTypeNative nativeType && nativeType.isPrimitive();
			TypeSpec.Builder view = TypeSpec.classBuilder(implementationName)
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
					.addSuperinterface(interfaceType)
					.addField(TypeName.BOOLEAN, "scanned", Modifier.PRIVATE)
					.addField(TypeName.BOOLEAN, "present", Modifier.PRIVATE)
					.addField(TypeName.INT, "valueStart", Modifier.PRIVATE)
					.addField(TypeName.INT, "valueLength", Modifier.PRIVATE);
			addRegionViewInfrastructure(view, frameType, regionStart, regionLength);
			if (!primitive) {
				view.addField(TypeName.BOOLEAN, "valueSet", Modifier.PRIVATE)
						.addField(valueType.getJTypeName(basePackageName), "cachedValue", Modifier.PRIVATE);
			}
			WireViewBinding reusableValueView = null;
			if (isWireViewType(valueType)) {
				reusableValueView = generateRegionWireView(apiOwner, valueType,
						GenUpgraderBaseX.wireNullableValueViewInterfaceName(interfaceName), implementationType,
						CodeBlock.of("owner.valueStart"), CodeBlock.of("owner.valueLength"));
				boolean recursive = recursiveRegionWireViewFields.contains(reusableValueView.fieldName());
				var fieldBuilder = FieldSpec.builder(reusableValueView.implementationType(),
						reusableValueView.fieldName(), Modifier.PRIVATE);
				if (!recursive) {
					fieldBuilder.addModifiers(Modifier.FINAL)
							.initializer("new $T(this)", reusableValueView.implementationType());
				}
				view.addField(fieldBuilder.build());
			}
			MethodSpec.Builder ensureScanned = MethodSpec.methodBuilder("ensureScanned")
					.addModifiers(Modifier.PRIVATE)
					.beginControlFlow("if (!scanned)")
					.addStatement("wireParent().bindRegion(scanCursor, wireStart(), wireLength())")
					.beginControlFlow("try");
			NullableWireEmitter.emitValueRegion(ensureScanned, viewedType, "scanCursor",
					CodeBlock.of("wireStart()"), "valueStart", "valueLength", "present", "first",
					CodeBlock.of("$N(scanCursor)", ensureSkipper(valueType)));
			ensureScanned
					.addStatement("int trailing = scanCursor.remainingIncludingClosed()")
					.beginControlFlow("if (trailing != 0)")
					.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
							"Trailing bytes in wire nullable view: ")
					.endControlFlow()
					.addStatement("scanned = true")
					.nextControlFlow("finally")
					.beginControlFlow("if (scanCursor.isBound())")
					.addStatement("scanCursor.unbind()")
					.endControlFlow()
					.endControlFlow()
					.endControlFlow();
			view.addMethod(ensureScanned.build());
			view.addMethod(MethodSpec.methodBuilder("isPresent")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(TypeName.BOOLEAN)
					.addStatement("ensureScanned()")
					.addStatement("return present")
					.build());
			MethodSpec.Builder value = MethodSpec.methodBuilder("value")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(valueType.getJTypeName(basePackageName))
					.addStatement("ensureScanned()")
					.beginControlFlow("if (!present)")
					.addStatement("throw new $T($S)", java.util.NoSuchElementException.class,
							"Nullable wire value is empty")
					.endControlFlow();
			if (primitive) {
				ComputedTypeNative nativeType = (ComputedTypeNative) valueType;
				value.addStatement("return wireParent().get$NAt(valueStart)", capitalize(nativeType.getName()));
			} else {
				CodeBlock decodedValue;
				if (WireLayout.of(viewedType) == WireLayout.BOOLEAN_TAGGED_SHORT_STRING) {
					decodedValue = NullableWireEmitter.valueExpression(viewedType, binaryStrings,
							CodeBlock.of("valueCursor"), "first", readExact(valueType));
				} else {
					String reader = ensureReader(valueType, valueType);
					externallyRequiredReaders.add(reader);
					decodedValue = CodeBlock.of("$N(valueCursor, wireState())", reader);
				}
				value.beginControlFlow("if (!valueSet)")
						.addStatement("wireParent().bindRegion(valueCursor, valueStart, valueLength)")
						.beginControlFlow("try")
						.addStatement("cachedValue = ($T) $L", valueType.getJTypeName(basePackageName),
								decodedValue)
						.addStatement("int trailing = valueCursor.remainingIncludingClosed()")
						.beginControlFlow("if (trailing != 0)")
						.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
								"Trailing bytes in nullable wire value: ")
						.endControlFlow()
						.addStatement("valueSet = true")
						.nextControlFlow("finally")
						.beginControlFlow("if (valueCursor.isBound())")
						.addStatement("valueCursor.unbind()")
						.endControlFlow()
						.endControlFlow()
						.endControlFlow()
						.addStatement("return cachedValue");
			}
			view.addMethod(value.build());
			if (reusableValueView != null) {
				MethodSpec.Builder valueView = MethodSpec.methodBuilder("valueView")
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(reusableValueView.interfaceType())
						.addStatement("ensureScanned()")
						.beginControlFlow("if (!present)")
						.addStatement("throw new $T($S)", java.util.NoSuchElementException.class,
								"Nullable wire value is empty")
						.endControlFlow();
				if (recursiveRegionWireViewFields.contains(reusableValueView.fieldName())) {
					valueView.beginControlFlow("if ($N == null)", reusableValueView.fieldName())
							.addStatement("$N = new $T()", reusableValueView.fieldName(),
									reusableValueView.implementationType())
							.endControlFlow()
							.addStatement("$N.rebind(this, valueStart, valueLength)", reusableValueView.fieldName());
				} else {
					valueView.addStatement("$N.clear()", reusableValueView.fieldName());
				}
				valueView.addStatement("return $N", reusableValueView.fieldName());
				view.addMethod(valueView.build());
			}
			MethodSpec.Builder clear = MethodSpec.methodBuilder("clear")
					.addModifiers(Modifier.PRIVATE)
					.beginControlFlow("try")
					.beginControlFlow("if (scanCursor.isBound())")
					.addStatement("scanCursor.unbind()")
					.endControlFlow()
					.beginControlFlow("if (valueCursor.isBound())")
					.addStatement("valueCursor.unbind()")
					.endControlFlow();
			if (reusableValueView != null) {
				if (recursiveRegionWireViewFields.contains(reusableValueView.fieldName())) {
					clear.beginControlFlow("if ($N != null)", reusableValueView.fieldName())
							.addStatement("$N.clear()", reusableValueView.fieldName())
							.endControlFlow();
				} else {
					clear.addStatement("$N.clear()", reusableValueView.fieldName());
				}
			}
			clear.nextControlFlow("finally")
					.addStatement("scanned = false")
					.addStatement("present = false")
					.addStatement("valueStart = 0")
					.addStatement("valueLength = 0");
			if (!primitive) {
				clear.addStatement("valueSet = false")
						.addStatement("cachedValue = null");
			}
			clear.addStatement("recursiveOwner = null")
					.addStatement("recursiveStart = 0")
					.addStatement("recursiveLength = 0");
			clear.endControlFlow();
			view.addMethod(clear.build());
			classBuilder.addType(view.build());
			return result;
		}

		private WireViewBinding generateUnionRegionWireView(ComputedTypeBase apiOwner,
				ComputedTypeSuper viewedType,
				String interfaceName,
				ClassName frameType,
				CodeBlock regionStart,
				CodeBlock regionLength,
				WireViewBinding result) {
			String implementationName = result.implementationType().simpleName();
			String fieldName = result.fieldName();
			ClassName implementationType = result.implementationType();
			TypeName interfaceType = result.interfaceType();
			TypeName kindType = apiOwner.getJUpgraderName(basePackageName).nestedClass(interfaceName + "Kind");
			TypeSpec.Builder view = TypeSpec.classBuilder(implementationName)
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
					.addSuperinterface(interfaceType)
					.addField(TypeName.BOOLEAN, "scanned", Modifier.PRIVATE)
					.addField(TypeName.INT, "kindId", Modifier.PRIVATE)
					.addField(TypeName.INT, "valueStart", Modifier.PRIVATE)
					.addField(TypeName.INT, "valueLength", Modifier.PRIVATE)
					.addField(TypeName.BOOLEAN, "valueSet", Modifier.PRIVATE)
					.addField(viewedType.getJTypeName(basePackageName), "cachedValue", Modifier.PRIVATE);
			addRegionViewInfrastructure(view, frameType, regionStart, regionLength);
			var subtypeViews = new LinkedHashMap<Integer, WireViewBinding>();
			for (int index = 0; index < viewedType.subTypes().size(); index++) {
				ComputedType subtype = viewedType.subTypes().get(index);
				if (!isWireViewType(subtype)) continue;
				WireViewBinding binding = generateRegionWireView(apiOwner, subtype,
						GenUpgraderBaseX.wireUnionSubtypeViewInterfaceName(interfaceName, subtype.getName()),
						implementationType, CodeBlock.of("owner.valueStart"), CodeBlock.of("owner.valueLength"));
				subtypeViews.put(index, binding);
				boolean recursive = recursiveRegionWireViewFields.contains(binding.fieldName());
				var fieldBuilder = FieldSpec.builder(binding.implementationType(), binding.fieldName(), Modifier.PRIVATE);
				if (!recursive) {
					fieldBuilder.addModifiers(Modifier.FINAL).initializer("new $T(this)", binding.implementationType());
				}
				view.addField(fieldBuilder.build());
			}
			MethodSpec.Builder scan = MethodSpec.methodBuilder("ensureScanned")
					.addModifiers(Modifier.PRIVATE)
					.beginControlFlow("if (!scanned)")
					.addStatement("wireParent().bindRegion(scanCursor, wireStart(), wireLength())")
					.beginControlFlow("try")
					.addStatement("kindId = scanCursor.readUnsignedByte()")
					.beginControlFlow("if (kindId >= $L)", viewedType.subTypes().size())
					.addStatement("throw new $T($S + kindId)", MalformedDataException.class,
							"Invalid wire union discriminator: ")
					.endControlFlow()
					.addStatement("valueStart = wireStart() + scanCursor.position()")
					.beginControlFlow("switch (kindId)");
			for (int index = 0; index < viewedType.subTypes().size(); index++) {
				scan.addStatement("case $L -> $N(scanCursor)", index,
						ensureSkipper(viewedType.subTypes().get(index)));
			}
			scan.addStatement("default -> throw new $T(kindId)", AssertionError.class)
					.endControlFlow()
					.addStatement("valueLength = (wireStart() + scanCursor.position()) - valueStart")
					.addStatement("int trailing = scanCursor.remainingIncludingClosed()")
					.beginControlFlow("if (trailing != 0)")
					.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
							"Trailing bytes in wire union view: ")
					.endControlFlow()
					.addStatement("scanned = true")
					.nextControlFlow("finally")
					.beginControlFlow("if (scanCursor.isBound())")
					.addStatement("scanCursor.unbind()")
					.endControlFlow()
					.endControlFlow()
					.endControlFlow();
			view.addMethod(scan.build());
			MethodSpec.Builder kind = MethodSpec.methodBuilder("kind")
					.addAnnotation(Override.class)
					.addModifiers(Modifier.PUBLIC)
					.returns(kindType)
					.addStatement("ensureScanned()")
					.beginControlFlow("return switch (kindId)");
			for (int index = 0; index < viewedType.subTypes().size(); index++) {
				kind.addStatement("case $L -> $T.$N", index, kindType, viewedType.subTypes().get(index).getName());
			}
			kind.addStatement("default -> throw new $T()", AssertionError.class).addCode("$<};\n");
			view.addMethod(kind.build());
			for (int index = 0; index < viewedType.subTypes().size(); index++) {
				ComputedType subtype = viewedType.subTypes().get(index);
				String reader = ensureReader(subtype, subtype);
				externallyRequiredReaders.add(reader);
				MethodSpec.Builder getter = MethodSpec.methodBuilder("as" + capitalize(subtype.getName()))
						.addAnnotation(Override.class)
						.addModifiers(Modifier.PUBLIC)
						.returns(subtype.getJTypeName(basePackageName))
						.addStatement("ensureScanned()")
						.beginControlFlow("if (kindId != $L)", index)
						.addStatement("throw new $T($S + kind())", IllegalStateException.class,
								"Wire union is not " + subtype.getName() + ": ")
						.endControlFlow()
						.beginControlFlow("if (!valueSet)")
						.addStatement("wireParent().bindRegion(valueCursor, valueStart, valueLength)")
						.beginControlFlow("try")
						.addStatement("cachedValue = $N(valueCursor, wireState())", reader)
						.addStatement("int trailing = valueCursor.remainingIncludingClosed()")
						.beginControlFlow("if (trailing != 0)")
						.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
								"Trailing bytes in wire union value: ")
						.endControlFlow()
						.addStatement("valueSet = true")
						.nextControlFlow("finally")
						.beginControlFlow("if (valueCursor.isBound())")
						.addStatement("valueCursor.unbind()")
						.endControlFlow()
						.endControlFlow()
						.endControlFlow()
						.addStatement("return ($T) cachedValue", subtype.getJTypeName(basePackageName));
				view.addMethod(getter.build());
				WireViewBinding subtypeView = subtypeViews.get(index);
				if (subtypeView != null) {
					MethodSpec.Builder subtypeViewGetter = MethodSpec.methodBuilder(
							"as" + capitalize(subtype.getName()) + "View")
							.addAnnotation(Override.class)
							.addModifiers(Modifier.PUBLIC)
							.returns(subtypeView.interfaceType())
							.addStatement("ensureScanned()")
							.beginControlFlow("if (kindId != $L)", index)
							.addStatement("throw new $T($S + kind())", IllegalStateException.class,
									"Wire union is not " + subtype.getName() + ": ")
							.endControlFlow();
					if (recursiveRegionWireViewFields.contains(subtypeView.fieldName())) {
						subtypeViewGetter.beginControlFlow("if ($N == null)", subtypeView.fieldName())
								.addStatement("$N = new $T()", subtypeView.fieldName(),
										subtypeView.implementationType())
								.endControlFlow()
								.addStatement("$N.rebind(this, valueStart, valueLength)", subtypeView.fieldName());
					} else {
						subtypeViewGetter.addStatement("$N.clear()", subtypeView.fieldName());
					}
					subtypeViewGetter.addStatement("return $N", subtypeView.fieldName());
					view.addMethod(subtypeViewGetter.build());
				}
			}
			MethodSpec.Builder clear = MethodSpec.methodBuilder("clear")
					.addModifiers(Modifier.PRIVATE)
					.beginControlFlow("try")
					.beginControlFlow("if (scanCursor.isBound())")
					.addStatement("scanCursor.unbind()")
					.endControlFlow()
					.beginControlFlow("if (valueCursor.isBound())")
					.addStatement("valueCursor.unbind()")
					.endControlFlow();
			for (WireViewBinding binding : subtypeViews.values()) {
				if (recursiveRegionWireViewFields.contains(binding.fieldName())) {
					clear.beginControlFlow("if ($N != null)", binding.fieldName())
							.addStatement("$N.clear()", binding.fieldName())
							.endControlFlow();
				} else {
					clear.addStatement("$N.clear()", binding.fieldName());
				}
			}
			clear.nextControlFlow("finally")
					.addStatement("scanned = false")
					.addStatement("kindId = 0")
					.addStatement("valueStart = 0")
					.addStatement("valueLength = 0")
					.addStatement("valueSet = false")
					.addStatement("cachedValue = null");
			clear.addStatement("recursiveOwner = null")
					.addStatement("recursiveStart = 0")
					.addStatement("recursiveLength = 0");
			clear.endControlFlow();
			view.addMethod(clear.build());
			classBuilder.addType(view.build());
			return result;
		}

		private boolean isWireViewType(ComputedType type) {
			return type instanceof ComputedTypeBase || type instanceof ComputedTypeArray
					|| type instanceof ComputedTypeNullable || type instanceof ComputedTypeSuper;
		}

		private CodeBlock wireViewField(CodeBlock ownerValue,
				ComputedTypeBase ownerType,
				String fieldName,
				ComputedType fieldType) {
			if (fieldType instanceof ComputedTypeArray) {
				return CodeBlock.of("(($T) $L).$NUnsafeArray()", ownerType.getJTypeName(basePackageName),
						ownerValue, fieldName);
			}
			if (fieldType instanceof ComputedTypeNullable) {
				return CodeBlock.of("(($T) $L).has$N() ? $T.of((($T) $L).$N()) : $T.empty()",
						ownerType.getJTypeName(basePackageName), ownerValue, capitalize(fieldName),
						fieldType.getJTypeName(basePackageName), ownerType.getJTypeName(basePackageName),
						ownerValue, fieldName, fieldType.getJTypeName(basePackageName));
			}
			return CodeBlock.of("(($T) $L).$N()", ownerType.getJTypeName(basePackageName), ownerValue,
					fieldName);
		}

		private CodeBlock defaultValue(TypeName type) {
			return type.equals(TypeName.BOOLEAN) ? CodeBlock.of("false")
					: type.isPrimitive() ? CodeBlock.of("0") : CodeBlock.of("null");
		}

		private TypeName contextType(ComputedTypeBase owner, String fieldName, List<String> parameters) {
			return parameters.isEmpty() ? ClassName.get(DataContextNone.class)
					: owner.getJUpgraderName(basePackageName).nestedClass("Context" + capitalize(fieldName));
		}

		private FieldSpec interfaceField(TypeName type, String name, JInterfaceLocation location) {
			var field = FieldSpec.builder(type, name, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
			switch (location) {
				case JInterfaceLocationClassName className -> field.initializer("new $T()", className.className());
				case JInterfaceLocationInstanceField instance -> field.initializer("$T.$N",
						instance.fieldLocation().className(), instance.fieldLocation().fieldName());
			}
			return field.build();
		}

		private FieldOrigin traceFieldOrigin(int targetVersion, String ownerName, String targetField) {
			var origin = readPlanCompiler.traceFieldOrigin(targetVersion, ownerName, targetField);
			return new FieldOrigin(origin.previousName(), origin.initializer(), origin.upgrades());
		}

		private ComputedTypeBase requireBase(int logicalVersion, String name) {
			return readPlanCompiler.requireBase(logicalVersion, name);
		}

		private ComputedType typeNamed(int logicalVersion, String name) {
			return readPlanCompiler.typeNamed(logicalVersion, name);
		}

		private IllegalArgumentException generationError(String message) {
			return new IllegalArgumentException("Read plan " + currentType.getName() + ": " + message);
		}
	}

	private static String capitalize(String name) {
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	private record VersionDispatch(int version, String method, ReadPlanCompiler.Plan plan) {}

	private enum StorageKernel {
		GENERIC("", ClassName.get(SafeDataInput.class), false),
		HEAP("Heap", ClassName.get(HeapBufDataCursor.class), true),
		MEMORY_SEGMENT("MemorySegment", ClassName.get(MemorySegmentBufDataCursor.class), true),
		FALLBACK("Fallback", ClassName.get(FallbackBufDataCursor.class), true);

		private static final List<StorageKernel> SPECIALIZED = List.of(HEAP, MEMORY_SEGMENT, FALLBACK);

		private final String suffix;
		private final TypeName inputType;
		private final boolean randomAccess;

		StorageKernel(String suffix, TypeName inputType, boolean randomAccess) {
			this.suffix = suffix;
			this.inputType = inputType;
			this.randomAccess = randomAccess;
		}

		private String method(String baseMethod) {
			return baseMethod + suffix;
		}

		private TypeName inputType() {
			return inputType;
		}

		private boolean randomAccess() {
			return randomAccess;
		}

		private static List<StorageKernel> specialized() {
			return SPECIALIZED;
		}
	}

	private record ReaderKey(ComputedType input, ComputedType target) {}

	private record FieldReaderKey(ComputedTypeBase input,
		ComputedTypeBase targetOwner,
		String targetField,
		ComputedType resultType) {}

	private record ObjectMapperKey(ComputedType input, ComputedType target) {}

	private record ResolveKey(int version, String field) {}

	private record WireResolveKey(int version, String field, String outputField, boolean allowTerminal) {}

	private record FieldOrigin(String previousName,
		NewDataConfiguration initializer,
		List<UpgradeDataConfiguration> upgrades) {}

	private sealed interface PreparedComputation permits PreparedValue, PreparedTransform, PreparedNullableTransform {

		void emit(MethodSpec.Builder method, String basePackageName);
	}

	private record PreparedValue(String name, ComputedType type, CodeBlock expression)
			implements PreparedComputation {

		@Override
		public void emit(MethodSpec.Builder method, String basePackageName) {
			method.addStatement("final $T $N = $L", type.getJTypeName(basePackageName), name, expression);
		}
	}

	private record PreparedTransform(String name, ComputedType type, TransformExpression expression)
			implements PreparedComputation {

		@Override
		public void emit(MethodSpec.Builder method, String basePackageName) {
			CodeBlock result = expression.emit(method, basePackageName);
			method.addStatement("final $T $N = ($T) $L", type.getJTypeName(basePackageName), name,
					type.getJTypeName(basePackageName), result);
		}
	}

	private record PreparedNullableTransform(String presentName,
			String valueName,
			ComputedTypeNullable type,
			WireNullableMapTransformExpression expression) implements PreparedComputation {

		@Override
		public void emit(MethodSpec.Builder method, String basePackageName) {
			expression.emitFlattened(method, basePackageName, presentName, valueName);
		}
	}

	private record FlattenedNullableValue(String presentName,
			String valueName,
			ComputedTypeNullable type) {}

	private interface WireElementReadPlan {

		void emit(MethodSpec.Builder method, String basePackageName);
	}

	private sealed interface TransformExpression permits ImmediateTransformExpression, CallTransformExpression,
			ArrayMapTransformExpression, WireArrayMapTransformExpression, NullableMapTransformExpression,
			WireNullableMapTransformExpression {

		ComputedType type();

		CodeBlock emit(MethodSpec.Builder method, String basePackageName);
	}

	private record ImmediateTransformExpression(ComputedType type, CodeBlock code) implements TransformExpression {

		@Override
		public CodeBlock emit(MethodSpec.Builder method, String basePackageName) {
			return code;
		}
	}

	private enum TransformCallKind {
		STATIC,
		CONSTRUCTOR
	}

	private record CallTransformExpression(ComputedType type,
			TransformCallKind kind,
			ClassName owner,
			String methodName,
			List<TransformExpression> arguments) implements TransformExpression {

		@Override
		public CodeBlock emit(MethodSpec.Builder method, String basePackageName) {
			var emittedArguments = CodeBlock.builder();
			for (int index = 0; index < arguments.size(); index++) {
				if (index != 0) emittedArguments.add(", ");
				emittedArguments.add("$L", arguments.get(index).emit(method, basePackageName));
			}
			return switch (kind) {
				case STATIC -> CodeBlock.of("$T.$N($L)", owner, methodName, emittedArguments.build());
				case CONSTRUCTOR -> CodeBlock.of("new $T($L)", owner, emittedArguments.build());
			};
		}
	}

	private record ArrayMapTransformExpression(ComputedTypeArray type,
			ComputedTypeArray sourceType,
			TransformExpression source,
			TransformExpression elementTransform,
			String sourceName,
			String targetName,
			String indexName,
			ClassName targetCodec) implements TransformExpression {

		@Override
		public CodeBlock emit(MethodSpec.Builder method, String basePackageName) {
			TypeName sourceJavaType = sourceType.getJTypeName(basePackageName);
			TypeName targetJavaType = type.getJTypeName(basePackageName);
			TypeName targetElementType = type.getBase().getJTypeName(basePackageName);
			method.addStatement("final $T $N = ($T) $L", sourceJavaType, sourceName, sourceJavaType,
					source.emit(method, basePackageName));
			method.addStatement("final $T $N", targetJavaType, targetName);
			method.beginControlFlow("if ($N.length == 0)", sourceName)
					.addStatement("$N = $T.emptyArray()", targetName, targetCodec)
					.nextControlFlow("else")
					.addStatement("$N = new $T[$N.length]", targetName, targetElementType, sourceName)
					.beginControlFlow("for (int $N = 0; $N < $N.length; $N++)",
							indexName, indexName, sourceName, indexName);
			CodeBlock element = elementTransform.emit(method, basePackageName);
			method.addStatement("$N[$N] = ($T) $L", targetName, indexName, targetElementType, element)
					.endControlFlow()
					.endControlFlow();
			return CodeBlock.of("$N", targetName);
		}
	}

	private record WireArrayMapTransformExpression(ComputedTypeArray type,
			ComputedTypeArray sourceType,
			TransformExpression elementTransform,
			String cursorName,
			String stateCursorName,
			String targetName,
			String indexName,
			String elementName,
			CodeBlock readElement,
			WireElementReadPlan wireElementReadPlan,
			TypeName cursorType,
			CodeBlock sourceStart,
			CodeBlock sourceLength,
			ClassName targetCodec,
			Integer fixedElementSize,
			int minimumElementSize,
			String elementSkipper) implements TransformExpression {

		@Override
		public CodeBlock emit(MethodSpec.Builder method, String basePackageName) {
			TypeName targetJavaType = type.getJTypeName(basePackageName);
			TypeName sourceElementType = sourceType.getBase().getJTypeName(basePackageName);
			TypeName targetElementType = type.getBase().getJTypeName(basePackageName);
			String sizeName = targetName + "Size";
			String payloadName = targetName + "PayloadStart";
			method.addStatement("final $T $N = state.$N", cursorType, cursorName, stateCursorName)
					.addStatement("final $T $N", targetJavaType, targetName)
					.addStatement("randomInput.bindRegion($N, $L, $L)", cursorName, sourceStart, sourceLength)
					.beginControlFlow("try")
					.addStatement("final int $N = $T.readLength($N)", sizeName, ProjectionReadSupport.class,
							cursorName)
					.addStatement("$T.prepareArrayAllocation($N, $N, $L)", ProjectionReadSupport.class,
							cursorName, sizeName, minimumElementSize)
					.addStatement("final int $N = $N.position()", payloadName, cursorName);
			if (fixedElementSize != null) {
				method.addStatement("$N.skipExact($T.checkedArrayBytes($N, $L))", cursorName,
						ProjectionReadSupport.class, sizeName, fixedElementSize);
			} else {
				method.beginControlFlow("for (int check = 0; check < $N; check++)", sizeName)
						.addStatement("$N($N)", elementSkipper, cursorName)
						.endControlFlow();
			}
			method.addStatement("int validationTrailing = $N.remainingIncludingClosed()", cursorName)
					.beginControlFlow("if (validationTrailing != 0)")
					.addStatement("throw new $T($S + validationTrailing)", MalformedDataException.class,
							"Trailing bytes in mapped wire array: ")
					.endControlFlow()
					.addStatement("$N.position($N)", cursorName, payloadName)
					.beginControlFlow("if ($N == 0)", sizeName)
					.addStatement("$N = $T.emptyArray()", targetName, targetCodec)
					.nextControlFlow("else")
					.addStatement("$N = new $T[$N]", targetName, targetElementType, sizeName)
					.beginControlFlow("for (int $N = 0; $N < $N; $N++)", indexName, indexName,
							sizeName, indexName);
			if (wireElementReadPlan != null) {
				wireElementReadPlan.emit(method, basePackageName);
			} else {
				method.addStatement("final $T $N = ($T) $L", sourceElementType, elementName,
						sourceElementType, readElement);
			}
			CodeBlock element = elementTransform.emit(method, basePackageName);
			method.addStatement("$N[$N] = ($T) $L", targetName, indexName, targetElementType, element)
					.endControlFlow()
					.endControlFlow()
					.addStatement("int trailing = $N.remainingIncludingClosed()", cursorName)
					.beginControlFlow("if (trailing != 0)")
					.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
							"Trailing bytes after mapped wire array: ")
					.endControlFlow()
					.nextControlFlow("finally")
					.beginControlFlow("if ($N.isBound())", cursorName)
					.addStatement("$N.unbind()", cursorName)
					.endControlFlow()
					.endControlFlow();
			return CodeBlock.of("$N", targetName);
		}
	}

	private record NullableMapTransformExpression(ComputedTypeNullable type,
			ComputedTypeNullable sourceType,
			TransformExpression source,
			TransformExpression elementTransform,
			String sourceName,
			String targetName) implements TransformExpression {

		@Override
		public CodeBlock emit(MethodSpec.Builder method, String basePackageName) {
			TypeName sourceJavaType = sourceType.getJTypeName(basePackageName);
			TypeName targetJavaType = type.getJTypeName(basePackageName);
			TypeName targetElementType = type.getBase().getJTypeName(basePackageName);
			method.addStatement("final $T $N = ($T) $L", sourceJavaType, sourceName, sourceJavaType,
					source.emit(method, basePackageName));
			method.addStatement("final $T $N", targetJavaType, targetName);
			method.beginControlFlow("if ($N.getNullable() == null)", sourceName)
					.addStatement("$N = $T.empty()", targetName, targetJavaType)
					.nextControlFlow("else");
			CodeBlock element = elementTransform.emit(method, basePackageName);
			method.addStatement("$N = $T.of(($T) $L)", targetName, targetJavaType, targetElementType, element)
					.endControlFlow();
			return CodeBlock.of("$N", targetName);
		}
	}

	private record WireNullableMapTransformExpression(ComputedTypeNullable type,
			ComputedTypeNullable sourceType,
			TransformExpression elementTransform,
			String cursorName,
			String stateCursorName,
			String targetName,
			String elementName,
			CodeBlock readElement,
			WireElementReadPlan wireElementReadPlan,
			boolean binaryStrings,
			TypeName cursorType,
			CodeBlock sourceStart,
			CodeBlock sourceLength) implements TransformExpression {

		@Override
		public CodeBlock emit(MethodSpec.Builder method, String basePackageName) {
			String presentName = targetName + "Present";
			String valueName = targetName + "Value";
			emitFlattened(method, basePackageName, presentName, valueName);
			TypeName targetType = type.getJTypeName(basePackageName);
			method.addStatement("final $T $N = $N ? $T.of($N) : $T.empty()", targetType, targetName,
					presentName, targetType, valueName, targetType);
			return CodeBlock.of("$N", targetName);
		}

		private void emitFlattened(MethodSpec.Builder method,
				String basePackageName,
				String presentName,
				String valueName) {
			TypeName sourceElementType = sourceType.getBase().getJTypeName(basePackageName);
			TypeName targetElementType = type.getBase().getJTypeName(basePackageName);
			CodeBlock absentValue = targetElementType.equals(TypeName.BOOLEAN) ? CodeBlock.of("false")
					: targetElementType.isPrimitive() ? CodeBlock.of("0") : CodeBlock.of("null");
			String firstName = targetName + "First";
			String sourcePresentName = targetName + "SourcePresent";
			method.addStatement("final $T $N = state.$N", cursorType, cursorName, stateCursorName)
					.addStatement("final boolean $N", presentName)
					.addStatement("final $T $N", targetElementType, valueName)
					.addStatement("randomInput.bindRegion($N, $L, $L)", cursorName, sourceStart, sourceLength)
					.beginControlFlow("try");
			NullableWireEmitter.emitPresence(method, sourceType, CodeBlock.of("$N", cursorName), sourcePresentName,
					firstName);
			method.beginControlFlow("if (!$N)", sourcePresentName)
					.addStatement("int trailing = $N.remainingIncludingClosed()", cursorName)
					.beginControlFlow("if (trailing != 0)")
					.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
							"Trailing bytes after empty mapped nullable: ")
					.endControlFlow()
					.addStatement("$N = false", presentName)
					.addStatement("$N = $L", valueName, absentValue)
					.nextControlFlow("else");
			if (wireElementReadPlan != null) {
				wireElementReadPlan.emit(method, basePackageName);
			} else {
				CodeBlock decoded = NullableWireEmitter.valueExpression(sourceType, binaryStrings,
						CodeBlock.of("$N", cursorName), firstName, readElement);
				method.addStatement("final $T $N = ($T) $L", sourceElementType, elementName,
						sourceElementType, decoded);
			}
			CodeBlock mapped = elementTransform.emit(method, basePackageName);
			method.addStatement("$N = true", presentName)
					.addStatement("$N = ($T) $L", valueName, targetElementType, mapped)
					.addStatement("int trailing = $N.remainingIncludingClosed()", cursorName)
					.beginControlFlow("if (trailing != 0)")
					.addStatement("throw new $T($S + trailing)", MalformedDataException.class,
							"Trailing bytes after mapped nullable: ")
					.endControlFlow()
					.endControlFlow()
					.nextControlFlow("finally")
					.beginControlFlow("if ($N.isBound())", cursorName)
					.addStatement("$N.unbind()", cursorName)
					.endControlFlow()
					.endControlFlow();
		}
	}

	private record StaticMethod(ClassName owner, String method) {}

	private record TransformSupport(String fieldName) {}

	private record WireViewBinding(String fieldName, ClassName implementationType, TypeName interfaceType) {}

	private record WireTransformCursorKey(String kind,
			ComputedTypeBase inputOwner,
			String inputField,
			ReadTransformConfiguration transform,
			StorageKernel kernel) {}

	private record ReadInitializerFrameKey(NewDataConfiguration initializer,
			TransformSupport support,
			ComputedTypeBase apiOwner,
			ComputedType resultType,
			List<LazyContext> contexts,
			boolean needsRecordRegion) {}

	private record ReadUpgradeFrameKey(UpgradeDataConfiguration upgrade,
			TransformSupport support,
			ComputedTypeBase serializedOwner,
			ComputedTypeBase apiOwner,
			ComputedType oldType,
			ComputedType newType,
			boolean serializedAvailable,
			String sourceReader,
			ComputedType currentValueType,
			String currentSourceReader,
			String currentObjectMapper,
			List<LazyContext> contexts,
			boolean needsRecordRegion) {}

	private record DirectField(String inputField, ComputedType sourceType) {}

	private sealed interface WireScanStep permits FixedScanRun, SequentialScanField { }

	private record FixedScanRun(int byteSize, List<FixedScanField> fields) implements WireScanStep { }

	private record FixedScanField(String fieldName,
			ComputedType sourceType,
			int byteOffset,
			int byteSize,
			FixedScanAction action) { }

	private record SequentialScanField(String fieldName, ComputedType sourceType) implements WireScanStep { }

	private enum FixedScanAction {
		SKIP(false, false),
		CAPTURE(false, true),
		READ(true, false),
		CAPTURE_AND_READ(true, true);

		private final boolean reads;
		private final boolean captures;

		FixedScanAction(boolean reads, boolean captures) {
			this.reads = reads;
			this.captures = captures;
		}

		private boolean reads() {
			return reads;
		}

		private boolean captures() {
			return captures;
		}
	}

	private record LazyContext(String fieldName,
			ComputedType type,
			ComputedType currentType,
			String readerMethod,
			String directReaderMethod,
			String currentReaderMethod,
			String regionStartVariable,
			String regionLengthVariable) {
		private boolean direct() {
			return directReaderMethod != null;
		}

		private String methodName() {
			return "context" + capitalize(fieldName);
		}

		private String cacheName() {
			return "context" + capitalize(fieldName) + "Value";
		}

		private String cacheSetName() {
			return "context" + capitalize(fieldName) + "Set";
		}

		private boolean hasCurrent() {
			return currentType != null;
		}

		private String currentMethodName() {
			return "currentContext" + capitalize(fieldName);
		}

		private String currentCacheName() {
			return "currentContext" + capitalize(fieldName) + "Value";
		}

		private String currentCacheSetName() {
			return "currentContext" + capitalize(fieldName) + "Set";
		}

		private String frameStartName() {
			return "context" + capitalize(fieldName) + "Start";
		}

		private String frameLengthName() {
			return "context" + capitalize(fieldName) + "Length";
		}
	}

	private record SkipperKey(String implementation, String name, int version) {}
}
