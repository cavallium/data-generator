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
import it.cavallium.datagen.plugin.ComputedTypeBase;
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
		HashMap<String, String> initializerStaticFieldNames = new HashMap<>();
		HashMap<String, ContextInfo> contextStaticFieldCodeBlocks = new HashMap<>();
		AtomicInteger nextUpgraderStaticFieldId = new AtomicInteger();
		HashMap<String, String> upgraderStaticFieldNames = new HashMap<>();
		List<TransformationConfiguration> transformations = dataModel.getChanges(nextTypeBase);
		method.addCode("return new $T(\n$>", nextTypeBaseClassName);
		record ResultField(String name, ComputedType type, CodeBlock code) {}
		Stream<ResultField> resultFields;
		if (transformations.isEmpty()) {
			resultFields = typeBase
					.getData()
					.entrySet()
					.stream()
					.map(e -> new ResultField(e.getKey(), e.getValue(), CodeBlock.of("data.$N()", e.getKey())));
		} else {
			record Field(String name, ComputedType type, CodeBlock code, int processFromTx) {}
			var fields = Stream.concat(
					typeBase.getData().entrySet().stream()
							.map(e -> new Field(e.getKey(), e.getValue(), CodeBlock.of("data.$N()", e.getKey()), 0)),
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

						cb.add("($T) $N.upgrade($L, ($T) ",
								newFieldType.getJTypeName(basePackageName),
								upgraderName,
								contextInfo.contextApply,
								fieldType.getJTypeName(basePackageName)
						);
						cb.add(codeBlock);
						cb.add(")");
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
		resultFieldsList.stream().flatMap(e -> {
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
					return Stream.of(CodeBlock.of(",\n"), upgradeFieldToType(e.name(), e.type(), e.code(), nextTypeBase));
				})
				.skip(1)
				.forEach(method::addCode);
		method.addCode("\n$<);\n");

		classBuilder.addMethod(method.build());
	}

	private String createInitializerStaticField(AtomicInteger nextInitializerStaticFieldId,
												HashMap<String, String> initializerStaticFieldNames,
												Builder classBuilder,
												JInterfaceLocation initializerLocation,
												TypeName genericInitializerClass) {
		var identifier = initializerLocation.getIdentifier();
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

	private ContextInfo createContextStaticClass(ComputedTypeBase typeBase,
			String fieldName,
											   HashMap<String, ContextInfo> contextStaticFieldCodeBlocks,
											   Builder classBuilder,
											   JInterfaceLocation initializerLocation,
											   @NotNull List<String> contextParameters) {
		var identifier = initializerLocation.getIdentifier();
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
					codeBlockBuilder.add("data.$N()", contextParameter);
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
			HashMap<String, String> upgraderStaticFieldNames,
			Builder classBuilder,
			JInterfaceLocation upgraderLocation,
			TypeName genericUpgraderClass) {
		var identifier = upgraderLocation.getIdentifier();
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
}
