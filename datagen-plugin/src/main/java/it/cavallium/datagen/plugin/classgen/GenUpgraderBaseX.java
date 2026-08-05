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
import it.cavallium.datagen.DataContext;
import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataInitializer;
import it.cavallium.datagen.DataUpgrader;
import it.cavallium.datagen.DataUpgraderSimple;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedType;
import it.cavallium.datagen.plugin.ComputedType.VersionedComputedType;
import it.cavallium.datagen.plugin.ComputedTypeArray;
import it.cavallium.datagen.plugin.ComputedTypeBase;
import it.cavallium.datagen.plugin.ComputedTypeNullable;
import it.cavallium.datagen.plugin.ComputedTypeSuper;
import it.cavallium.datagen.plugin.ComputedVersion;
import it.cavallium.datagen.plugin.DataModel;
import it.cavallium.datagen.plugin.JInterfaceLocation;
import it.cavallium.datagen.plugin.JInterfaceLocation.JInterfaceLocationClassName;
import it.cavallium.datagen.plugin.JInterfaceLocation.JInterfaceLocationInstanceField;
import it.cavallium.datagen.plugin.MoveDataConfiguration;
import it.cavallium.datagen.plugin.NewDataConfiguration;
import it.cavallium.datagen.plugin.RemoveDataConfiguration;
import it.cavallium.datagen.plugin.SourcesGenerator;
import it.cavallium.datagen.plugin.TransformationConfiguration;
import it.cavallium.datagen.plugin.UpgradeDataConfiguration;
import it.cavallium.stream.SafeDataInput;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.jetbrains.annotations.NotNull;

public class GenUpgraderBaseX extends ClassGenerator {

	public GenUpgraderBaseX(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().parallelStream().flatMap(this::generateVersionClasses);
	}

	private Stream<GeneratedClass> generateVersionClasses(ComputedVersion version) {
		return dataModel
				.getBaseTypesComputed(version)
				.filter(type -> !type.getVersion().isCurrent() && type.getVersion().equals(version))
				.map(type -> generateTypeVersioned(version, type));
	}

	private GeneratedClass generateTypeVersioned(ComputedVersion version, ComputedTypeBase typeBase) {
		ClassName upgraderClassName = typeBase.getJUpgraderName(basePackageName);
		ClassName typeBaseClassName = typeBase.getJTypeName(basePackageName);
		ComputedTypeBase nextTypeBase = dataModel.getNextVersion(typeBase);

		var classBuilder = TypeSpec.classBuilder(upgraderClassName.simpleName());

		classBuilder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		classBuilder.superclass(ParameterizedTypeName.get(ClassName.get(DataUpgraderSimple.class),
				typeBaseClassName,
				nextTypeBase.getJTypeName(basePackageName)
		));

		generateUpgradeMethod(version, typeBase, nextTypeBase, classBuilder);

		return new GeneratedClass(upgraderClassName.packageName(), classBuilder);
	}

