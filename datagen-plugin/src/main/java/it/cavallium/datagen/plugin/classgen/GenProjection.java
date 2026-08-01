package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataInitializer;
import it.cavallium.datagen.DataSkipper;
import it.cavallium.datagen.DataUpgrader;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedType;
import it.cavallium.datagen.plugin.ComputedType.VersionedComputedType;
import it.cavallium.datagen.plugin.ComputedTypeArray;
import it.cavallium.datagen.plugin.ComputedTypeBase;
import it.cavallium.datagen.plugin.ComputedTypeCustom;
import it.cavallium.datagen.plugin.ComputedTypeNative;
import it.cavallium.datagen.plugin.ComputedTypeNullable;
import it.cavallium.datagen.plugin.ComputedTypeNullableNative;
import it.cavallium.datagen.plugin.ComputedTypeSuper;
import it.cavallium.datagen.plugin.CustomTypesConfiguration;
import it.cavallium.datagen.plugin.DataModel;
import it.cavallium.datagen.plugin.FieldLocation;
import it.cavallium.datagen.plugin.JInterfaceLocation;
import it.cavallium.datagen.plugin.JInterfaceLocation.JInterfaceLocationClassName;
import it.cavallium.datagen.plugin.JInterfaceLocation.JInterfaceLocationInstanceField;
import it.cavallium.datagen.plugin.MoveDataConfiguration;
import it.cavallium.datagen.plugin.NewDataConfiguration;
import it.cavallium.datagen.plugin.ProjectionConfiguration;
import it.cavallium.datagen.plugin.RemoveDataConfiguration;
import it.cavallium.datagen.plugin.TransformationConfiguration;
import it.cavallium.datagen.plugin.UpgradeDataConfiguration;
import it.cavallium.stream.SafeDataInput;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;

/** Generates compact, version-aware readers for configured field projections. */
public final class GenProjection extends ClassGenerator {

	public GenProjection(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getProjections().entrySet().stream()
				.map(entry -> new ProjectionGenerator(entry.getKey(), entry.getValue()).generate());
	}

	private final class ProjectionGenerator {

		private final String projectionName;
		private final ProjectionConfiguration configuration;
		private final TypeSpec.Builder classBuilder;
		private final ClassName projectionClassName;
		private final ClassName resultClassName;
		private final ClassName sinkClassName;
		private final ClassName readerClassName;
		private final List<ProjectionField> fields;
		private final Map<SkipperKey, String> skipperMethods = new LinkedHashMap<>();
		private final Deque<Map.Entry<SkipperKey, ComputedType>> pendingSkippers = new ArrayDeque<>();
		private final Map<String, String> customSkipperFields = new LinkedHashMap<>();
		private final IdentityHashMap<Object, TransformSupport> transformSupports = new IdentityHashMap<>();
		private int nextSkipperId;
		private int nextTransformId;
		private int nextPresenceId;

		private ProjectionGenerator(String projectionName, ProjectionConfiguration configuration) {
			this.projectionName = requireIdentifier(projectionName, "Projection name");
			this.configuration = Objects.requireNonNull(configuration, "Projection " + projectionName + " configuration");
			String sourceType = requireIdentifier(configuration.sourceType,
					"Projection " + projectionName + " sourceType");
			ComputedType currentSource = dataModel.getComputedTypes(dataModel.getCurrentVersion()).get(sourceType);
			if (!(currentSource instanceof ComputedTypeBase)) {
				throw configurationError("sourceType must name a base record, got " + sourceType);
			}
			if (configuration.fields == null || configuration.fields.isEmpty()) {
				throw configurationError("fields must contain at least one result component");
			}

			String className = projectionName.endsWith("Projection") ? projectionName : projectionName + "Projection";
			String packageName = DataModel.joinPackage(basePackageName, "projections");
			this.projectionClassName = ClassName.get(packageName, className);
			this.resultClassName = projectionClassName.nestedClass("Result");
			this.sinkClassName = projectionClassName.nestedClass("Sink");
			this.readerClassName = projectionClassName.nestedClass("Reader");
			this.classBuilder = TypeSpec.classBuilder(className)
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.addJavadoc("Reads the configured fields of {@code $L} without materializing the complete payload.\n",
							sourceType);

			var resultFields = new ArrayList<ProjectionField>();
			int index = 0;
			for (var field : configuration.fields.entrySet()) {
				String resultName = requireIdentifier(field.getKey(), "Projection result component");
				List<String> path = parsePath(field.getValue(), resultName);
				PathInfo currentPath = resolvePath(dataModel.getCurrentVersionNumber(), path);
				NullableDescriptor nullableDescriptor = currentPath.nullable()
						? nullableDescriptor(currentPath)
						: null;
				TypeName valueType = currentPath.underlying().getJTypeName(basePackageName);
				TypeName resultType = currentPath.nullable() ? nullableDescriptor.wrapperType() : valueType;
				resultFields.add(new ProjectionField(index++, resultName, path, currentPath, nullableDescriptor,
						valueType, resultType));
			}
			this.fields = List.copyOf(resultFields);
		}

		private GeneratedClass generate() {
			classBuilder.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
			generateResult();
			generateSink();

			var plans = dataModel.getVersionsSet().stream()
					.map(version -> new VersionPlan(version.getVersion()))
					.toList();
			plans.forEach(VersionPlan::compile);

			generateStaticEntryPoints(plans);
			generateStaticVersionMethods(plans);
			generateReader(plans);
			generatePendingSkippers();
			return new GeneratedClass(projectionClassName.packageName(), classBuilder);
		}

		private void generateResult() {
			var constructor = MethodSpec.constructorBuilder();
			for (ProjectionField field : fields) {
				constructor.addParameter(field.resultTypeName(), field.name());
			}
			classBuilder.addType(TypeSpec.recordBuilder("Result")
					.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
					.recordConstructor(constructor.build())
					.build());
		}

		private void generateSink() {
			var accept = MethodSpec.methodBuilder("accept")
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);
			for (ProjectionField field : fields) {
				if (field.isNullable()) {
					accept.addParameter(TypeName.BOOLEAN, field.name() + "Present");
				}
				accept.addParameter(field.valueTypeName(), field.name());
			}
			classBuilder.addType(TypeSpec.interfaceBuilder("Sink")
					.addModifiers(Modifier.PUBLIC)
					.addAnnotation(FunctionalInterface.class)
					.addMethod(accept.build())
					.build());
		}

