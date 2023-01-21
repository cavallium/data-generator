package it.cavallium.data.generator.plugin.classgen;

import static it.cavallium.data.generator.plugin.DataModel.fixType;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import it.cavallium.data.generator.DataUpgrader;
import it.cavallium.data.generator.plugin.ClassGenerator;
import it.cavallium.data.generator.plugin.ComputedType;
import it.cavallium.data.generator.plugin.ComputedType.VersionedComputedType;
import it.cavallium.data.generator.plugin.ComputedTypeBase;
import it.cavallium.data.generator.plugin.ComputedVersion;
import it.cavallium.data.generator.plugin.MoveDataConfiguration;
import it.cavallium.data.generator.plugin.NewDataConfiguration;
import it.cavallium.data.generator.plugin.RemoveDataConfiguration;
import it.cavallium.data.generator.plugin.TransformationConfiguration;
import it.cavallium.data.generator.plugin.UpgradeDataConfiguration;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
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

		classBuilder.addSuperinterface(ParameterizedTypeName.get(ClassName.get(DataUpgrader.class),
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
		method.addException(IOException.class);

		ClassName typeBaseClassName = typeBase.getJTypeName(basePackageName);
		ClassName nextTypeBaseClassName = nextTypeBase.getJTypeName(basePackageName);
		method.returns(nextTypeBaseClassName);
		method.addAnnotation(NotNull.class);

		method.addParameter(ParameterSpec.builder(typeBaseClassName, "data").addAnnotation(NotNull.class).build());

		nextTypeBase.getData().forEach((fieldName, fieldType) -> {
			method.addStatement("$T $N", fieldType.getJTypeName(basePackageName), fieldName);
		});

		List<String> expectedResultFields = nextTypeBase.getData().keySet().stream().toList();

		AtomicInteger nextInitializerStaticFieldId = new AtomicInteger();
		HashMap<String, String> initializerStaticFieldNames = new HashMap<>();
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
								var computedTypes = dataModel.getComputedTypes(version);
								var newFieldType = Objects.requireNonNull(computedTypes.get(fixType(newDataConfiguration.type)));
								var initializerClass = ClassName.bestGuess(newDataConfiguration.initializer);

								var initializerName = createInitializerStaticField(nextInitializerStaticFieldId,
										initializerStaticFieldNames,
										classBuilder,
										initializerClass
								);

								return new Field(newDataConfiguration.to, newFieldType, CodeBlock.of("$N", initializerName), i);
							})
			);
			resultFields = fields.<ResultField>mapMulti((field, consumer) -> {
				String fieldName = field.name();
				ComputedType fieldType = field.type();
				CodeBlock codeBlock = CodeBlock.of("data.$N()", fieldName);
				for (TransformationConfiguration transformation : transformations.subList(field.processFromTx(),
						transformations.size()
				)) {
					if (transformation instanceof MoveDataConfiguration moveDataConfiguration) {
						if (!moveDataConfiguration.from.equals(fieldName)) {
							continue;
						}
						fieldName = moveDataConfiguration.to;
					} else if (transformation instanceof NewDataConfiguration newDataConfiguration) {
						continue;
					} else if (transformation instanceof RemoveDataConfiguration removeDataConfiguration) {
						if (!removeDataConfiguration.from.equals(fieldName)) {
							continue;
						}
						return;
					} else if (transformation instanceof UpgradeDataConfiguration upgradeDataConfiguration) {
						if (!upgradeDataConfiguration.from.equals(fieldName)) {
							continue;
						}
						var upgraderClass = ClassName.bestGuess(upgradeDataConfiguration.upgrader);
						var cb = CodeBlock.builder();
						var newFieldType = Objects
								.requireNonNull(dataModel.getComputedTypes(version).get(fixType(upgradeDataConfiguration.type)));
						cb.add("($T) $T.upgrade(($T) ",
								newFieldType.getJTypeName(basePackageName),
								upgraderClass,
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
		resultFields
				.flatMap(e -> Stream.of(CodeBlock.of(",\n"), upgradeFieldToType(e.name(), e.type(), e.code(), nextTypeBase)))
				.skip(1)
				.forEach(method::addCode);
		method.addCode("\n$<);\n");

		classBuilder.addMethod(method.build());
	}

	private String createInitializerStaticField(AtomicInteger nextInitializerStaticFieldId,
			HashMap<String, String> initializerStaticFieldNames,
			Builder classBuilder,
			ClassName initializerClass) {
		var ref = initializerClass.reflectionName();
		var initializerName = initializerStaticFieldNames.get(ref);
		if (initializerName == null) {
			initializerName = "I" + nextInitializerStaticFieldId.getAndIncrement();
			classBuilder.addField(FieldSpec.builder(initializerClass, initializerName).initializer("new $T()", initializerClass).build());
			initializerStaticFieldNames.put(ref, initializerName);
		}
		return initializerName;
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

	private String getFieldVarName(int totalTransformations, int transformationId) {
		return null;
	}
}