	private void generateUpgradeMethod(ComputedVersion version, ComputedTypeBase typeBase,
			ComputedTypeBase nextTypeBase,
			Builder classBuilder) {
		var method = MethodSpec.methodBuilder("upgrade");

		method.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		ClassName typeBaseClassName = typeBase.getJTypeName(basePackageName);
		ClassName nextTypeBaseClassName = nextTypeBase.getJTypeName(basePackageName);
		method.returns(nextTypeBaseClassName);
		method.addAnnotation(NotNull.class);

		method.addParameter(ParameterSpec.builder(typeBaseClassName, "data").addAnnotation(NotNull.class).build());

		List<String> expectedResultFields = nextTypeBase.getData().keySet().stream().toList();

		AtomicInteger nextInitializerStaticFieldId = new AtomicInteger();
		HashMap<TypeLocationKey, String> initializerStaticFieldNames = new HashMap<>();
		HashMap<FieldLocationKey, ContextInfo> contextStaticFieldCodeBlocks = new HashMap<>();
		AtomicInteger nextUpgraderStaticFieldId = new AtomicInteger();
		HashMap<TypeLocationKey, String> upgraderStaticFieldNames = new HashMap<>();
		HashMap<String, ReadUpgradeApi> readUpgradeApis = new HashMap<>();
		HashMap<String, ReadInitializerApi> readInitializerApis = new HashMap<>();
		List<TransformationConfiguration> transformations = dataModel.getChanges(nextTypeBase);
		record ResultField(String name, ComputedType type, CodeBlock code) {}
		Stream<ResultField> resultFields;
		if (transformations.isEmpty()) {
			resultFields = typeBase
					.getData()
					.entrySet()
					.stream()
					.map(e -> new ResultField(e.getKey(), e.getValue(), fieldAccessor(e.getValue(), "data", e.getKey())));
		} else {
			record Field(String name, ComputedType type, CodeBlock code, int processFromTx) {}
			var fields = Stream.concat(
					typeBase.getData().entrySet().stream()
							.map(e -> new Field(e.getKey(), e.getValue(), fieldAccessor(e.getValue(), "data", e.getKey()), 0)),
					IntStream
							.range(0, transformations.size())
							.mapToObj(i -> Map.entry(i, transformations.get(i)))
							.filter(t -> t.getValue() instanceof NewDataConfiguration)
							.map(t -> Map.entry(t.getKey(), (NewDataConfiguration) t.getValue()))
							.map(e -> {
								var i = e.getKey();
								var newDataConfiguration = e.getValue();
							var computedTypes = dataModel.getComputedTypes(nextTypeBase.getVersion());
							var newFieldType = Objects.requireNonNull(computedTypes.get(DataModel.fixType(newDataConfiguration.type)));
							if (newDataConfiguration.hasReadTransform()
									&& newDataConfiguration.getReadTransform().isCustom()) {
								ComputedType readResultType = newDataConfiguration.hasReadTransformTypeOverride()
										? Objects.requireNonNull(dataModel.getComputedTypes(dataModel.getCurrentVersion())
												.get(DataModel.fixType(newDataConfiguration.getReadTransformType())))
										: newFieldType;
								createReadInitializerApi(typeBase, newDataConfiguration, readResultType,
										readInitializerApis, classBuilder);
							}
								var initializerLocation = newDataConfiguration.getInitializerLocation();

								var contextInfo = createContextStaticClass(typeBase, e.getValue().to,
										contextStaticFieldCodeBlocks,
										classBuilder,
										initializerLocation,
										newDataConfiguration.getContextParameters()
								);

								var genericInitializerClass = ParameterizedTypeName.get(ClassName.get(DataInitializer.class),
										contextInfo.typeName(),
										newFieldType.getJTypeName(basePackageName).box()
								);

								var initializerName = createInitializerStaticField(nextInitializerStaticFieldId,
										initializerStaticFieldNames,
										classBuilder,
										initializerLocation,
										genericInitializerClass
								);

								return new Field(newDataConfiguration.to, newFieldType, CodeBlock.of("$N.initialize($L)", initializerName, contextInfo.contextApply), i + 1);
							})
			);
			resultFields = fields.<ResultField>mapMulti((field, consumer) -> {
				String fieldName = field.name();
				ComputedType fieldType = field.type();
				CodeBlock codeBlock = field.code();
				for (TransformationConfiguration transformation : transformations.subList(field.processFromTx(),
						transformations.size()
				)) {
					if (transformation instanceof MoveDataConfiguration moveDataConfiguration) {
						if (!moveDataConfiguration.from.equals(fieldName)) {
							continue;
						}
						fieldName = moveDataConfiguration.to;
					} else if (transformation instanceof NewDataConfiguration newDataConfiguration) {
						if (newDataConfiguration.to.equals(fieldName)) {
							var type = dataModel.getComputedTypes(version).get(DataModel.fixType(newDataConfiguration.type));
							throw new IllegalStateException(
									"New field " + typeBase.getName() + "." + fieldName + " of type \"" + type + "\" at version \"" + nextTypeBase.getVersion()
											+ "\" conflicts with another field of type \"" + fieldType + "\" with the same name at version \""
											+ version + "\"!");
						}
						continue;
					} else if (transformation instanceof RemoveDataConfiguration removeDataConfiguration) {
						if (!removeDataConfiguration.from.equals(fieldName)) {
							continue;
						}
						fieldName = null;
						fieldType = null;
						return;
					} else if (transformation instanceof UpgradeDataConfiguration upgradeDataConfiguration) {
						if (!upgradeDataConfiguration.from.equals(fieldName)) {
							continue;
						}
						var upgraderImplementationLocation = upgradeDataConfiguration.getUpgraderLocation();
						var cb = CodeBlock.builder();
						var newFieldType = Objects
								.requireNonNull(dataModel.getComputedTypes(nextTypeBase.getVersion()).get(DataModel.fixType(upgradeDataConfiguration.type)));
						if (upgradeDataConfiguration.hasReadTransform()
								&& upgradeDataConfiguration.getReadTransform().isCustom()) {
							ComputedType readResultType = upgradeDataConfiguration.hasReadTransformTypeOverride()
									? Objects.requireNonNull(dataModel.getComputedTypes(dataModel.getCurrentVersion())
											.get(DataModel.fixType(upgradeDataConfiguration.getReadTransformType())))
									: newFieldType;
							createReadUpgradeApi(typeBase, upgradeDataConfiguration, fieldType, readResultType,
									readUpgradeApis, classBuilder);
						}

						var contextInfo = createContextStaticClass(typeBase, upgradeDataConfiguration.from,
								contextStaticFieldCodeBlocks,
								classBuilder,
								upgraderImplementationLocation,
								upgradeDataConfiguration.getContextParameters()
						);

						var genericUpgraderClass = ParameterizedTypeName.get(ClassName.get(DataUpgrader.class),
								contextInfo.typeName(),
								fieldType.getJTypeName(basePackageName).box(),
								newFieldType.getJTypeName(basePackageName).box()
						);

						var upgraderName = createUpgraderStaticField(nextUpgraderStaticFieldId,
								upgraderStaticFieldNames,
								classBuilder,
								upgraderImplementationLocation,
								genericUpgraderClass
						);

						cb.add("($T) $N.upgrade($L, ($T) (",
								newFieldType.getJTypeName(basePackageName),
								upgraderName,
								contextInfo.contextApply,
								fieldType.getJTypeName(basePackageName)
						);
						cb.add(codeBlock);
						cb.add("))");
						codeBlock = cb.build();
						fieldType = newFieldType;
					} else {
						throw	new UnsupportedOperationException("Unsupported transformation type: " + transformation);
					}
				}
				consumer.accept(new ResultField(fieldName, fieldType, codeBlock));
			}).sorted(Comparator.comparingInt(f -> expectedResultFields.indexOf(f.name())));
		}
		AtomicInteger currentField = new AtomicInteger();
		var resultFieldsList = resultFields.toList();
		record FinalField(String name, ComputedType type, CodeBlock code) {}
		List<FinalField> finalFields = resultFieldsList.stream().map(e -> {
					var currentFieldIndex = currentField.getAndIncrement();
					var currentFieldName = e.name();
					var expectedFieldIndex = expectedResultFields.indexOf(currentFieldName);
					if (expectedFieldIndex != currentFieldIndex) {
						var expectedFieldName = (currentFieldIndex >= 0 && expectedResultFields.size() > currentFieldIndex) ? expectedResultFields.get(currentFieldIndex) : "<?>";
						throw new IllegalStateException(
								"" + typeBase + " to " + nextTypeBase + ". Index " + currentFieldIndex + ". Expected " + expectedFieldName + ", got " + currentFieldName
										+ ".\n\tExpected: " + String.join(", ", expectedResultFields) + "\n\tResult: " + resultFieldsList
										.stream()
										.map(ResultField::name)
										.collect(Collectors.joining(", ")));
					}
					ComputedType targetType = nextTypeBase.getData().get(e.name());
					return new FinalField(e.name(), targetType,
							upgradeFieldToType(e.name(), e.type(), e.code(), nextTypeBase));
				}).toList();

		var nullableLocals = new HashMap<String, String>();
		for (FinalField field : finalFields) {
			if (field.type() instanceof ComputedTypeNullable) {
				String local = "nullable" + nullableLocals.size();
				nullableLocals.put(field.name(), local);
				method.addStatement("final $T $N = ($T) ($L)", field.type().getJTypeName(basePackageName), local,
						field.type().getJTypeName(basePackageName), field.code());
			}
		}
		method.addCode("return $T.unsafeOfOwned(\n$>", nextTypeBaseClassName);
		int argument = 0;
		for (FinalField field : finalFields) {
			if (argument++ != 0) method.addCode(",\n");
			if (field.type() instanceof ComputedTypeNullable nullable) {
				String local = nullableLocals.get(field.name());
				TypeName valueType = nullable.getBase().getJTypeName(basePackageName);
				if (valueType.isPrimitive()) {
					method.addCode("$N.getNullable() != null, $N.getNullable() != null ? $N.get() : $L",
							local, local, local, primitiveDefault(valueType));
				} else {
					method.addCode("($T) $N.getNullable()", valueType, local);
				}
			} else {
				method.addCode("$L", field.code());
			}
		}
		method.addCode("$<\n);\n");

		classBuilder.addMethod(method.build());
	}