		private void generateStaticEntryPoints(List<VersionPlan> plans) {
			var read = MethodSpec.methodBuilder("read")
					.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
					.returns(resultClassName)
					.addParameter(TypeName.INT, "version")
					.addParameter(SafeDataInput.class, "input")
					.addStatement("$T.requireNonNull(input, $S)", Objects.class, "input")
					.beginControlFlow("return switch (version)");
			for (VersionPlan plan : plans) {
				read.addStatement("case $L -> readV$L(input)", plan.inputVersion, plan.inputVersion);
			}
			read.addStatement("default -> throw unsupportedVersion(version)")
					.addCode("$<};\n");
			classBuilder.addMethod(read.build());

			var readInto = MethodSpec.methodBuilder("readInto")
					.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
					.addParameter(TypeName.INT, "version")
					.addParameter(SafeDataInput.class, "input")
					.addParameter(sinkClassName, "sink")
					.addStatement("$T.requireNonNull(input, $S)", Objects.class, "input")
					.addStatement("$T.requireNonNull(sink, $S)", Objects.class, "sink")
					.beginControlFlow("switch (version)");
			for (VersionPlan plan : plans) {
				readInto.addStatement("case $L -> readIntoV$L(input, sink)", plan.inputVersion, plan.inputVersion);
			}
			readInto.addStatement("default -> throw unsupportedVersion(version)")
					.endControlFlow();
			classBuilder.addMethod(readInto.build());

			classBuilder.addMethod(MethodSpec.methodBuilder("newReader")
					.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
					.returns(readerClassName)
					.addStatement("return new $T()", readerClassName)
					.build());

			classBuilder.addMethod(MethodSpec.methodBuilder("unsupportedVersion")
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC)
					.returns(IllegalArgumentException.class)
					.addParameter(TypeName.INT, "version")
					.addStatement("return new $T($S + version)", IllegalArgumentException.class,
							"Unsupported serialized version: ")
					.build());
		}

		private void generateStaticVersionMethods(List<VersionPlan> plans) {
			for (VersionPlan plan : plans) {
				var read = MethodSpec.methodBuilder("readV" + plan.inputVersion)
						.addModifiers(Modifier.PRIVATE, Modifier.STATIC)
						.returns(resultClassName)
						.addParameter(SafeDataInput.class, "input");
				plan.emitRead(read, OutputTarget.RESULT);
				classBuilder.addMethod(read.build());

				var readInto = MethodSpec.methodBuilder("readIntoV" + plan.inputVersion)
						.addModifiers(Modifier.PRIVATE, Modifier.STATIC)
						.addParameter(SafeDataInput.class, "input")
						.addParameter(sinkClassName, "sink");
				plan.emitRead(readInto, OutputTarget.SINK);
				classBuilder.addMethod(readInto.build());
			}
		}

		private void generateReader(List<VersionPlan> plans) {
			var reader = TypeSpec.classBuilder("Reader")
					.addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
					.addJavadoc("Reusable thread-confined reader. It never retains a bound source after an operation returns.\n")
					.addField(FieldSpec.builder(BufDataCursor.class, "cursor", Modifier.PRIVATE, Modifier.FINAL)
							.initializer("new $T()", BufDataCursor.class)
							.build());
			for (ProjectionField field : fields) {
				reader.addField(FieldSpec.builder(field.valueTypeName(), readerValueName(field), Modifier.PRIVATE).build());
				if (field.isNullable()) {
					reader.addField(FieldSpec.builder(TypeName.BOOLEAN, readerPresenceName(field), Modifier.PRIVATE).build());
				}
			}

			for (VersionPlan plan : plans) {
				var versionRead = MethodSpec.methodBuilder("readV" + plan.inputVersion)
						.addModifiers(Modifier.PRIVATE)
						.addParameter(SafeDataInput.class, "input");
				plan.emitRead(versionRead, OutputTarget.READER);
				reader.addMethod(versionRead.build());
			}

			var dispatch = MethodSpec.methodBuilder("readValues")
					.addModifiers(Modifier.PRIVATE)
					.addParameter(TypeName.INT, "version")
					.addParameter(SafeDataInput.class, "input")
					.beginControlFlow("switch (version)");
			for (VersionPlan plan : plans) {
				dispatch.addStatement("case $L -> readV$L(input)", plan.inputVersion, plan.inputVersion);
			}
			dispatch.addStatement("default -> throw unsupportedVersion(version)")
					.endControlFlow();
			reader.addMethod(dispatch.build());

			var clear = MethodSpec.methodBuilder("clearValues").addModifiers(Modifier.PRIVATE);
			for (ProjectionField field : fields) {
				clear.addStatement("this.$N = $L", readerValueName(field), defaultValue(field.currentPath.underlying()));
				if (field.isNullable()) {
					clear.addStatement("this.$N = false", readerPresenceName(field));
				}
			}
			reader.addMethod(clear.build());

			var read = MethodSpec.methodBuilder("read")
					.addModifiers(Modifier.PUBLIC)
					.returns(resultClassName)
					.addParameter(TypeName.INT, "version")
					.addParameter(Buf.class, "source")
					.addParameter(TypeName.INT, "offset")
					.addParameter(TypeName.INT, "length");
			emitReaderBinding(read);
			read.addStatement("return new $T($L)", resultClassName, readerResultArguments());
			reader.addMethod(read.build());

			var readInto = MethodSpec.methodBuilder("readInto")
					.addModifiers(Modifier.PUBLIC)
					.addParameter(TypeName.INT, "version")
					.addParameter(Buf.class, "source")
					.addParameter(TypeName.INT, "offset")
					.addParameter(TypeName.INT, "length")
					.addParameter(sinkClassName, "sink")
					.addStatement("$T.requireNonNull(sink, $S)", Objects.class, "sink");
			emitReaderBinding(readInto);
			readInto.addStatement("sink.accept($L)", readerSinkArguments());
			reader.addMethod(readInto.build());

			classBuilder.addType(reader.build());
		}

		private void emitReaderBinding(MethodSpec.Builder method) {
			method.addStatement("cursor.bind(source, offset, length)")
					.addStatement("boolean success = false")
					.beginControlFlow("try")
					.addStatement("readValues(version, cursor)")
					.addStatement("success = true")
					.nextControlFlow("finally")
					.addStatement("cursor.unbind()")
					.beginControlFlow("if (!success)")
					.addStatement("clearValues()")
					.endControlFlow()
					.endControlFlow();
		}

		private CodeBlock readerResultArguments() {
			var result = CodeBlock.builder();
			for (int i = 0; i < fields.size(); i++) {
				if (i != 0) result.add(", ");
				ProjectionField field = fields.get(i);
				if (field.isNullable()) {
					result.add("this.$N ? $T.of(this.$N) : $T.empty()",
							readerPresenceName(field), field.nullableDescriptor.wrapperType(), readerValueName(field),
							field.nullableDescriptor.wrapperType());
				} else {
					result.add("this.$N", readerValueName(field));
				}
			}
			return result.build();
		}

		private CodeBlock readerSinkArguments() {
			var result = CodeBlock.builder();
			boolean first = true;
			for (ProjectionField field : fields) {
				if (field.isNullable()) {
					if (!first) result.add(", ");
					result.add("this.$N", readerPresenceName(field));
					first = false;
				}
				if (!first) result.add(", ");
				result.add("this.$N", readerValueName(field));
				first = false;
			}
			return result.build();
		}

		private final class VersionPlan {

			private final int inputVersion;
			private final ReadRegistry reads = new ReadRegistry();
			private final Map<ResolveKey, ResolvedValue> resolvedValues = new HashMap<>();
			private final Map<ResolveKey, CodeBlock> resolvedPresences = new HashMap<>();
			private final List<PreparedValue> preparedValues = new ArrayList<>();
			private List<ResolvedValue> outputs;

			private VersionPlan(int inputVersion) {
				this.inputVersion = inputVersion;
			}

			private void compile() {
				outputs = fields.stream()
						.map(field -> resolveAt(dataModel.getCurrentVersionNumber(), field.path()))
						.toList();
				for (int i = 0; i < fields.size(); i++) {
					ResolvedValue output = outputs.get(i);
					ProjectionField field = fields.get(i);
					if (!Objects.equals(output.type().getName(), field.currentPath.underlying().getName())) {
						throw configurationError("field " + field.name() + " resolved to " + output.type()
								+ " instead of " + field.currentPath.underlying());
					}
					if (output.nullable() != field.isNullable()) {
						throw configurationError("field " + field.name() + " has inconsistent nullability in version "
								+ inputVersion);
					}
				}
				reads.buildTree();
			}

			private ResolvedValue resolveAt(int targetVersion, List<String> targetPath) {
				var key = new ResolveKey(targetVersion, List.copyOf(targetPath));
				ResolvedValue cached = resolvedValues.get(key);
				if (cached != null) return cached;
				ResolvedValue result;
				if (targetVersion == inputVersion) {
					result = reads.register(targetPath);
				} else {
					result = resolveBoundary(targetVersion,
							configuration.sourceType,
							List.of(),
							List.of(),
							targetPath);
				}
				resolvedValues.put(key, result);
				return result;
			}

			private ResolvedValue resolveBoundary(int targetVersion,
					String ownerName,
					List<String> nextPrefix,
					List<String> previousPrefix,
					List<String> relativePath) {
				ComputedTypeBase nextOwner = requireBaseType(targetVersion, ownerName);
				ComputedTypeBase previousOwner = requireBaseType(targetVersion - 1, ownerName);
				String nextFieldName = relativePath.getFirst();
				ComputedType nextFieldType = nextOwner.getData().get(nextFieldName);
				if (nextFieldType == null) {
					throw configurationError("path " + String.join(".", concat(nextPrefix, relativePath))
							+ " does not exist in version " + targetVersion);
				}
				FieldOrigin origin = traceFieldOrigin(targetVersion, ownerName, nextFieldName);
				List<String> remainder = relativePath.subList(1, relativePath.size());

				if (!remainder.isEmpty()) {
					if (origin.initializer() != null || !origin.upgrades().isEmpty()) {
						throw configurationError("path " + String.join(".", concat(nextPrefix, relativePath))
								+ " traverses a field handled by an opaque initializer/upgrader in version " + targetVersion
								+ "; arrays, unions, custom values, and opaque upgrades are terminal projection values");
					}
					ComputedType previousFieldType = previousOwner.getData().get(origin.previousName());
					ComputedTypeBase nextChild = traversableBase(nextFieldType, nextPrefix, relativePath);
					ComputedTypeBase previousChild = traversableBase(previousFieldType, previousPrefix,
							concat(List.of(origin.previousName()), remainder));
					if (!nextChild.getName().equals(previousChild.getName())) {
						throw configurationError("path " + String.join(".", concat(nextPrefix, relativePath))
								+ " crosses incompatible record types " + previousChild.getName() + " and " + nextChild.getName());
					}
					return resolveBoundary(targetVersion,
							nextChild.getName(),
							concat(nextPrefix, List.of(nextFieldName)),
							concat(previousPrefix, List.of(origin.previousName())),
							remainder);
				}

				ResolvedValue value;
				ComputedType operationType;
				if (origin.initializer() != null) {
					operationType = typeNamed(targetVersion, DataModel.fixType(origin.initializer().type));
					value = applyInitializer(targetVersion, previousOwner, previousPrefix, origin.initializer(), operationType);
				} else {
					ComputedType previousFieldType = previousOwner.getData().get(origin.previousName());
					if (previousFieldType == null) {
						throw configurationError("field " + ownerName + "." + origin.previousName()
								+ " is missing in version " + (targetVersion - 1));
					}
					value = resolveAt(targetVersion - 1, concat(previousPrefix, List.of(origin.previousName())));
					operationType = previousFieldType;
				}

				for (UpgradeDataConfiguration upgrade : origin.upgrades()) {
					ComputedType newType = typeNamed(targetVersion, DataModel.fixType(upgrade.type));
					value = applyExplicitUpgrade(targetVersion, previousOwner, previousPrefix,
							upgrade, operationType, newType, value);
					operationType = newType;
				}
				return upgradeValue(value, operationType, nextFieldType);
			}

			private ResolvedValue applyInitializer(int targetVersion,
					ComputedTypeBase previousOwner,
					List<String> previousPrefix,
					NewDataConfiguration initializer,
					ComputedType newType) {
				TransformSupport support = transformSupports.computeIfAbsent(initializer,
						ignored -> createInitializerSupport(initializer, previousOwner, newType));
				ContextCall context = contextCall(targetVersion, previousOwner, previousPrefix,
						initializer.getContextParameters(), support.contextType());
				CodeBlock call = CodeBlock.of("$N.initialize($L)", support.fieldName(), context.expression());
				CodeBlock guard = and(context.guard(), resolvePresenceAt(targetVersion - 1, previousPrefix));
				return prepareValue(newType, call, guard);
			}

			private ResolvedValue applyExplicitUpgrade(int targetVersion,
					ComputedTypeBase previousOwner,
					List<String> previousPrefix,
					UpgradeDataConfiguration upgrade,
					ComputedType oldType,
					ComputedType newType,
					ResolvedValue oldValue) {
				TransformSupport support = transformSupports.computeIfAbsent(upgrade,
						ignored -> createUpgraderSupport(upgrade, previousOwner, oldType, newType));
				ContextCall context = contextCall(targetVersion, previousOwner, previousPrefix,
						upgrade.getContextParameters(), support.contextType());
				CodeBlock oldDeclared = declaredValue(oldValue, oldType);
				CodeBlock call = CodeBlock.of("$N.upgrade($L, $L)", support.fieldName(), context.expression(), oldDeclared);
				CodeBlock guard = and(context.guard(), resolvePresenceAt(targetVersion - 1, previousPrefix));
				return prepareValue(newType, call, guard);
			}

			private CodeBlock resolvePresenceAt(int targetVersion, List<String> recordPath) {
				if (recordPath.isEmpty() || !resolvePath(targetVersion, recordPath).nullable()) {
					return null;
				}
				var key = new ResolveKey(targetVersion, List.copyOf(recordPath));
				if (resolvedPresences.containsKey(key)) return resolvedPresences.get(key);
				CodeBlock result = targetVersion == inputVersion
						? reads.registerPresence(recordPath)
						: resolvePresenceAt(targetVersion - 1, mapDirectPathBackward(targetVersion, recordPath));
				resolvedPresences.put(key, result);
				return result;
			}

			private List<String> mapDirectPathBackward(int targetVersion, List<String> path) {
				String ownerName = configuration.sourceType;
				var previousPath = new ArrayList<String>(path.size());
				for (int i = 0; i < path.size(); i++) {
					String nextFieldName = path.get(i);
					ComputedTypeBase nextOwner = requireBaseType(targetVersion, ownerName);
					ComputedTypeBase previousOwner = requireBaseType(targetVersion - 1, ownerName);
					FieldOrigin origin = traceFieldOrigin(targetVersion, ownerName, nextFieldName);
					if (origin.initializer() != null || !origin.upgrades().isEmpty()) {
						throw configurationError("nullable record path " + String.join(".", path)
								+ " crosses an opaque initializer/upgrader in version " + targetVersion);
					}
					previousPath.add(origin.previousName());
					if (i + 1 < path.size()) {
						ComputedTypeBase nextChild = traversableBase(nextOwner.getData().get(nextFieldName), List.of(), path);
						ComputedTypeBase previousChild = traversableBase(previousOwner.getData().get(origin.previousName()),
								List.of(), previousPath);
						if (!nextChild.getName().equals(previousChild.getName())) {
							throw configurationError("nullable record path " + String.join(".", path)
									+ " crosses incompatible record types");
						}
						ownerName = nextChild.getName();
					}
				}
				return List.copyOf(previousPath);
			}

			private ContextCall contextCall(int targetVersion,
					ComputedTypeBase previousOwner,
					List<String> previousPrefix,
					List<String> contextParameters,
					TypeName contextType) {
				if (contextParameters.isEmpty()) {
					return new ContextCall(CodeBlock.of("$T.INSTANCE", DataContextNone.class), null);
				}
				var values = new ArrayList<ResolvedValue>();
				for (String contextParameter : contextParameters) {
					if (!previousOwner.getData().containsKey(contextParameter)) {
						throw configurationError("unknown context field " + previousOwner.getName() + "." + contextParameter);
					}
					values.add(resolveAt(targetVersion - 1, concat(previousPrefix, List.of(contextParameter))));
				}
				var expression = CodeBlock.builder().add("new $T(", contextType);
				CodeBlock guard = null;
				for (int i = 0; i < contextParameters.size(); i++) {
					if (i != 0) expression.add(", ");
					ComputedType declaredType = previousOwner.getData().get(contextParameters.get(i));
					ResolvedValue value = values.get(i);
					expression.add("$L", declaredValue(value, declaredType));
					if (!(declaredType instanceof ComputedTypeNullable) && value.nullable()) {
						guard = and(guard, value.present());
					}
				}
				expression.add(")");
				return new ContextCall(expression.build(), guard);
			}

			private ResolvedValue upgradeValue(ResolvedValue value, ComputedType oldDeclared, ComputedType newDeclared) {
				if (oldDeclared.equals(newDeclared)) {
					return value.withType(unwrap(newDeclared));
				}
				if (oldDeclared instanceof ComputedTypeNullable oldNullable
						&& newDeclared instanceof ComputedTypeNullable newNullable) {
					ResolvedValue unwrapped = value.withType(oldNullable.getBase());
					ResolvedValue upgraded = upgradeValue(unwrapped, oldNullable.getBase(), newNullable.getBase());
					return new ResolvedValue(newNullable.getBase(), upgraded.value(), value.present(), true, null);
				}
				ComputedType current = oldDeclared;
				CodeBlock code = value.value();
				while (!current.equals(newDeclared) && current instanceof VersionedComputedType) {
					ComputedType next = dataModel.getNextVersion(current);
					if (next == null) break;
					CodeBlock upgraded = current.wrapWithUpgrade(basePackageName, code, next);
					code = value.nullable()
							? CodeBlock.of("$L ? $L : $L", value.present(), upgraded, defaultValue(unwrap(next)))
							: upgraded;
					current = next;
				}
				if (!current.equals(newDeclared)) {
					throw configurationError("cannot project value across type change " + oldDeclared + " -> " + newDeclared);
				}
				return new ResolvedValue(unwrap(newDeclared), code, value.present(), value.nullable(), null);
			}

			private CodeBlock declaredValue(ResolvedValue value, ComputedType declaredType) {
				if (declaredType instanceof ComputedTypeNullable nullable) {
					if (value.wrapper() != null) return value.wrapper();
					TypeName wrapper = nullable.getJTypeName(basePackageName);
					return CodeBlock.of("$L ? $T.of($L) : $T.empty()", value.present(), wrapper, value.value(), wrapper);
				}
				return value.value();
			}

			private ResolvedValue prepareValue(ComputedType declaredType, CodeBlock expression, CodeBlock guard) {
				String name = "prepared" + preparedValues.size();
				preparedValues.add(new PreparedValue(name, declaredType, expression, guard));
				if (declaredType instanceof ComputedTypeNullable nullable) {
					CodeBlock present = CodeBlock.of("$N.isPresent()", name);
					return new ResolvedValue(nullable.getBase(),
							CodeBlock.of("$L ? $N.get() : $L", present, name, defaultValue(nullable.getBase())),
							present,
							true,
							CodeBlock.of("$N", name));
				}
				return new ResolvedValue(declaredType, CodeBlock.of("$N", name), guard, guard != null, null);
			}

			private void emitPreparedValues(MethodSpec.Builder method) {
				for (PreparedValue prepared : preparedValues) {
					TypeName type = prepared.declaredType().getJTypeName(basePackageName);
					if (prepared.guard() == null) {
						method.addStatement("final $T $N = $L", type, prepared.name(), prepared.expression());
					} else if (prepared.declaredType() instanceof ComputedTypeNullable) {
						method.addStatement("final $T $N = $L ? $L : $T.empty()", type, prepared.name(),
								prepared.guard(), prepared.expression(), type);
					} else {
						method.addStatement("final $T $N = $L ? $L : $L", type, prepared.name(),
								prepared.guard(), prepared.expression(), defaultValue(prepared.declaredType()));
					}
				}
				if (!preparedValues.isEmpty()) method.addCode("\n");
			}

			private void emitRead(MethodSpec.Builder method, OutputTarget target) {
				reads.emitDeclarations(method);
				reads.emitReads(method);
				emitPreparedValues(method);

				if (target == OutputTarget.READER) {
					for (int i = 0; i < fields.size(); i++) {
						ProjectionField field = fields.get(i);
						ResolvedValue output = outputs.get(i);
						method.addStatement("this.$N = $L", readerValueName(field), output.value());
						if (field.isNullable()) {
							method.addStatement("this.$N = $L", readerPresenceName(field), output.present());
						}
					}
					return;
				}

				for (int i = 0; i < fields.size(); i++) {
					ProjectionField field = fields.get(i);
					ResolvedValue output = outputs.get(i);
					method.addStatement("final $T projected$L = $L", field.valueTypeName(), i, output.value());
					if (field.isNullable()) {
						method.addStatement("final boolean projected$LPresent = $L", i, output.present());
					}
				}

				if (target == OutputTarget.RESULT) {
					var arguments = CodeBlock.builder();
					for (int i = 0; i < fields.size(); i++) {
						if (i != 0) arguments.add(", ");
						ProjectionField field = fields.get(i);
						if (field.isNullable()) {
							arguments.add("projected$LPresent ? $T.of(projected$L) : $T.empty()",
									i, field.nullableDescriptor.wrapperType(), i, field.nullableDescriptor.wrapperType());
						} else {
							arguments.add("projected$L", i);
						}
					}
					method.addStatement("return new $T($L)", resultClassName, arguments.build());
				} else {
					var arguments = CodeBlock.builder();
					boolean first = true;
					for (int i = 0; i < fields.size(); i++) {
						ProjectionField field = fields.get(i);
						if (field.isNullable()) {
							if (!first) arguments.add(", ");
							arguments.add("projected$LPresent", i);
							first = false;
						}
						if (!first) arguments.add(", ");
						arguments.add("projected$L", i);
						first = false;
					}
					method.addStatement("sink.accept($L)", arguments.build());
				}
			}

			private final class ReadRegistry {

				private final LinkedHashMap<List<String>, ReadLeaf> leaves = new LinkedHashMap<>();
				private final LinkedHashMap<List<String>, String> presences = new LinkedHashMap<>();
				private ReadNode root;

				private ResolvedValue register(List<String> path) {
					List<String> key = List.copyOf(path);
					ReadLeaf leaf = leaves.get(key);
					if (leaf == null) {
						PathInfo pathInfo = resolvePath(inputVersion, key);
						leaf = new ReadLeaf(leaves.size(), key, pathInfo);
						leaves.put(key, leaf);
					}
					return new ResolvedValue(leaf.pathInfo.underlying(),
							CodeBlock.of("$N", leaf.valueName()),
							leaf.pathInfo.nullable() ? CodeBlock.of("$N", leaf.presenceName()) : null,
							leaf.pathInfo.nullable(),
							null);
				}

				private CodeBlock registerPresence(List<String> path) {
					List<String> key = List.copyOf(path);
					ReadLeaf exactLeaf = leaves.get(key);
					if (exactLeaf != null && exactLeaf.pathInfo.nullable()) {
						return CodeBlock.of("$N", exactLeaf.presenceName());
					}
					String name = presences.computeIfAbsent(key, ignored -> "recordPresent" + presences.size());
					return CodeBlock.of("$N", name);
				}

				private void buildTree() {
					root = new ReadNode();
					for (ReadLeaf leaf : leaves.values()) {
						ReadNode node = root;
						for (String segment : leaf.path) {
							if (node.leaf != null) {
								throw configurationError("projection dependencies overlap at " + String.join(".", leaf.path));
							}
							node = node.children.computeIfAbsent(segment, ignored -> new ReadNode());
						}
						if (!node.children.isEmpty()) {
							throw configurationError("projection dependencies overlap at " + String.join(".", leaf.path));
						}
						node.leaf = leaf;
					}
					for (var presence : presences.entrySet()) {
						ReadNode node = root;
						for (String segment : presence.getKey()) {
							node = node.children.computeIfAbsent(segment, ignored -> new ReadNode());
						}
						node.presenceName = presence.getValue();
					}
				}

				private void emitDeclarations(MethodSpec.Builder method) {
					for (ReadLeaf leaf : leaves.values()) {
						method.addStatement("$T $N = $L", leaf.pathInfo.underlying().getJTypeName(basePackageName),
								leaf.valueName(), defaultValue(leaf.pathInfo.underlying()));
						if (leaf.pathInfo.nullable()) {
							method.addStatement("boolean $N = false", leaf.presenceName());
						}
					}
					for (String presenceName : presences.values()) {
						method.addStatement("boolean $N = false", presenceName);
					}
					if (!leaves.isEmpty() || !presences.isEmpty()) method.addCode("\n");
				}

				private void emitReads(MethodSpec.Builder method) {
					emitRecord(method, requireBaseType(inputVersion, configuration.sourceType), root, false);
					if (!leaves.isEmpty()) method.addCode("\n");
				}

				private void emitRecord(MethodSpec.Builder method,
						ComputedTypeBase record,
						ReadNode node,
						boolean consumeToEnd) {
					Set<String> remaining = new LinkedHashSet<>(node.children.keySet());
					for (var field : record.getData().entrySet()) {
						ReadNode child = node.children.get(field.getKey());
						if (child == null) {
							if (consumeToEnd || !remaining.isEmpty()) {
								method.addStatement("$N(input)", ensureSkipper(field.getValue()));
							}
							continue;
						}
						remaining.remove(field.getKey());
						emitField(method, field.getValue(), child, consumeToEnd || !remaining.isEmpty());
						if (remaining.isEmpty() && !consumeToEnd) break;
					}
					if (!remaining.isEmpty()) {
						throw configurationError("fields not found while reading " + record.getName() + ": " + remaining);
					}
				}

				private void emitField(MethodSpec.Builder method,
						ComputedType declaredType,
						ReadNode node,
						boolean consumeToEnd) {
					if (node.leaf != null) {
						ReadLeaf leaf = node.leaf;
						if (declaredType instanceof ComputedTypeNullable nullable) {
							int presenceId = nextPresenceId++;
							String present = "present" + presenceId;
							String nullableValue = "nullableValue" + presenceId;
							method.addStatement("$T $N = $L", nullable.getJTypeName(basePackageName), nullableValue,
									readValue(nullable))
									.addStatement("boolean $N = $N.isPresent()", present, nullableValue)
									.addCode(node.presenceName == null
											? CodeBlock.builder().build()
											: CodeBlock.of("$N = $N;\n", node.presenceName, present))
									.beginControlFlow("if ($N)", present)
									.addStatement("$N = $N.get()", leaf.valueName(), nullableValue)
									.addStatement("$N = true", leaf.presenceName())
									.endControlFlow();
						} else {
							if (node.presenceName != null) method.addStatement("$N = true", node.presenceName);
							method.addStatement("$N = $L", leaf.valueName(), readValue(declaredType));
							if (leaf.pathInfo.nullable()) {
								method.addStatement("$N = true", leaf.presenceName());
							}
						}
						return;
					}

					if (node.children.isEmpty()) {
						if (declaredType instanceof ComputedTypeNullable nullable) {
							String present = "present" + nextPresenceId++;
							method.addStatement("boolean $N = input.readBoolean()", present)
									.addStatement("$N = $N", node.presenceName, present);
							if (consumeToEnd) {
								method.beginControlFlow("if ($N)", present)
										.addStatement("$N(input)", ensureSkipper(nullable.getBase()))
										.endControlFlow();
							}
						} else {
							method.addStatement("$N = true", node.presenceName);
							if (consumeToEnd) method.addStatement("$N(input)", ensureSkipper(declaredType));
						}
						return;
					}

					ComputedType nested = declaredType;
					if (nested instanceof ComputedTypeNullable nullable) {
						String present = "present" + nextPresenceId++;
						method.addStatement("boolean $N = input.readBoolean()", present)
								.addCode(node.presenceName == null
										? CodeBlock.builder().build()
										: CodeBlock.of("$N = $N;\n", node.presenceName, present))
								.beginControlFlow("if ($N)", present);
						nested = nullable.getBase();
						if (!(nested instanceof ComputedTypeBase nestedRecord)) {
							throw configurationError("only records and nullable records can be traversed");
						}
						emitRecord(method, nestedRecord, node, consumeToEnd);
						method.endControlFlow();
					} else if (nested instanceof ComputedTypeBase nestedRecord) {
						if (node.presenceName != null) method.addStatement("$N = true", node.presenceName);
						emitRecord(method, nestedRecord, node, consumeToEnd);
					} else {
						throw configurationError("only records and nullable records can be traversed");
					}
				}
			}
		}

		private CodeBlock readValue(ComputedType type) {
			if (type instanceof ComputedTypeNative nativeType && nativeType.isPrimitive()) {
				return CodeBlock.of("input.read$N()", capitalize(nativeType.getName()));
			}
			FieldLocation serializer = type.getJSerializerInstance(basePackageName);
			return CodeBlock.of("($T) $T.$N.deserialize(input)", type.getJTypeName(basePackageName),
					serializer.className(), serializer.fieldName());
		}

		private String ensureSkipper(ComputedType type) {
			SkipperKey key = new SkipperKey(type.getClass().getName(), type.getName(),
					type instanceof VersionedComputedType versioned ? versioned.getVersion().getVersion() : -1);
			String existing = skipperMethods.get(key);
			if (existing != null) return existing;
			String methodName = "skip" + nextSkipperId++;
			skipperMethods.put(key, methodName);
			pendingSkippers.add(Map.entry(key, type));
			return methodName;
		}

		private void generatePendingSkippers() {
			while (!pendingSkippers.isEmpty()) {
				var pending = pendingSkippers.removeFirst();
				String name = skipperMethods.get(pending.getKey());
				ComputedType type = pending.getValue();
				var method = MethodSpec.methodBuilder(name)
						.addModifiers(Modifier.PRIVATE, Modifier.STATIC)
						.addParameter(SafeDataInput.class, "input");
				emitSkipBody(method, type);
				classBuilder.addMethod(method.build());
			}
		}

		private void emitSkipBody(MethodSpec.Builder method, ComputedType type) {
			if (type instanceof ComputedTypeNative nativeType) {
				int fixedSize = switch (nativeType.getName()) {
					case "boolean", "byte" -> 1;
					case "short", "char" -> 2;
					case "int", "float" -> 4;
					case "long", "double" -> 8;
					case "Int52" -> 7;
					case "String" -> -1;
					default -> throw new IllegalStateException(nativeType.getName());
				};
				if (fixedSize >= 0) {
					method.addStatement("$T.skipBytes(input, $L)", ProjectionReadSupport.class, fixedSize);
				} else {
					method.addStatement("$T.skipBytes(input, input.readInt())", ProjectionReadSupport.class);
				}
				return;
			}
			if (type instanceof ComputedTypeCustom custom) {
				CustomTypesConfiguration customConfiguration = dataModel.getCustomTypes().get(custom.getName());
				if (customConfiguration == null || customConfiguration.skipper == null || customConfiguration.skipper.isBlank()) {
					throw configurationError("crossing unselected custom type " + custom.getName()
							+ " requires customTypesData." + custom.getName() + ".skipper");
				}
				String fieldName = customSkipperFields.computeIfAbsent(custom.getName(), ignored -> {
					String generatedName = "SKIPPER_" + customSkipperFields.size();
					classBuilder.addField(FieldSpec.builder(DataSkipper.class, generatedName,
							Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
							.initializer("new $T()", ClassName.bestGuess(customConfiguration.skipper))
							.build());
					return generatedName;
				});
				method.addStatement("$N.skip(input)", fieldName);
				return;
			}
			if (type instanceof ComputedTypeNullableNative nullableNative
					&& (nullableNative.getBase().getName().equals("String")
							|| nullableNative.getBase().getName().equals("Int52"))) {
				FieldLocation serializer = nullableNative.getJSerializerInstance(basePackageName);
				method.addStatement("$T.$N.skip(input)", serializer.className(), serializer.fieldName());
				return;
			}
			if (type instanceof ComputedTypeNullable nullable) {
				method.beginControlFlow("if (input.readBoolean())")
						.addStatement("$N(input)", ensureSkipper(nullable.getBase()))
						.endControlFlow();
				return;
			}
			if (type instanceof ComputedTypeArray array) {
				method.addStatement("int size = $T.readLength(input)", ProjectionReadSupport.class)
						.beginControlFlow("for (int i = 0; i < size; i++)")
						.addStatement("$N(input)", ensureSkipper(array.getBase()))
						.endControlFlow();
				return;
			}
			if (type instanceof ComputedTypeBase base) {
				for (ComputedType fieldType : base.getData().values()) {
					method.addStatement("$N(input)", ensureSkipper(fieldType));
				}
				return;
			}
			if (type instanceof ComputedTypeSuper union) {
				method.addStatement("int id = input.readUnsignedByte()")
						.beginControlFlow("switch (id)");
				for (int i = 0; i < union.subTypes().size(); i++) {
					method.addStatement("case $L -> $N(input)", i, ensureSkipper(union.subTypes().get(i)));
				}
				method.addStatement("default -> throw new $T(id)", IndexOutOfBoundsException.class)
						.endControlFlow();
				return;
			}
			throw new IllegalStateException("Unsupported projection skipper type: " + type);
		}

		private TransformSupport createInitializerSupport(NewDataConfiguration initializer,
				ComputedTypeBase previousOwner,
				ComputedType newType) {
			int id = nextTransformId++;
			TypeName contextType = contextType(previousOwner, initializer.to, initializer.getContextParameters());
			TypeName interfaceType = ParameterizedTypeName.get(ClassName.get(DataInitializer.class),
					contextType, newType.getJTypeName(basePackageName).box());
			String fieldName = "INITIALIZER_" + id;
			classBuilder.addField(interfaceField(interfaceType, fieldName, initializer.getInitializerLocation()));
			return new TransformSupport(fieldName, contextType);
		}

		private TransformSupport createUpgraderSupport(UpgradeDataConfiguration upgrade,
				ComputedTypeBase previousOwner,
				ComputedType oldType,
				ComputedType newType) {
			int id = nextTransformId++;
			TypeName contextType = contextType(previousOwner, upgrade.from, upgrade.getContextParameters());
			TypeName interfaceType = ParameterizedTypeName.get(ClassName.get(DataUpgrader.class),
					contextType, oldType.getJTypeName(basePackageName).box(), newType.getJTypeName(basePackageName).box());
			String fieldName = "UPGRADER_" + id;
			classBuilder.addField(interfaceField(interfaceType, fieldName, upgrade.getUpgraderLocation()));
			return new TransformSupport(fieldName, contextType);
		}

		private TypeName contextType(ComputedTypeBase owner, String fieldName, List<String> parameters) {
			if (parameters.isEmpty()) return ClassName.get(DataContextNone.class);
			for (String parameter : parameters) {
				ComputedType parameterType = owner.getData().get(parameter);
				if (parameterType == null) {
					throw configurationError("unknown context field " + owner.getName() + "." + parameter);
				}
			}
			return owner.getJUpgraderName(basePackageName).nestedClass("Context" + capitalize(fieldName));
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
			String name = targetField;
			NewDataConfiguration initializer = null;
			Deque<UpgradeDataConfiguration> upgrades = new ArrayDeque<>();
			List<TransformationConfiguration> changes = dataModel.getChanges(targetVersion, ownerName);
			for (int i = changes.size() - 1; i >= 0; i--) {
				TransformationConfiguration change = changes.get(i);
				if (change instanceof MoveDataConfiguration move && name.equals(move.to)) {
					name = move.from;
				} else if (change instanceof UpgradeDataConfiguration upgrade && name.equals(upgrade.from)) {
					upgrades.addFirst(upgrade);
				} else if (change instanceof NewDataConfiguration added && name.equals(added.to)) {
					initializer = added;
					name = null;
					break;
				} else if (change instanceof RemoveDataConfiguration removed && name.equals(removed.from)) {
					throw configurationError("field " + ownerName + "." + targetField
							+ " cannot originate from removed field " + removed.from);
				}
			}
			return new FieldOrigin(name, initializer, List.copyOf(upgrades));
		}

		private PathInfo resolvePath(int logicalVersion, List<String> path) {
			ComputedType type = typeNamed(logicalVersion, configuration.sourceType);
			boolean nullable = false;
			for (String segment : path) {
				if (type instanceof ComputedTypeNullable wrapper) {
					nullable = true;
					type = wrapper.getBase();
				}
				if (!(type instanceof ComputedTypeBase record)) {
					throw configurationError("path " + String.join(".", path)
							+ " traverses terminal type " + type.getName());
				}
				type = record.getData().get(segment);
				if (type == null) {
					throw configurationError("path " + String.join(".", path) + " has no field " + segment
							+ " in " + record.getName() + " at version " + logicalVersion);
				}
			}
			ComputedType declared = type;
			if (type instanceof ComputedTypeNullable wrapper) {
				nullable = true;
				type = wrapper.getBase();
			}
			return new PathInfo(declared, type, nullable);
		}

		private NullableDescriptor nullableDescriptor(PathInfo path) {
			if (path.declaredTerminal() instanceof ComputedTypeNullable nullable) {
				return new NullableDescriptor(nullable.getJTypeName(basePackageName));
			}
			ComputedType configured = dataModel.getComputedTypes(dataModel.getCurrentVersion()).get("-" + path.underlying().getName());
			if (configured instanceof ComputedTypeNullable nullable) {
				return new NullableDescriptor(nullable.getJTypeName(basePackageName));
			}
			if (path.underlying() instanceof ComputedTypeNative nativeType) {
				String simpleName = switch (nativeType.getName()) {
					case "String" -> binaryStrings ? "NullableBinaryString" : "NullableString";
					default -> "Nullable" + nativeType.getName();
				};
				return new NullableDescriptor(ClassName.get("it.cavallium.datagen.nativedata", simpleName));
			}
			throw configurationError("path through a nullable record produces nullable " + path.underlying().getName()
					+ ", but that nullable type is not generated; reference -" + path.underlying().getName()
					+ " in the schema so the normal nullable type is available");
		}

		private ComputedTypeBase traversableBase(ComputedType type, List<String> prefix, List<String> path) {
			ComputedType unwrapped = unwrap(type);
			if (unwrapped instanceof ComputedTypeBase base) return base;
			throw configurationError("path " + String.join(".", concat(prefix, path))
					+ " traverses terminal type " + unwrapped.getName());
		}

		private ComputedTypeBase requireBaseType(int logicalVersion, String name) {
			ComputedType type = typeNamed(logicalVersion, name);
			if (type instanceof ComputedTypeBase base) return base;
			throw configurationError(name + " is not a base record in version " + logicalVersion);
		}

		private ComputedType typeNamed(int logicalVersion, String name) {
			ComputedType type = dataModel.getComputedTypes(dataModel.getVersion(logicalVersion)).get(name);
			if (type == null) throw configurationError("unknown type " + name + " in version " + logicalVersion);
			return type;
		}

		private ComputedType unwrap(ComputedType type) {
			return type instanceof ComputedTypeNullable nullable ? nullable.getBase() : type;
		}

		private CodeBlock and(CodeBlock first, CodeBlock second) {
			if (first == null) return second;
			if (second == null) return first;
			return CodeBlock.of("($L && $L)", first, second);
		}

		private List<String> parsePath(String rawPath, String resultName) {
			if (rawPath == null || rawPath.isBlank()) {
				throw configurationError("field " + resultName + " has an empty source path");
			}
			List<String> result = Stream.of(rawPath.split("\\.", -1))
					.map(segment -> requireIdentifier(segment, "Projection path segment"))
					.toList();
			if (result.isEmpty()) throw configurationError("field " + resultName + " has an empty source path");
			return result;
		}

		private String configurationErrorPrefix() {
			return "Projection " + projectionName + ": ";
		}

		private IllegalArgumentException configurationError(String message) {
			return new IllegalArgumentException(configurationErrorPrefix() + message);
		}
	}

	private static String requireIdentifier(String value, String description) {
		if (value == null || !SourceVersion.isIdentifier(value) || SourceVersion.isKeyword(value)) {
			throw new IllegalArgumentException(description + " is not a valid Java identifier: " + value);
		}
		return value;
	}

	private static List<String> concat(List<String> first, List<String> second) {
		var result = new ArrayList<String>(first.size() + second.size());
		result.addAll(first);
		result.addAll(second);
		return List.copyOf(result);
	}

	private static String capitalize(String name) {
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	private static CodeBlock defaultValue(ComputedType type) {
		if (!(type instanceof ComputedTypeNative nativeType) || !nativeType.isPrimitive()) {
			return CodeBlock.of("null");
		}
		return switch (nativeType.getName()) {
			case "boolean" -> CodeBlock.of("false");
			case "byte" -> CodeBlock.of("(byte) 0");
			case "short" -> CodeBlock.of("(short) 0");
			case "char" -> CodeBlock.of("(char) 0");
			case "int" -> CodeBlock.of("0");
			case "long" -> CodeBlock.of("0L");
			case "float" -> CodeBlock.of("0.0f");
			case "double" -> CodeBlock.of("0.0d");
			default -> throw new IllegalStateException(nativeType.getName());
		};
	}

	private static String readerValueName(ProjectionField field) {
		return "value" + field.index();
	}

	private static String readerPresenceName(ProjectionField field) {
		return "value" + field.index() + "Present";
	}

	private enum OutputTarget {
		RESULT,
		SINK,
		READER
	}

	private record ProjectionField(int index,
		String name,
		List<String> path,
		PathInfo currentPath,
		NullableDescriptor nullableDescriptor,
		TypeName valueTypeName,
		TypeName resultTypeName) {

		private boolean isNullable() {
			return currentPath.nullable();
		}
	}

	private record PathInfo(ComputedType declaredTerminal, ComputedType underlying, boolean nullable) {}

	private record NullableDescriptor(TypeName wrapperType) {}

	private record ResolvedValue(ComputedType type,
		CodeBlock value,
		CodeBlock present,
		boolean nullable,
		CodeBlock wrapper) {

		private ResolvedValue withType(ComputedType newType) {
			return new ResolvedValue(newType, value, present, nullable, wrapper);
		}
	}

	private record ResolveKey(int version, List<String> path) {}

	private record FieldOrigin(String previousName,
		NewDataConfiguration initializer,
		List<UpgradeDataConfiguration> upgrades) {}

	private record ContextCall(CodeBlock expression, CodeBlock guard) {}

	private record PreparedValue(String name,
		ComputedType declaredType,
		CodeBlock expression,
		CodeBlock guard) {}

	private record TransformSupport(String fieldName, TypeName contextType) {}

	private record SkipperKey(String implementation, String name, int version) {}

	private static final class ReadNode {

		private final LinkedHashMap<String, ReadNode> children = new LinkedHashMap<>();
		private ReadLeaf leaf;
		private String presenceName;
	}

	private record ReadLeaf(int index, List<String> path, PathInfo pathInfo) {

		private String valueName() {
			return "raw" + index;
		}

		private String presenceName() {
			return "raw" + index + "Present";
		}
	}
}