	static String readInputInterfaceName(String fieldName) {
		return "ReadInput" + SourcesGenerator.capitalize(fieldName);
	}

	static String readUpgraderInterfaceName(String fieldName) {
		return "ReadUpgrader" + SourcesGenerator.capitalize(fieldName);
	}

	static String readInitializerInputInterfaceName(String fieldName) {
		return "ReadInitializerInput" + SourcesGenerator.capitalize(fieldName);
	}

	static String readInitializerInterfaceName(String fieldName) {
		return "ReadInitializer" + SourcesGenerator.capitalize(fieldName);
	}

	static String valueWireViewInterfaceName(String fieldName) {
		return "WireValue" + SourcesGenerator.capitalize(fieldName);
	}

	static String contextWireViewInterfaceName(String fieldName, String contextField) {
		return "WireContext" + SourcesGenerator.capitalize(fieldName)
				+ SourcesGenerator.capitalize(contextField);
	}

	static String wireArrayCursorInterfaceName(String viewName) {
		return viewName + "Cursor";
	}

	static String wireElementViewInterfaceName(String viewName) {
		return viewName + "Element";
	}

	static String wireRecordFieldViewInterfaceName(String viewName, String fieldName) {
		return viewName + "Field" + SourcesGenerator.capitalize(fieldName);
	}

	static String wireNullableValueViewInterfaceName(String viewName) {
		return viewName + "PresentValue";
	}

	static String wireUnionSubtypeViewInterfaceName(String viewName, String subtypeName) {
		return viewName + "Variant" + SourcesGenerator.capitalize(subtypeName);
	}

	private TypeName addWireViewInterface(ComputedTypeBase owner,
			ComputedType type,
			String viewName,
			Builder classBuilder) {
		return addWireViewInterface(owner, type, viewName, classBuilder, new java.util.IdentityHashMap<>());
	}

	private TypeName addWireViewInterface(ComputedTypeBase owner,
			ComputedType type,
			String viewName,
			Builder classBuilder,
			java.util.IdentityHashMap<ComputedType, String> ancestors) {
		if (!(type instanceof ComputedTypeBase || type instanceof ComputedTypeArray
				|| type instanceof ComputedTypeNullable || type instanceof ComputedTypeSuper)) {
			return null;
		}
		String ancestorName = ancestors.get(type);
		if (ancestorName != null) {
			return owner.getJUpgraderName(basePackageName).nestedClass(ancestorName);
		}
		ancestors.put(type, viewName);
		TypeSpec.Builder view = TypeSpec.interfaceBuilder(viewName)
				.addModifiers(Modifier.PUBLIC)
				.addJavadoc("Ephemeral zero-copy view of a bounded serialized {@code $L} value. "
						+ "The view is valid only during its custom transform call and must not be retained.\n", type);
		if (type instanceof ComputedTypeBase record) {
			for (var field : record.getData().entrySet()) {
				view.addMethod(MethodSpec.methodBuilder(field.getKey())
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(field.getValue().getJTypeName(basePackageName))
						.addJavadoc("Reads {@code $N} lazily and caches reference values for this binding.\n",
								field.getKey())
						.build());
				TypeName fieldViewType = addWireViewInterface(owner, field.getValue(),
						wireRecordFieldViewInterfaceName(viewName, field.getKey()), classBuilder, ancestors);
				if (fieldViewType != null) {
					view.addMethod(MethodSpec.methodBuilder(field.getKey() + "View")
							.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
							.returns(fieldViewType)
							.addJavadoc("Rebinds and returns the reader-owned structural view of {@code $N}.\n",
									field.getKey())
							.build());
				}
			}
		} else if (type instanceof ComputedTypeArray array) {
			TypeName elementViewType = addWireViewInterface(owner, array.getBase(),
					wireElementViewInterfaceName(viewName), classBuilder, ancestors);
			String cursorName = wireArrayCursorInterfaceName(viewName);
			TypeSpec.Builder cursor = TypeSpec.interfaceBuilder(cursorName)
					.addModifiers(Modifier.PUBLIC)
					.addJavadoc("Reusable sequential cursor for {@link $L}. Calling {@code cursor()} resets "
							+ "the single cursor owned by the wire view. It must not be retained.\n", viewName)
					.addMethod(MethodSpec.methodBuilder("hasNext")
							.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
							.returns(TypeName.BOOLEAN)
							.build())
					.addMethod(MethodSpec.methodBuilder("next")
							.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
							.returns(array.getBase().getJTypeName(basePackageName))
							.build());
			if (elementViewType != null) {
				cursor.addMethod(MethodSpec.methodBuilder("nextView")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(elementViewType)
						.addJavadoc("Rebinds and returns one reader-owned element view. A later call invalidates "
								+ "the previously returned binding.\n")
						.build());
			}
			classBuilder.addType(cursor.build());
			view.addMethod(MethodSpec.methodBuilder("size")
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.returns(TypeName.INT)
					.build());
			view.addMethod(MethodSpec.methodBuilder("get")
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.returns(array.getBase().getJTypeName(basePackageName))
					.addParameter(TypeName.INT, "index")
					.build());
			view.addMethod(MethodSpec.methodBuilder("copy")
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.returns(type.getJTypeName(basePackageName))
					.build());
			if (elementViewType != null) {
				view.addMethod(MethodSpec.methodBuilder("elementView")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(elementViewType)
						.addParameter(TypeName.INT, "index")
						.addJavadoc("Rebinds and returns one reader-owned element view. A later call invalidates "
								+ "the previously returned binding.\n")
						.build());
			}
			view.addMethod(MethodSpec.methodBuilder("cursor")
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.returns(owner.getJUpgraderName(basePackageName).nestedClass(cursorName))
					.addJavadoc("Resets and returns the reader-owned sequential cursor.\n")
					.build());
		} else if (type instanceof ComputedTypeNullable nullable) {
			view.addMethod(MethodSpec.methodBuilder("isPresent")
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.returns(TypeName.BOOLEAN)
					.build());
			view.addMethod(MethodSpec.methodBuilder("value")
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.returns(nullable.getBase().getJTypeName(basePackageName))
					.build());
			TypeName valueViewType = addWireViewInterface(owner, nullable.getBase(),
					wireNullableValueViewInterfaceName(viewName), classBuilder, ancestors);
			if (valueViewType != null) {
				view.addMethod(MethodSpec.methodBuilder("valueView")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(valueViewType)
						.addJavadoc("Returns the reader-owned structural view of the present value.\n")
						.build());
			}
		} else if (type instanceof ComputedTypeSuper union) {
			String kindName = viewName + "Kind";
			TypeSpec.Builder kind = TypeSpec.enumBuilder(kindName).addModifiers(Modifier.PUBLIC);
			for (ComputedType subtype : union.subTypes()) kind.addEnumConstant(subtype.getName());
			classBuilder.addType(kind.build());
			view.addMethod(MethodSpec.methodBuilder("kind")
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.returns(owner.getJUpgraderName(basePackageName).nestedClass(kindName))
					.build());
			for (ComputedType subtype : union.subTypes()) {
				view.addMethod(MethodSpec.methodBuilder("as" + SourcesGenerator.capitalize(subtype.getName()))
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(subtype.getJTypeName(basePackageName))
						.build());
				TypeName subtypeViewType = addWireViewInterface(owner, subtype,
						wireUnionSubtypeViewInterfaceName(viewName, subtype.getName()), classBuilder, ancestors);
				if (subtypeViewType != null) {
					view.addMethod(MethodSpec.methodBuilder("as" + SourcesGenerator.capitalize(subtype.getName())
								+ "View")
							.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
							.returns(subtypeViewType)
							.addJavadoc("Returns the reader-owned typed view of the active {@code $N} variant.\n",
									subtype.getName())
							.build());
				}
			}
		}
		ancestors.remove(type);
		classBuilder.addType(view.build());
		return owner.getJUpgraderName(basePackageName).nestedClass(viewName);
	}

	private ReadInitializerApi createReadInitializerApi(ComputedTypeBase owner,
			NewDataConfiguration initializer,
			ComputedType resultType,
			Map<String, ReadInitializerApi> existingApis,
			Builder classBuilder) {
		String inputName = readInitializerInputInterfaceName(initializer.to);
		String initializerName = readInitializerInterfaceName(initializer.to);
		var signature = new ReadInitializerApi(inputName, initializerName, resultType,
				List.copyOf(initializer.getContextParameters()));
		ReadInitializerApi previous = existingApis.putIfAbsent(inputName, signature);
		if (previous != null) {
			if (!previous.equals(signature)) {
				throw new IllegalArgumentException("Conflicting optimized read initializers for "
						+ owner.getName() + "." + initializer.to);
			}
			return previous;
		}

		TypeSpec.Builder input = TypeSpec.interfaceBuilder(inputName)
				.addModifiers(Modifier.PUBLIC)
				.addJavadoc("Ephemeral, invocation-scoped input for the optimized {@code $N} initializer. "
						+ "Implementations must not retain this input.\n", initializer.to);
		for (String contextParameter : initializer.getContextParameters()) {
			ComputedType contextType = owner.getData().get(contextParameter);
			if (contextType == null) {
				throw new IllegalArgumentException("Unknown optimized read-initializer context field "
						+ owner.getName() + "." + contextParameter);
			}
			input.addMethod(MethodSpec.methodBuilder("context" + SourcesGenerator.capitalize(contextParameter))
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.returns(contextType.getJTypeName(basePackageName))
					.addJavadoc("Returns {@code $N} lazily at most once.\n", contextParameter)
					.build());
			ComputedType currentContextType = currentRepresentation(contextType);
			if (currentContextType != null) {
				input.addMethod(MethodSpec.methodBuilder("currentContext"
						+ SourcesGenerator.capitalize(contextParameter))
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(currentContextType.getJTypeName(basePackageName))
						.addJavadoc("Reads {@code $N} lazily in its current structural representation.\n",
								contextParameter)
						.build());
			}
			String viewName = contextWireViewInterfaceName(initializer.to, contextParameter);
			TypeName viewType = addWireViewInterface(owner, contextType, viewName, classBuilder);
			if (viewType != null) {
				input.addMethod(MethodSpec.methodBuilder("hasContext"
							+ SourcesGenerator.capitalize(contextParameter) + "View")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(TypeName.BOOLEAN)
						.build());
				input.addMethod(MethodSpec.methodBuilder("context"
							+ SourcesGenerator.capitalize(contextParameter) + "View")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(viewType)
						.build());
			}
		}
		classBuilder.addType(input.build());

		ClassName ownerClass = owner.getJUpgraderName(basePackageName);
		classBuilder.addType(TypeSpec.interfaceBuilder(initializerName)
				.addAnnotation(FunctionalInterface.class)
				.addModifiers(Modifier.PUBLIC)
				.addJavadoc("Allocation-minimal serialized-data initializer for {@code $N}.\n", initializer.to)
				.addMethod(MethodSpec.methodBuilder("initialize")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(resultType.getJTypeName(basePackageName))
						.addParameter(ownerClass.nestedClass(inputName), "input")
						.build())
				.build());
		return signature;
	}

	private ReadUpgradeApi createReadUpgradeApi(ComputedTypeBase owner,
			UpgradeDataConfiguration upgrade,
			ComputedType oldType,
			ComputedType newType,
			Map<String, ReadUpgradeApi> existingApis,
			Builder classBuilder) {
		String inputName = readInputInterfaceName(upgrade.from);
		String upgraderName = readUpgraderInterfaceName(upgrade.from);
		ComputedType currentValueType = currentRepresentation(oldType);
		var signature = new ReadUpgradeApi(inputName, upgraderName, oldType, currentValueType, newType,
				List.copyOf(upgrade.getContextParameters()));
		ReadUpgradeApi previous = existingApis.putIfAbsent(inputName, signature);
		if (previous != null) {
			if (!previous.equals(signature)) {
				throw new IllegalArgumentException("Conflicting optimized read upgrades for " + owner.getName()
						+ "." + upgrade.from);
			}
			return previous;
		}

		TypeSpec.Builder input = TypeSpec.interfaceBuilder(inputName)
				.addModifiers(Modifier.PUBLIC)
				.addJavadoc("Ephemeral, invocation-scoped input for the optimized {@code $N} upgrade. "
						+ "Implementations must not retain this input or its serialized cursor.\n", upgrade.from)
				.addMethod(MethodSpec.methodBuilder("value")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(oldType.getJTypeName(basePackageName))
						.addJavadoc("Returns the logical pre-upgrade value, materializing it lazily at most once.\n")
						.build());
		String valueViewName = valueWireViewInterfaceName(upgrade.from);
		TypeName valueViewType = addWireViewInterface(owner, oldType, valueViewName, classBuilder);
		if (valueViewType != null) {
			input.addMethod(MethodSpec.methodBuilder("hasValueView")
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.returns(TypeName.BOOLEAN)
					.build());
			input.addMethod(MethodSpec.methodBuilder("valueView")
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.returns(valueViewType)
					.build());
		}
		if (currentValueType != null) {
			input.addMethod(MethodSpec.methodBuilder("currentValue")
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.returns(currentValueType.getJTypeName(basePackageName))
					.addJavadoc("Reads the same logical value directly in its current structural representation, "
							+ "without constructing historical containers. It is mutually exclusive with "
							+ "{@link #value()} and {@link #serializedValue()}.\n")
					.build());
		}
		input
				.addMethod(MethodSpec.methodBuilder("hasSerializedValue")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(TypeName.BOOLEAN)
						.addJavadoc("Whether the original bounded field bytes are available.\n")
						.build())
				.addMethod(MethodSpec.methodBuilder("serializedVersion")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(TypeName.INT)
						.addJavadoc("Returns the version of the available serialized field bytes.\n")
						.build())
				.addMethod(MethodSpec.methodBuilder("serializedValue")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(SafeDataInput.class)
						.addJavadoc("Returns a bounded zero-copy cursor over the original field. It must be fully "
								+ "consumed during the upgrade call.\n")
						.build());
		for (String contextParameter : upgrade.getContextParameters()) {
			ComputedType contextType = owner.getData().get(contextParameter);
			if (contextType == null) {
				throw new IllegalArgumentException("Unknown optimized read-upgrade context field "
						+ owner.getName() + "." + contextParameter);
			}
			input.addMethod(MethodSpec.methodBuilder("context" + SourcesGenerator.capitalize(contextParameter))
					.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
					.returns(contextType.getJTypeName(basePackageName))
					.addJavadoc("Returns {@code $N} lazily at most once.\n", contextParameter)
					.build());
			ComputedType currentContextType = currentRepresentation(contextType);
			if (currentContextType != null) {
				input.addMethod(MethodSpec.methodBuilder("currentContext"
						+ SourcesGenerator.capitalize(contextParameter))
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(currentContextType.getJTypeName(basePackageName))
						.addJavadoc("Reads {@code $N} lazily in its current structural representation, "
								+ "without historical containers.\n", contextParameter)
						.build());
			}
			String viewName = contextWireViewInterfaceName(upgrade.from, contextParameter);
			TypeName viewType = addWireViewInterface(owner, contextType, viewName, classBuilder);
			if (viewType != null) {
				input.addMethod(MethodSpec.methodBuilder("hasContext"
							+ SourcesGenerator.capitalize(contextParameter) + "View")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(TypeName.BOOLEAN)
						.build());
				input.addMethod(MethodSpec.methodBuilder("context"
							+ SourcesGenerator.capitalize(contextParameter) + "View")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(viewType)
						.build());
			}
		}
		classBuilder.addType(input.build());

		ClassName ownerClass = owner.getJUpgraderName(basePackageName);
		TypeSpec readUpgrader = TypeSpec.interfaceBuilder(upgraderName)
				.addAnnotation(FunctionalInterface.class)
				.addModifiers(Modifier.PUBLIC)
				.addJavadoc("Allocation-minimal serialized-data upgrade for {@code $N}.\n", upgrade.from)
				.addMethod(MethodSpec.methodBuilder("upgrade")
						.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
						.returns(newType.getJTypeName(basePackageName))
						.addParameter(ownerClass.nestedClass(inputName), "input")
						.build())
				.build();
		classBuilder.addType(readUpgrader);
		return signature;
	}

	private ComputedType currentRepresentation(ComputedType oldType) {
		return dataModel.getCurrentStructuralRepresentation(oldType);
	}

	private record ReadUpgradeApi(String inputName,
			String upgraderName,
			ComputedType oldType,
			ComputedType currentValueType,
			ComputedType newType,
			List<String> contextParameters) {}

	private record ReadInitializerApi(String inputName,
			String initializerName,
			ComputedType resultType,
			List<String> contextParameters) {}

	private String createInitializerStaticField(AtomicInteger nextInitializerStaticFieldId,
												HashMap<TypeLocationKey, String> initializerStaticFieldNames,
												Builder classBuilder,
												JInterfaceLocation initializerLocation,
												TypeName genericInitializerClass) {
		var identifier = new TypeLocationKey(initializerLocation.getIdentifier(), genericInitializerClass);
		var initializerName = initializerStaticFieldNames.get(identifier);
		if (initializerName == null) {
			initializerName = "I" + nextInitializerStaticFieldId.getAndIncrement();
			var fieldBuilder = FieldSpec
					.builder(genericInitializerClass, initializerName)
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
			switch (initializerLocation) {
				case JInterfaceLocationClassName className -> fieldBuilder.initializer("new $T()", className.className());
				case JInterfaceLocationInstanceField instanceField -> fieldBuilder.initializer("$T.$N",
						instanceField.fieldLocation().className(),
						instanceField.fieldLocation().fieldName()
				);
			}
			classBuilder.addField(fieldBuilder.build());
			initializerStaticFieldNames.put(identifier, initializerName);
		}
		return initializerName;
	}

	record ContextInfo(TypeName typeName, CodeBlock contextApply) {}
	record TypeLocationKey(String identifier, TypeName typeName) {}
	record FieldLocationKey(String identifier, String fieldName) {}

	private ContextInfo createContextStaticClass(ComputedTypeBase typeBase,
			String fieldName,
											   HashMap<FieldLocationKey, ContextInfo> contextStaticFieldCodeBlocks,
											   Builder classBuilder,
											   JInterfaceLocation initializerLocation,
											   @NotNull List<String> contextParameters) {
		var identifier = new FieldLocationKey(initializerLocation.getIdentifier(), fieldName);
		var contextStaticFieldCodeBlock = contextStaticFieldCodeBlocks.get(identifier);
		if (contextStaticFieldCodeBlock == null) {
			var codeBlockBuilder = CodeBlock.builder();
			TypeName typeName;

			if (contextParameters.isEmpty()) {
				typeName = ClassName.get(DataContextNone.class);
				codeBlockBuilder.add("$T.INSTANCE", typeName);
			} else {
				var name = "Context" + SourcesGenerator.capitalize(fieldName);
				var contextTypeClassBuilder = TypeSpec.recordBuilder(name)
						.addSuperinterface(ClassName.get(DataContext.class))
						.addModifiers(Modifier.PUBLIC, Modifier.STATIC);
				typeName = typeBase.getJUpgraderName(basePackageName).nestedClass(name);

				codeBlockBuilder.add("new $T(", typeName);
				boolean first = true;
				var contextTypeClassConstructorBuilder = MethodSpec.constructorBuilder();
				for (String contextParameter : contextParameters) {
					var fieldType = typeBase.getData().get(contextParameter);
					contextTypeClassConstructorBuilder.addParameter(ParameterSpec.builder(fieldType.getJTypeNameGeneric(basePackageName), contextParameter).build());

					if (first) {
						first = false;
					} else {
						codeBlockBuilder.add(", ");
					}
					codeBlockBuilder.add("$L", fieldAccessor(fieldType, "data", contextParameter));
				}
				contextTypeClassBuilder.recordConstructor(contextTypeClassConstructorBuilder.build());
				codeBlockBuilder.add(")");

				var clazz = contextTypeClassBuilder.build();
				classBuilder.addType(clazz);
			}

			contextStaticFieldCodeBlock = new ContextInfo(typeName, codeBlockBuilder.build());
			contextStaticFieldCodeBlocks.put(identifier, contextStaticFieldCodeBlock);
		}
		return contextStaticFieldCodeBlock;
	}

	private String createUpgraderStaticField(AtomicInteger nextUpgraderStaticFieldId,
			HashMap<TypeLocationKey, String> upgraderStaticFieldNames,
			Builder classBuilder,
			JInterfaceLocation upgraderLocation,
			TypeName genericUpgraderClass) {
		var identifier = new TypeLocationKey(upgraderLocation.getIdentifier(), genericUpgraderClass);
		var upgraderName = upgraderStaticFieldNames.get(identifier);
		if (upgraderName == null) {
			upgraderName = "U" + nextUpgraderStaticFieldId.getAndIncrement();
			var fieldBuilder = FieldSpec
					.builder(genericUpgraderClass, upgraderName)
					.addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
			switch (upgraderLocation) {
				case JInterfaceLocationClassName className -> fieldBuilder.initializer("new $T()", className.className());
				case JInterfaceLocationInstanceField instanceField -> fieldBuilder.initializer("$T.$N",
						instanceField.fieldLocation().className(),
						instanceField.fieldLocation().fieldName()
				);
			}
			classBuilder.addField(fieldBuilder.build());
			upgraderStaticFieldNames.put(identifier, upgraderName);
		}
		return upgraderName;
	}

	private CodeBlock upgradeFieldToType(String fieldName,
			ComputedType fieldType,
			CodeBlock codeBlock,
			ComputedTypeBase nextTypeBase) {
		while (fieldType instanceof VersionedComputedType versionedComputedType
				&& versionedComputedType.getVersion().compareTo(nextTypeBase.getVersion()) < 0) {
			var currentFieldType = fieldType;
			var nextFieldType = dataModel.getNextVersion(currentFieldType);
			codeBlock = currentFieldType.wrapWithUpgrade(basePackageName, codeBlock, nextFieldType);
			fieldType = nextFieldType;
		}
		return codeBlock;
	}

	private CodeBlock fieldAccessor(ComputedType fieldType, String owner, String fieldName) {
		if (fieldType instanceof ComputedTypeArray) {
			return CodeBlock.of("$N.$NUnsafeArray()", owner, fieldName);
		}
		if (fieldType instanceof ComputedTypeNullable) {
			return CodeBlock.of("$N.has$N() ? $T.of($N.$N()) : $T.empty()", owner,
					SourcesGenerator.capitalize(fieldName), fieldType.getJTypeName(basePackageName), owner,
					fieldName, fieldType.getJTypeName(basePackageName));
		}
		return CodeBlock.of("$N.$N()", owner, fieldName);
	}

	private static CodeBlock primitiveDefault(TypeName type) {
		return switch (type.toString()) {
			case "boolean" -> CodeBlock.of("false");
			case "byte" -> CodeBlock.of("(byte) 0");
			case "short" -> CodeBlock.of("(short) 0");
			case "char" -> CodeBlock.of("(char) 0");
			case "int" -> CodeBlock.of("0");
			case "long" -> CodeBlock.of("0L");
			case "float" -> CodeBlock.of("0.0f");
			case "double" -> CodeBlock.of("0.0d");
			default -> throw new IllegalArgumentException("Not primitive: " + type);
		};
	}
}
