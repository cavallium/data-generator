package it.cavallium.data.generator.plugin;

import static java.nio.file.StandardOpenOption.*;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;
import io.soabase.recordbuilder.core.RecordBuilder;
import it.cavallium.data.generator.CommonField;
import it.cavallium.data.generator.DataInitializer;
import it.cavallium.data.generator.DataSerializer;
import it.cavallium.data.generator.DataUpgrader;
import it.cavallium.data.generator.nativedata.INullable;
import it.cavallium.data.generator.nativedata.Int52Serializer;
import it.cavallium.data.generator.nativedata.StringSerializer;
import it.unimi.dsi.fastutil.booleans.BooleanList;
import it.unimi.dsi.fastutil.bytes.ByteList;
import it.unimi.dsi.fastutil.chars.CharList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.floats.FloatList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

@SuppressWarnings({"SameParameterValue", "unused"})
public class SourcesGenerator {

	private static final Logger logger = LoggerFactory.getLogger(SourcesGenerator.class);
	private static final boolean OVERRIDE_ALL_NULLABLE_METHODS = false;

	private final DataModel dataModel;

	private SourcesGenerator(InputStream yamlDataStream) {
		Yaml yaml = new Yaml();
		var configuration = yaml.loadAs(yamlDataStream, SourcesGeneratorConfiguration.class);
		this.dataModel = configuration.buildDataModel();
	}

	public static SourcesGenerator load(InputStream yamlData) {
		return new SourcesGenerator(yamlData);
	}

	public static SourcesGenerator load(Path yamlPath) throws IOException {
		try (InputStream in = Files.newInputStream(yamlPath)) {
			return new SourcesGenerator(in);
		}
	}

	public static SourcesGenerator load(File yamlPath) throws IOException {
		try (InputStream in = Files.newInputStream(yamlPath.toPath())) {
			return new SourcesGenerator(in);
		}
	}

	/**
	 * @param basePackageName org.example
	 * @param outPath         path/to/output
	 * @param useRecordBuilders if true, the data will have @RecordBuilder annotation
	 */
	public void generateSources(String basePackageName, Path outPath, boolean useRecordBuilders) throws IOException {
		Path basePackageNamePath;
		{
			Path basePackageNamePathPartial = outPath;
			for (String part : basePackageName.split("\\.")) {
				basePackageNamePathPartial = basePackageNamePathPartial.resolve(part);
			}
			basePackageNamePath = basePackageNamePathPartial;
		}
		var hashPath = basePackageNamePath.resolve(".hash");
		var curHash = dataModel.computeHash();
		if (Files.isRegularFile(hashPath) && Files.isReadable(hashPath)) {
			var lines = Files.readAllLines(hashPath, StandardCharsets.UTF_8);
			if (lines.size() >= 3) {
				var prevBasePackageName = lines.get(0);
				var prevRecordBuilders = lines.get(1);
				var prevHash = lines.get(2);

				if (prevBasePackageName.equals(basePackageName) && (prevRecordBuilders.equalsIgnoreCase("true") == useRecordBuilders)
				 && prevHash.equals(curHash)) {
					logger.info("Skipped sources generation because it didn't change");
					return;
				}
			}
		}

		// Create the base dir
		if (Files.notExists(outPath)) {
			Files.createDirectories(outPath);
		}
		if (Files.notExists(basePackageNamePath)) {
			Files.createDirectories(basePackageNamePath);
		}

		// Get the files list
		HashSet<Path> generatedFilesToDelete;
		try (var stream = Files.find(outPath, Integer.MAX_VALUE, (filePath, fileAttr) -> fileAttr.isRegularFile())) {
			var relativeBasePackageNamePath = outPath.relativize(basePackageNamePath);
			generatedFilesToDelete = stream
					.map(outPath::relativize)
					.filter(path -> path.startsWith(relativeBasePackageNamePath))
					.collect(Collectors.toCollection(HashSet::new));
		}

		// Update the hash
		Files.writeString(hashPath, basePackageName + '\n' + useRecordBuilders + '\n' + curHash + '\n',
				StandardCharsets.UTF_8, TRUNCATE_EXISTING, WRITE, CREATE);
		markFileAsCreated(generatedFilesToDelete, outPath, hashPath);

		// Create the Versions class
		var versionsClass = TypeSpec.classBuilder("Versions");
		versionsClass.addModifiers(Modifier.PUBLIC);
		versionsClass.addModifiers(Modifier.FINAL);
		var versionsInstances = FieldSpec.builder(ArrayTypeName.of(ClassName.get(joinPackage(basePackageName, ""), "IVersion")),
				"VERSIONS",
				Modifier.PUBLIC,
				Modifier.STATIC,
				Modifier.FINAL
		);
		List<CodeBlock> versionsInstancesValue = new ArrayList<>();
		for (ComputedVersion value : dataModel.getVersionsSet()) {
			// Add a static variable for this version, containing the normalized version number
			var versionNumberField = FieldSpec
					.builder(TypeName.INT, getVersionVarName(value))
					.addModifiers(Modifier.PUBLIC)
					.addModifiers(Modifier.STATIC)
					.addModifiers(Modifier.FINAL)
					.initializer(getVersionShortInt(value))
					.build();
			// Add the fields to the class
			versionsClass.addField(versionNumberField);

			var versionPackage = value.getPackage(basePackageName);
			var versionClassType = ClassName.get(joinPackage(versionPackage, ""), "Version");

			versionsInstancesValue.add(CodeBlock.builder().add("$T.INSTANCE", versionClassType).build());
		}
		versionsInstances.initializer(CodeBlock
				.builder()
				.add("{\n")
				.add(CodeBlock.join(versionsInstancesValue, ",\n"))
				.add("\n}")
				.build());
		versionsClass.addField(versionsInstances.build());
		// Save the resulting class in the main package
		writeClass(generatedFilesToDelete, outPath, joinPackage(basePackageName, ""), versionsClass);

		// Create the BaseType class
		{
			var baseTypeClass = TypeSpec.enumBuilder("BaseType");
			baseTypeClass.addModifiers(Modifier.PUBLIC);
			for (var value : dataModel.getVersionsSet()) {
				for (String baseTypeName : value.getClassMap().keySet()) {
					if (!baseTypeClass.enumConstants.containsKey(baseTypeName)) {
						baseTypeClass.addEnumConstant(baseTypeName);
					}
				}
			}
			// Save the resulting class in the main package
			writeClass(generatedFilesToDelete, outPath, joinPackage(basePackageName, ""), baseTypeClass);
		}

		// Create the SuperType class
		{
			var superTypeClass = TypeSpec.enumBuilder("SuperType");
			superTypeClass.addModifiers(Modifier.PUBLIC);
			for (var value : dataModel.getVersionsSet()) {
				for (String superTypeName : dataModel.getSuperTypes().keySet()) {
					if (!superTypeClass.enumConstants.containsKey(superTypeName)) {
						superTypeClass.addEnumConstant(superTypeName);
					}
				}
			}
			// Save the resulting class in the main package
			writeClass(generatedFilesToDelete, outPath, joinPackage(basePackageName, ""), superTypeClass);
		}

		// Create the IVersion class
		{
			var iVersionClass = TypeSpec.interfaceBuilder("IVersion");
			iVersionClass.addModifiers(Modifier.PUBLIC);
			iVersionClass.addTypeVariable(TypeVariableName.get("B"));

			// Add getClass method
			{
				var getClassMethodBuilder = MethodSpec
						.methodBuilder("getClass")
						.addModifiers(Modifier.PUBLIC)
						.addModifiers(Modifier.ABSTRACT)
						.returns(ParameterizedTypeName.get(ClassName.get(Class.class),
								WildcardTypeName.subtypeOf(TypeVariableName.get("B"))
						))
						.addParameter(ParameterSpec
								.builder(ClassName.get(joinPackage(basePackageName, ""), "BaseType"), "type")
								.build());
				iVersionClass.addMethod(getClassMethodBuilder.build());
			}

			// Add getSerializer method
			{
				var getSerializerMethodBuilder = MethodSpec
						.methodBuilder("getSerializer")
						.addModifiers(Modifier.PUBLIC)
						.addModifiers(Modifier.ABSTRACT)
						.addTypeVariable(TypeVariableName.get("T",
								TypeVariableName.get("B")
						))
						.returns(ParameterizedTypeName.get(ClassName.get(DataSerializer.class), TypeVariableName.get("T")))
						.addException(IOException.class)
						.addParameter(ParameterSpec
								.builder(ClassName.get(joinPackage(basePackageName, ""), "BaseType"), "type")
								.build());
				iVersionClass.addMethod(getSerializerMethodBuilder.build());
			}

			// Add getVersion method
			{
				var getVersionMethod = MethodSpec
						.methodBuilder("getVersion")
						.addModifiers(Modifier.PUBLIC)
						.addModifiers(Modifier.ABSTRACT)
						.returns(TypeName.INT)
						.build();
				iVersionClass.addMethod(getVersionMethod);
			}

			// Save the resulting class in the main package
			writeClass(generatedFilesToDelete, outPath, joinPackage(basePackageName, ""), iVersionClass);
		}

		var currentVersionPackage = dataModel.getCurrentVersion().getPackage(basePackageName);

		// Create the CurrentVersion class
		{
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
			// Get super type classes method
			{
				var getSuperTypeClasses = MethodSpec.methodBuilder("getSuperTypeClasses").addModifiers(Modifier.PUBLIC)
						.addModifiers(Modifier.FINAL).addModifiers(Modifier.STATIC).returns(ParameterizedTypeName.get(
								ClassName.get(Set.class), ParameterizedTypeName.get(
										ClassName.get(Class.class), WildcardTypeName.subtypeOf(ClassName
												.get(joinPackage(currentVersionPackage, "data"),
														"IType")))))
						.addCode("return $T.of(\n", Set.class);
				AtomicBoolean isFirst = new AtomicBoolean(true);
				for (Entry<String, Set<String>> entry : dataModel.getSuperTypes().entrySet()) {
					String superTypeName = entry.getKey();
					Set<String> superTypeConfig = entry.getValue();
					if (!isFirst.getAndSet(false)) {
						getSuperTypeClasses.addCode(",\n");
					}
					getSuperTypeClasses.addCode("$T.class",
							ClassName.get(joinPackage(currentVersionPackage, "data"), superTypeName)
					);
				}
				getSuperTypeClasses.addCode("\n);");
				currentVersionClass.addMethod(getSuperTypeClasses.build());
			}
			// Get super type subtypes classes method
			{
				var getSuperTypeSubtypesClasses = MethodSpec.methodBuilder("getSuperTypeSubtypesClasses").addModifiers(Modifier.PUBLIC)
						.addModifiers(Modifier.FINAL).addModifiers(Modifier.STATIC).returns(ParameterizedTypeName.get(
								ClassName.get(Set.class), ParameterizedTypeName.get(
										ClassName.get(Class.class), WildcardTypeName.subtypeOf(ClassName
												.get(joinPackage(currentVersionPackage, "data"),
														"IBaseType")))));
				getSuperTypeSubtypesClasses.addParameter(ParameterSpec.builder(ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(ClassName
						.get(joinPackage(currentVersionPackage, "data"),
								"IType"))), "superTypeClass").build());
				getSuperTypeSubtypesClasses.beginControlFlow("switch (superTypeClass.getCanonicalName())");
				for (Entry<String, Set<String>> entry : dataModel.getSuperTypes().entrySet()) {
					String superTypeName = entry.getKey();
					Set<String> subTypes = entry.getValue();
					getSuperTypeSubtypesClasses.beginControlFlow("case \"" + ClassName
							.get(joinPackage(currentVersionPackage, "data"), superTypeName)
							.canonicalName() + "\":");
					getSuperTypeSubtypesClasses.addCode("return $T.of(\n", Set.class);
					AtomicBoolean isFirst = new AtomicBoolean(true);
					for (String subTypeName : subTypes) {
						if (!isFirst.getAndSet(false)) {
							getSuperTypeSubtypesClasses.addCode(",\n");
						}
						getSuperTypeSubtypesClasses.addCode("$T.class",
								ClassName.get(joinPackage(currentVersionPackage, "data"), subTypeName)
						);
					}
					getSuperTypeSubtypesClasses.addCode("\n);\n");
					getSuperTypeSubtypesClasses.endControlFlow();
				}
				getSuperTypeSubtypesClasses.beginControlFlow("default:");
				getSuperTypeSubtypesClasses.addStatement("throw new $T()", IllegalArgumentException.class);
				getSuperTypeSubtypesClasses.endControlFlow();
				getSuperTypeSubtypesClasses.endControlFlow();
				currentVersionClass.addMethod(getSuperTypeSubtypesClasses.build());
			}
			// UpgradeDataToLatestVersion1 Method
			{
				var upgradeDataToLatestVersion1MethodBuilder = MethodSpec.methodBuilder("upgradeDataToLatestVersion").addTypeVariable(TypeVariableName.get("U", ClassName
						.get(joinPackage(currentVersionPackage, "data"),
								"IBaseType")))
						.addModifiers(Modifier.PUBLIC).addModifiers(Modifier.STATIC).addModifiers(Modifier.FINAL).returns(TypeVariableName.get("U"))
						.addParameter(ParameterSpec.builder(TypeName.INT, "oldVersion").build()).addParameter(
								ParameterSpec.builder(ClassName.get(joinPackage(basePackageName, ""), "BaseType"), "type").build())
						.addParameter(ParameterSpec.builder(DataInput.class, "oldDataInput").build())
						.addException(IOException.class).beginControlFlow("switch (oldVersion)");
				AtomicInteger seqNumber = new AtomicInteger(0);
				for (var versionConfiguration : dataModel.getVersionsSet()) {
// Add a case in which the data version deserializes the serialized data and upgrades it
					upgradeDataToLatestVersion1MethodBuilder.beginControlFlow("case $T." + versionConfiguration.getVersionVarName() + ":",
							ClassName.get(joinPackage(basePackageName, ""), "Versions")
					);
					upgradeDataToLatestVersion1MethodBuilder.addStatement("var deserialized" + seqNumber.incrementAndGet() + " = "
							+ versionConfiguration.getPackage(basePackageName)
							+ ".Version.INSTANCE.getSerializer(type).deserialize(oldDataInput)");
					upgradeDataToLatestVersion1MethodBuilder.addStatement(
							"return upgradeDataToLatestVersion(Versions." + versionConfiguration.getVersionVarName() + ", deserialized"
									+ seqNumber.get() + ")");
					upgradeDataToLatestVersion1MethodBuilder.endControlFlow();
				}
				var upgradeDataToLatestVersion1Method = upgradeDataToLatestVersion1MethodBuilder.beginControlFlow("default:")
						.addStatement("throw new $T(\"Unknown version: \" + oldVersion)", IOException.class).endControlFlow()
						.endControlFlow().build();
				currentVersionClass.addMethod(upgradeDataToLatestVersion1Method);
			}
			// UpgradeDataToLatestVersion2 Method
			{
				var versionsClassName = ClassName.get(joinPackage(basePackageName, ""), "Versions");
				var upgradeDataToLatestVersion2MethodBuilder = MethodSpec.methodBuilder("upgradeDataToLatestVersion")
						.addModifiers(Modifier.PUBLIC).addModifiers(Modifier.STATIC).addModifiers(Modifier.FINAL).addTypeVariable(TypeVariableName.get("T"))
						.addTypeVariable(TypeVariableName.get("U", ClassName
								.get(joinPackage(currentVersionPackage, "data"),
										"IBaseType"))).returns(TypeVariableName.get("U"))
						.addParameter(ParameterSpec.builder(TypeName.INT, "oldVersion").build())
						.addParameter(ParameterSpec.builder(TypeVariableName.get("T"), "oldData").build())
						.addException(IOException.class)
						.addStatement("int intermediateVersion = oldVersion")
						.addStatement("$T intermediateData = oldData", Object.class)
						.beginControlFlow("while (true)")
						.beginControlFlow("switch (intermediateVersion)");
				for (var versionConfiguration : dataModel.getVersionsSet()) {
// Add a case in which the data version deserializes the serialized data and upgrades it
					upgradeDataToLatestVersion2MethodBuilder.beginControlFlow("case $T." + versionConfiguration.getVersionVarName() + ":",
							versionsClassName
					);
					if (versionConfiguration.isCurrent()) {
						// This is the latest version, don't upgrade.
						upgradeDataToLatestVersion2MethodBuilder.addStatement("return ($T) intermediateData", TypeVariableName.get("U"));
					} else {
						// Upgrade
						upgradeDataToLatestVersion2MethodBuilder
								.addStatement(
										"intermediateData = " + versionConfiguration.getPackage(basePackageName)
												+ ".Version.upgradeToNextVersion(($T) intermediateData)",
										ClassName.get(joinPackage(versionConfiguration.getPackage (basePackageName),
												"data"
										), "IBaseType")
								)
								.addStatement("intermediateVersion = $T."
										+ getVersionVarName(dataModel.getNextVersionOrThrow(versionConfiguration)), versionsClassName)
								.addStatement("break");
					}
					upgradeDataToLatestVersion2MethodBuilder.endControlFlow();
				}
				var upgradeDataToLatestVersion2Method = upgradeDataToLatestVersion2MethodBuilder.beginControlFlow("default:")
						.addStatement("throw new $T(\"Unknown version: \" + oldVersion)", IOException.class).endControlFlow()
						.endControlFlow()
						.endControlFlow()
						.build();
				currentVersionClass.addMethod(upgradeDataToLatestVersion2Method);
			}
			// Save the resulting class in the main package
			writeClass(generatedFilesToDelete, outPath, joinPackage(basePackageName, "current"), currentVersionClass);
		}

		for (var versionConfiguration : dataModel.getVersionsSet()) {
			var versionPackage = versionConfiguration.getPackage(basePackageName);
			var versionClassType = ClassName.get(joinPackage(versionPackage, ""), "Version");
			var nextVersion = dataModel.getNextVersion(versionConfiguration);
			var nextVersionPackage = nextVersion.map((nextVersionValue) -> nextVersionValue.getPackage(basePackageName));

			logger.info("Found version configuration:\n{\n\tversion: \"" + versionConfiguration.getName()
					+ "\",\n\tversionPackage: \"" + versionPackage + "\",\n\tnextVersion: \"" + nextVersion
					.map(ComputedVersion::getName)
					.orElse("unknown") + "\",\n\tnextVersionPackage: \"" + nextVersionPackage.orElse("unknown") + "\"\n}");

			HashMap<String, TypeName> typeOptionalSerializers = new LinkedHashMap<>();
			HashMap<String, TypeName> typeOptionalUpgraders = new LinkedHashMap<>();
			HashMap<String, SerializeCodeBlockGenerator> typeSerializeStatement = new HashMap<>();
			HashMap<String, CodeBlock> typeDeserializeStatement = new HashMap<>();
			HashMap<String, Boolean> typeMustGenerateSerializer = new HashMap<>();
			HashMap<String, TypeName> typeTypes = new LinkedHashMap<>(Map.of("boolean",
					TypeName.BOOLEAN,
					"short",
					TypeName.SHORT,
					"char",
					TypeName.CHAR,
					"int",
					TypeName.INT,
					"long",
					TypeName.LONG,
					"float",
					TypeName.FLOAT,
					"double",
					TypeName.DOUBLE,
					"byte",
					TypeName.BYTE
			));
			HashMap<String, Family> typeFamily = new LinkedHashMap<>(Map.of("boolean",
					Family.SPECIAL_NATIVE,
					"short",
					Family.SPECIAL_NATIVE,
					"char",
					Family.SPECIAL_NATIVE,
					"int",
					Family.SPECIAL_NATIVE,
					"long",
					Family.SPECIAL_NATIVE,
					"float",
					Family.SPECIAL_NATIVE,
					"double",
					Family.SPECIAL_NATIVE,
					"byte",
					Family.SPECIAL_NATIVE
			));
			@Nullable HashMap<String, TypeName> nextVersionTypeTypes;
			@Nullable HashMap<String, Family> nextVersionTypeFamily;
			if (nextVersion.isPresent()) {
				nextVersionTypeTypes = new LinkedHashMap<>(Map.of("boolean",
						TypeName.BOOLEAN,
						"short",
						TypeName.SHORT,
						"char",
						TypeName.CHAR,
						"int",
						TypeName.INT,
						"long",
						TypeName.LONG,
						"float",
						TypeName.FLOAT,
						"double",
						TypeName.DOUBLE,
						"byte",
						TypeName.BYTE
				));
				nextVersionTypeFamily = new LinkedHashMap<>(Map.of("boolean",
						Family.SPECIAL_NATIVE,
						"short",
						Family.SPECIAL_NATIVE,
						"char",
						Family.SPECIAL_NATIVE,
						"int",
						Family.SPECIAL_NATIVE,
						"long",
						Family.SPECIAL_NATIVE,
						"float",
						Family.SPECIAL_NATIVE,
						"double",
						Family.SPECIAL_NATIVE,
						"byte",
						Family.SPECIAL_NATIVE
				));
			} else {
				nextVersionTypeTypes = null;
				nextVersionTypeFamily = null;
			}
			Set<String> specialNativeTypes = Set.of("String",
					"boolean",
					"short",
					"char",
					"int",
					"long",
					"float",
					"double",
					"byte",
					"Int52"
			);

			// Generate the type statements
			{
				// Generate the native types
				for (String specialNativeType : specialNativeTypes) {
					if (Character.isUpperCase(specialNativeType.charAt(0))) {
						typeTypes.put(specialNativeType, ClassName.get(getSpecialNativePackage(specialNativeType), specialNativeType));
						typeFamily.put(specialNativeType, Family.SPECIAL_NATIVE);
						if (nextVersion.isPresent()) {
							nextVersionTypeTypes.put(specialNativeType, ClassName.get(getSpecialNativePackage(specialNativeType), specialNativeType));
							nextVersionTypeFamily.put(specialNativeType, Family.SPECIAL_NATIVE);
						}
						if (specialNativeType.equals("String")) {
							typeSerializeStatement.put(specialNativeType,
									new SerializeCodeBlockGenerator(CodeBlock
											.builder()
											.add("$T.INSTANCE.serialize(dataOutput, ", StringSerializer.class)
											.build(), CodeBlock.builder().add(")").build())
							);
							typeDeserializeStatement.put(specialNativeType,
									CodeBlock.builder().add("$T.INSTANCE.deserialize(dataInput)", StringSerializer.class).build()
							);
						} else if (specialNativeType.equals("Int52")) {
							typeSerializeStatement.put(specialNativeType,
									new SerializeCodeBlockGenerator(CodeBlock
											.builder()
											.add("$T.INSTANCE.serialize(dataOutput, ", Int52Serializer.class)
											.build(), CodeBlock.builder().add(")").build())
							);
							typeDeserializeStatement.put(specialNativeType,
									CodeBlock.builder().add("$T.INSTANCE.deserialize(dataInput)", Int52Serializer.class).build()
							);
						} else {
							typeSerializeStatement.put(specialNativeType,
									new SerializeCodeBlockGenerator(CodeBlock
											.builder()
											.add("dataOutput.write" + specialNativeType + "(")
											.build(), CodeBlock.builder().add(")").build())
							);
							typeDeserializeStatement.put(specialNativeType,
									CodeBlock.builder().add("dataInput.read" + specialNativeType + "()").build()
							);
						}
					} else {
						var uppercasedType = Character.toUpperCase(specialNativeType.charAt(0)) + specialNativeType.substring(1);
						// don't put the special type, because it's already in the hashmap.
						typeSerializeStatement.put(specialNativeType,
								new SerializeCodeBlockGenerator(CodeBlock
										.builder()
										.add("dataOutput.write" + uppercasedType + "(")
										.build(), CodeBlock.builder().add(")").build())
						);
						typeDeserializeStatement.put(specialNativeType,
								CodeBlock.builder().add("dataInput.read" + uppercasedType + "()").build()
						);
					}
					typeMustGenerateSerializer.put(specialNativeType, false);

					typeTypes.put("-" + specialNativeType,
							ClassName.get("it.cavallium.data.generator.nativedata", "Nullable" + specialNativeType)
					);
					typeFamily.put("-" + specialNativeType, Family.NULLABLE_OTHER);
					typeTypes.put("§" + specialNativeType, getImmutableArrayType(typeTypes, specialNativeType));
					typeFamily.put("§" + specialNativeType, Family.OTHER);
					if (nextVersion.isPresent()) {
						nextVersionTypeTypes.put("-" + specialNativeType,
								ClassName.get("it.cavallium.data.generator.nativedata", "Nullable" + specialNativeType)
						);
						nextVersionTypeFamily.put("-" + specialNativeType, Family.NULLABLE_OTHER);
						nextVersionTypeTypes.put("§" + specialNativeType, getImmutableArrayType(typeTypes, specialNativeType));
						nextVersionTypeFamily.put("§" + specialNativeType, Family.OTHER);
					}
					typeOptionalSerializers.put("-" + specialNativeType,
							ClassName.get("it.cavallium.data.generator.nativedata",
									"Nullable" + specialNativeType + "Serializer"
							)
					);
					typeOptionalSerializers.put("§" + specialNativeType,
							ClassName.get("it.cavallium.data.generator.nativedata",
									"Array" + specialNativeType + "Serializer"
							)
					);
					typeSerializeStatement.put("-" + specialNativeType,
							new SerializeCodeBlockGenerator(CodeBlock
									.builder()
									.add("$T.Nullable" + specialNativeType + "SerializerInstance.serialize(dataOutput, ",
											ClassName.get(joinPackage(versionPackage, ""), "Version")
									)
									.build(), CodeBlock.builder().add(")").build())
					);
					typeSerializeStatement.put("§" + specialNativeType,
							new SerializeCodeBlockGenerator(CodeBlock
									.builder()
									.add("$T.Array" + specialNativeType + "SerializerInstance.serialize(dataOutput, ",
											ClassName.get(joinPackage(versionPackage, ""), "Version")
									)
									.build(), CodeBlock.builder().add(")").build())
					);
					typeDeserializeStatement.put("-" + specialNativeType,
							CodeBlock
									.builder()
									.add("$T.Nullable" + specialNativeType + "SerializerInstance.deserialize(dataInput)",
											ClassName.get(joinPackage(versionPackage, ""), "Version")
									)
									.build()
					);
					typeDeserializeStatement.put("§" + specialNativeType,
							CodeBlock
									.builder()
									.add("$T.Array" + specialNativeType + "SerializerInstance.deserialize(dataInput)",
											ClassName.get(joinPackage(versionPackage, ""), "Version")
									)
									.build()
					);
					typeMustGenerateSerializer.put("-" + specialNativeType, false);
					typeMustGenerateSerializer.put("§" + specialNativeType, false);

				}

				// Setup only the base types upgraders variables
				for (String s : versionConfiguration.getClassMap().keySet()) {
					if (nextVersion.isPresent()) {
						typeOptionalUpgraders.put(s, ClassName.get(joinPackage(versionPackage, "upgraders"), s + "Upgrader"));
					}
				}

				// Generate the basic and super types
				Stream
						.concat(versionConfiguration.getClassMap().keySet().stream(), dataModel.getSuperTypes().keySet().stream())
						.forEach((type) -> {
							boolean isBasic = versionConfiguration.getClassMap().containsKey(type);
							typeOptionalSerializers.put(type,
									ClassName.get(joinPackage(versionPackage, "serializers"), type + "Serializer")
							);
							typeSerializeStatement.put(type,
									new SerializeCodeBlockGenerator(CodeBlock
											.builder()
											.add("$T." + type + "SerializerInstance.serialize(dataOutput, ", versionClassType)
											.build(), CodeBlock.builder().add(")").build())
							);
							typeDeserializeStatement.put(type,
									CodeBlock
											.builder()
											.add("$T." + type + "SerializerInstance.deserialize(dataInput)", versionClassType)
											.build()
							);
							typeMustGenerateSerializer.put(type, true);
							typeTypes.put(type, ClassName.get(joinPackage(versionPackage, "data"), type));
							typeFamily.put(type, !isBasic ? Family.GENERIC : Family.BASIC);
							if (nextVersion.isPresent()) {
								nextVersionTypeTypes.put(type, ClassName.get(joinPackage(nextVersionPackage.get(), "data"), type));
								nextVersionTypeFamily.put(type, !isBasic ? Family.GENERIC : Family.BASIC);
							}

							NeededTypes neededTypes = registerNeededTypes(versionConfiguration,
									!isBasic ? Family.GENERIC : Family.BASIC,
									type,
									nextVersion,
									nextVersionPackage,
									versionClassType,
									versionPackage,
									typeOptionalSerializers,
									typeSerializeStatement,
									typeDeserializeStatement,
									typeMustGenerateSerializer,
									typeTypes,
									typeFamily,
									nextVersionTypeTypes,
									nextVersionTypeFamily,
									() -> ClassName.get(joinPackage(versionPackage, "data"), type),
									() -> ClassName.get(joinPackage(nextVersionPackage.orElseThrow(), "data"), type)
							);
						});

				// Generate the special types
				for (Entry<String, CustomTypesConfiguration> entry : dataModel.getCustomTypes().entrySet()) {
					String key = entry.getKey();
					CustomTypesConfiguration customTypeConfiguration = entry.getValue();
					Optional<CustomTypesConfiguration> nextVersionCustomTypeConfiguration = nextVersion
							.map(s -> Objects.requireNonNull(dataModel.getCustomTypes().get(key),
									() -> "Custom type " + key + " not found in version " + s
							));
					typeOptionalSerializers.put(key, ClassName.bestGuess(customTypeConfiguration.serializer));
					typeSerializeStatement.put(key,
							new SerializeCodeBlockGenerator(CodeBlock
									.builder()
									.add("$T." + key + "SerializerInstance.serialize(dataOutput, ", versionClassType)
									.build(), CodeBlock.builder().add(")").build())
					);
					typeDeserializeStatement.put(key,
							CodeBlock
									.builder()
									.add("$T." + key + "SerializerInstance.deserialize(dataInput)", versionClassType)
									.build()
					);
					typeMustGenerateSerializer.put(key, false);
					typeTypes.put(key, customTypeConfiguration.getJavaClassType());
					typeFamily.put(key, Family.OTHER);
					if (nextVersionCustomTypeConfiguration.isPresent()) {
						nextVersionTypeTypes.put(key, nextVersionCustomTypeConfiguration.get().getJavaClassType());
						nextVersionTypeFamily.put(key, Family.OTHER);
					}

					var arrayClassName = customTypeConfiguration.getJavaClassType();
					var neededTypes = registerNeededTypes(versionConfiguration,
							Family.OTHER,
							key,
							nextVersion,
							nextVersionPackage,
							versionClassType,
							versionPackage,
							typeOptionalSerializers,
							typeSerializeStatement,
							typeDeserializeStatement,
							typeMustGenerateSerializer,
							typeTypes,
							typeFamily,
							nextVersionTypeTypes,
							nextVersionTypeFamily,
							() -> arrayClassName,
							() -> arrayClassName
					);
				}

				for (String type : typeTypes.keySet()) {
					var a1 = typeSerializeStatement.get(type).toString().replace("\n", "");
					var a2 = typeDeserializeStatement.get(type).toString().replace("\n", "");
					var a3 = typeTypes.get(type).toString().replace("\n", "");
					if (logger.isDebugEnabled()) {
						logger.debug("Found type: {\n\ttype: \"" + type + "\",\n\tclass: \"" + a3 + "\",\n\tserialize: \"" + a1
								+ "\",\n\tdeserialize: \"" + a2 + "\"\n}");
					} else {
						switch (type.charAt(0)) {
							case '$' -> logger.debug("Found array: " + type.substring(1));
							case '-' -> logger.debug("Found nullable type: " + type.substring(1));
							default -> logger.debug("Found type: " + type);
						}
					}
				}
			}

			// Check if all types exist
			{
				for (Entry<String, ParsedClass> e : versionConfiguration.getClassMap().entrySet()) {
					String type = e.getKey();
					ParsedClass typeConfig = e.getValue();
					for (Entry<String, String> entry : typeConfig.data.entrySet()) {
						String field = entry.getKey();
						String fieldType = entry.getValue();
						if (!typeTypes.containsKey(fieldType)) {
							throw new UnsupportedOperationException(
									"Unknown type '" + fieldType + "' of field '" + field + "' in class '" + type + "' in version '"
											+ versionConfiguration.getName() + "'");
						}
					}
				}
			}

			// Generate the nullable types and serializers
			{
				for (String type : typeTypes.keySet()) {
					if (type.startsWith("-")) {
						if (typeMustGenerateSerializer.get(type)) {
							String substring = type.substring(1);
							var nullableClassType = ClassName.get(joinPackage(versionPackage, "data.nullables"),
									"Nullable" + substring
							);

							// Create the nullable X serializer class
							{
								var nullableSerializerClass = TypeSpec.classBuilder("Nullable" + substring + "Serializer");
								nullableSerializerClass.addModifiers(Modifier.PUBLIC);
								nullableSerializerClass.addModifiers(Modifier.FINAL);
								nullableSerializerClass.addSuperinterface(ParameterizedTypeName.get(ClassName.get(
										"it.cavallium.data.generator",
										"DataSerializer"
								), nullableClassType));
								// Create the INSTANCE field
								{
									var thisSerializerClassType = ClassName.get(joinPackage(versionPackage, "serializers"),
											"Nullable" + substring + "Serializer"
									);
									var instanceField = FieldSpec
											.builder(thisSerializerClassType, "INSTANCE")
											.initializer("new $T()", thisSerializerClassType);
									instanceField.addModifiers(Modifier.PUBLIC);
									instanceField.addModifiers(Modifier.STATIC);
									instanceField.addModifiers(Modifier.FINAL);
									nullableSerializerClass.addField(instanceField.build());
								}
								// Create the serialize method
								{
									var serializeMethod = createEmptySerializeMethod(nullableClassType);
									serializeMethod.beginControlFlow("if (data.isEmpty())");
									serializeMethod.addStatement("dataOutput.writeBoolean(false)");
									serializeMethod.nextControlFlow("else");
									serializeMethod.addStatement("dataOutput.writeBoolean(true)");
									serializeMethod.addStatement("var dataContent = data.get()");
									serializeMethod.addStatement(typeSerializeStatement.get(substring).generate("dataContent"));
									serializeMethod.endControlFlow();
									nullableSerializerClass.addMethod(serializeMethod.build());
								}
								// Create the deserialize method
								{
									var deserializeMethod = createEmptyDeserializeMethod(nullableClassType);
									deserializeMethod.addStatement("var isPresent = dataInput.readBoolean()");
									deserializeMethod.beginControlFlow("if (!isPresent)");
									deserializeMethod.addStatement("return $T.empty()", nullableClassType);
									deserializeMethod.nextControlFlow("else");
									deserializeMethod.addCode("$[return $T.of(", nullableClassType);
									deserializeMethod.addCode(typeDeserializeStatement.get(substring));
									deserializeMethod.addCode(");\n$]");
									deserializeMethod.endControlFlow();
									nullableSerializerClass.addMethod(deserializeMethod.build());
								}
								// Save the resulting class in the main package
								try {
									writeClass(generatedFilesToDelete,
											outPath, joinPackage(versionPackage, "serializers"), nullableSerializerClass);
								} catch (IOException e) {
									throw new IOError(e);
								}
							}

							// Create the nullable X types classes
							{
								var typeType = typeTypes.get(substring);
								var family = typeFamily.get(substring);
								var nullableTypeType = typeTypes.get("-" + substring);
								var nullableTypeClass = TypeSpec.recordBuilder("Nullable" + capitalize(substring));
								nullableTypeClass.addModifiers(Modifier.PUBLIC);
								nullableTypeClass.addModifiers(Modifier.STATIC);
								nullableTypeClass.addModifiers(Modifier.FINAL);
								if (family == Family.BASIC) {
									nullableTypeClass.addSuperinterface(ClassName.get(joinPackage(versionPackage, "data.nullables"),
											"INullableBaseType"
									));
								}
								if (family == Family.GENERIC) {
									nullableTypeClass.addSuperinterface(ClassName.get(joinPackage(versionPackage, "data.nullables"),
											"INullableSuperType"
									));
								}
								nullableTypeClass.addSuperinterface(ClassName.get(joinPackage(versionPackage, "data.nullables"),
										"INullableIType"
								));
								nullableTypeClass.addSuperinterface(INullable.class);
								nullableTypeClass.addSuperinterface(ParameterizedTypeName.get(ClassName.get( "it.cavallium.data.generator", "TypedNullable"), typeType));
								var nullInstance = FieldSpec.builder(nullableTypeType, "NULL", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
								nullInstance.initializer("new $T(null)", nullableTypeType);
								nullableTypeClass.addField(nullInstance.build());
								var valueField = ParameterSpec.builder(typeType, "value");
								nullableTypeClass.addRecordComponent(valueField.build());
								var ofMethod = MethodSpec.methodBuilder("of");
								ofMethod.addModifiers(Modifier.PUBLIC);
								ofMethod.addModifiers(Modifier.STATIC);
								ofMethod.addModifiers(Modifier.FINAL);
								ofMethod.addException(NullPointerException.class);
								ofMethod.returns(nullableClassType);
								ofMethod.addParameter(ParameterSpec.builder(typeType, "value").build());
								ofMethod.beginControlFlow("if (value != null)");
								ofMethod.addStatement("return new $T(value)", nullableTypeType);
								ofMethod.nextControlFlow("else");
								ofMethod.addStatement("throw new $T()", NullPointerException.class);
								ofMethod.endControlFlow();
								nullableTypeClass.addMethod(ofMethod.build());
								var ofNullableMethod = MethodSpec.methodBuilder("ofNullable");
								ofNullableMethod.addModifiers(Modifier.PUBLIC);
								ofNullableMethod.addModifiers(Modifier.STATIC);
								ofNullableMethod.addModifiers(Modifier.FINAL);
								ofNullableMethod.returns(nullableClassType);
								ofNullableMethod.addParameter(ParameterSpec.builder(typeType, "value").build());
								ofNullableMethod.beginControlFlow("if (value != null)");
								ofNullableMethod.addStatement("return new $T(value)", nullableTypeType);
								ofNullableMethod.nextControlFlow("else");
								ofNullableMethod.addStatement("return NULL");
								ofNullableMethod.endControlFlow();
								nullableTypeClass.addMethod(ofNullableMethod.build());
								var emptyMethod = MethodSpec.methodBuilder("empty");
								emptyMethod.addModifiers(Modifier.PUBLIC);
								emptyMethod.addModifiers(Modifier.STATIC);
								emptyMethod.addModifiers(Modifier.FINAL);
								emptyMethod.returns(nullableClassType);
								emptyMethod.addStatement("return NULL");
								nullableTypeClass.addMethod(emptyMethod.build());
								if (OVERRIDE_ALL_NULLABLE_METHODS) {
									var isEmptyMethod = MethodSpec.methodBuilder("isEmpty");
									isEmptyMethod.addModifiers(Modifier.PUBLIC);
									isEmptyMethod.addModifiers(Modifier.FINAL);
									isEmptyMethod.addAnnotation(Override.class);
									isEmptyMethod.returns(TypeName.BOOLEAN);
									isEmptyMethod.addStatement("return value == null");
									nullableTypeClass.addMethod(isEmptyMethod.build());
									var isPresentMethod = MethodSpec.methodBuilder("isPresent");
									isPresentMethod.addModifiers(Modifier.PUBLIC);
									isPresentMethod.addModifiers(Modifier.FINAL);
									isPresentMethod.addAnnotation(Override.class);
									isPresentMethod.returns(TypeName.BOOLEAN);
									isPresentMethod.addStatement("return value != null");
									nullableTypeClass.addMethod(isPresentMethod.build());
									var getMethod = MethodSpec.methodBuilder("get");
									getMethod.addModifiers(Modifier.PUBLIC);
									getMethod.addModifiers(Modifier.FINAL);
									getMethod.addException(NullPointerException.class);
									getMethod.addAnnotation(Override.class);
									getMethod.addAnnotation(NotNull.class);
									getMethod.returns(typeType);
									getMethod.beginControlFlow("if (value == null)");
									getMethod.addStatement("throw new $T()", NullPointerException.class);
									getMethod.nextControlFlow("else");
									getMethod.addStatement("return value");
									getMethod.endControlFlow();
									nullableTypeClass.addMethod(getMethod.build());
									var orElseMethod = MethodSpec.methodBuilder("orElse");
									orElseMethod.addParameter(ParameterSpec
											.builder(typeType, "defaultValue")
											.addAnnotation(NotNull.class)
											.build());
									orElseMethod.addModifiers(Modifier.PUBLIC);
									orElseMethod.addModifiers(Modifier.FINAL);
									orElseMethod.addAnnotation(Override.class);
									orElseMethod.addAnnotation(NotNull.class);
									orElseMethod.returns(typeType);
									orElseMethod.beginControlFlow("if (value == null)");
									orElseMethod.addStatement("return defaultValue");
									orElseMethod.nextControlFlow("else");
									orElseMethod.addStatement("return value");
									orElseMethod.endControlFlow();
									nullableTypeClass.addMethod(orElseMethod.build());
									var orMethodGeneric = MethodSpec.methodBuilder("or");
									orMethodGeneric.addParameter(ParameterSpec
											.builder(ParameterizedTypeName.get(
													ClassName.get("it.cavallium.data.generator", "NativeNullable"),
													WildcardTypeName.subtypeOf(typeType)), "fallback")
											.addAnnotation(NotNull.class)
											.build());
									orMethodGeneric.addModifiers(Modifier.PUBLIC);
									orMethodGeneric.addModifiers(Modifier.FINAL);
									orMethodGeneric.addAnnotation(Override.class);
									orMethodGeneric.addAnnotation(NotNull.class);
									orMethodGeneric.returns(nullableClassType);
									orMethodGeneric.beginControlFlow("if (value == null)");
									orMethodGeneric.beginControlFlow("if (fallback.getClass() == $T.class)", nullableClassType);
									orMethodGeneric.addStatement("return ($T) fallback", nullableClassType);
									orMethodGeneric.nextControlFlow("else");
									orMethodGeneric.addStatement("return ofNullable(fallback.getNullable())");
									orMethodGeneric.endControlFlow();
									orMethodGeneric.nextControlFlow("else");
									orMethodGeneric.addStatement("return this");
									orMethodGeneric.endControlFlow();
									nullableTypeClass.addMethod(orMethodGeneric.build());
								}
								var orMethodSpecific = MethodSpec.methodBuilder("or");
								orMethodSpecific.addParameter(ParameterSpec
										.builder(nullableTypeType, "fallback")
										.addAnnotation(NotNull.class)
										.build());
								orMethodSpecific.addModifiers(Modifier.PUBLIC);
								orMethodSpecific.addModifiers(Modifier.FINAL);
								orMethodSpecific.addAnnotation(NotNull.class);
								orMethodSpecific.returns(nullableClassType);
								orMethodSpecific.beginControlFlow("if (value == null)");
								orMethodSpecific.addStatement("return fallback");
								orMethodSpecific.nextControlFlow("else");
								orMethodSpecific.addStatement("return this");
								orMethodSpecific.endControlFlow();
								nullableTypeClass.addMethod(orMethodSpecific.build());
								var getNullableMethod = MethodSpec.methodBuilder("getNullable");
								getNullableMethod.addModifiers(Modifier.PUBLIC);
								getNullableMethod.addModifiers(Modifier.FINAL);
								getNullableMethod.addAnnotation(Override.class);
								getNullableMethod.addAnnotation(Nullable.class);
								getNullableMethod.returns(typeType);
								getNullableMethod.addStatement("return value");
								nullableTypeClass.addMethod(getNullableMethod.build());
								if (OVERRIDE_ALL_NULLABLE_METHODS) {
									var getNullableParam = MethodSpec.methodBuilder("getNullable");
									getNullableParam.addParameter(ParameterSpec
											.builder(typeType, "defaultValue")
											.addAnnotation(Nullable.class)
											.build());
									getNullableParam.addModifiers(Modifier.PUBLIC);
									getNullableParam.addModifiers(Modifier.FINAL);
									getNullableParam.addAnnotation(Override.class);
									getNullableParam.addAnnotation(Nullable.class);
									getNullableParam.returns(typeType);
									getNullableParam.beginControlFlow("if (value == null)");
									getNullableParam.addStatement("return defaultValue");
									getNullableParam.nextControlFlow("else");
									getNullableParam.addStatement("return value");
									getNullableParam.endControlFlow();
									nullableTypeClass.addMethod(getNullableParam.build());
								}
								var getDollarNullableMethod = MethodSpec.methodBuilder("$getNullable");
								getDollarNullableMethod.addModifiers(Modifier.PUBLIC);
								getDollarNullableMethod.addModifiers(Modifier.FINAL);
								getDollarNullableMethod.addAnnotation(Nullable.class);
								getDollarNullableMethod.addAnnotation(Override.class);
								getDollarNullableMethod.returns(typeType);
								getDollarNullableMethod.addStatement("return this.getNullable()");
								nullableTypeClass.addMethod(getDollarNullableMethod.build());
								if (family == Family.BASIC) {
									var getBaseType = MethodSpec.methodBuilder("getBaseType$");
									getBaseType.addModifiers(Modifier.PUBLIC);
									getBaseType.addModifiers(Modifier.FINAL);
									getBaseType.addException(NullPointerException.class);
									getBaseType.addAnnotation(NotNull.class);
									getBaseType.returns(ClassName.get(joinPackage(basePackageName, ""), "BaseType"));
									getBaseType.addStatement("return $T." + capitalize(substring), ClassName.get(joinPackage(basePackageName, ""), "BaseType"));
									nullableTypeClass.addMethod(getBaseType.build());
								}
								if (family == Family.GENERIC) {
									var getBaseType = MethodSpec.methodBuilder("getSuperType$");
									getBaseType.addModifiers(Modifier.PUBLIC);
									getBaseType.addModifiers(Modifier.FINAL);
									getBaseType.addException(NullPointerException.class);
									getBaseType.addAnnotation(NotNull.class);
									getBaseType.returns(ClassName.get(joinPackage(basePackageName, ""), "SuperType"));
									getBaseType.addStatement("return $T." + capitalize(substring), ClassName.get(joinPackage(basePackageName, ""), "SuperType"));
									nullableTypeClass.addMethod(getBaseType.build());
								}

								try {
									writeClass(generatedFilesToDelete,
											outPath, joinPackage(versionPackage, "data.nullables"), nullableTypeClass);
								} catch (IOException e) {
									throw new IOError(e);
								}
							}
						}
					}
				}
			}

			// Generate the array types and serializers
			{
				for (String type : typeTypes.keySet()) {
					if (type.startsWith("§")) {
						if (typeMustGenerateSerializer.get(type)) {
							String substring = type.substring(1);
							var classType = ClassName.get(joinPackage(versionPackage, "data"), substring);
							var arrayClassType = getImmutableArrayType(classType);

							// Create the array X serializer class
							{
								var arraySerializerClass = TypeSpec.classBuilder("Array" + substring + "Serializer");
								arraySerializerClass.addModifiers(Modifier.PUBLIC);
								arraySerializerClass.addModifiers(Modifier.FINAL);
								arraySerializerClass.addSuperinterface(ParameterizedTypeName.get(ClassName.get(
										"it.cavallium.data.generator",
										"DataSerializer"
								), arrayClassType));
								// Create the INSTANCE field
								{
									var thisSerializerClassType = ClassName.get(joinPackage(versionPackage, "serializers"),
											"Array" + substring + "Serializer"
									);
									var instanceField = FieldSpec
											.builder(thisSerializerClassType, "INSTANCE")
											.initializer("new $T()", thisSerializerClassType);
									instanceField.addModifiers(Modifier.PUBLIC);
									instanceField.addModifiers(Modifier.STATIC);
									instanceField.addModifiers(Modifier.FINAL);
									arraySerializerClass.addField(instanceField.build());
								}
								// Create the serialize method
								{
									var serializeMethod = createEmptySerializeMethod(arrayClassType);
									serializeMethod.addStatement("dataOutput.writeInt(data.size())");
									serializeMethod.beginControlFlow("for (int i = 0; i < data.size(); i++)");
									serializeMethod.addStatement(typeSerializeStatement.get(substring).generate("data.get(i)"));
									serializeMethod.endControlFlow();
									arraySerializerClass.addMethod(serializeMethod.build());
								}
								// Create the deserialize method
								{
									var deserializeMethod = createEmptyDeserializeMethod(arrayClassType);
									deserializeMethod.addStatement("int length = dataInput.readInt()");
									deserializeMethod.addStatement("var data = new $T[length]", classType);
									deserializeMethod.beginControlFlow("for (int i = 0; i < length; i++)");
									deserializeMethod.addStatement(CodeBlock.join(List.of(CodeBlock.of("data[i] = "),
											typeDeserializeStatement.get(substring)
									), ""));
									deserializeMethod.endControlFlow();
									deserializeMethod.addStatement("return $T.of(data)",
											(arrayClassType instanceof ParameterizedTypeName
													? ((ParameterizedTypeName) arrayClassType).rawType : arrayClassType)
									);
									arraySerializerClass.addMethod(deserializeMethod.build());
								}
								// Save the resulting class in the main package
								try {
									writeClass(generatedFilesToDelete,
											outPath, joinPackage(versionPackage, "serializers"), arraySerializerClass);
								} catch (IOException e) {
									throw new IOError(e);
								}
							}
						}
					}
				}
			}

			// Generate the base types serializers
			{
				for (Entry<String, ParsedClass> classConfigurationEntry : versionConfiguration.getClassMap().entrySet()) {
					String type = classConfigurationEntry.getKey();
					ParsedClass baseTypeConfiguration = classConfigurationEntry.getValue();
					var classType = ClassName.get(joinPackage(versionPackage, "data"), type);

					// Create the basic X serializer class
					{
						var serializerClass = TypeSpec.classBuilder(type + "Serializer");
						serializerClass.addModifiers(Modifier.PUBLIC);
						serializerClass.addModifiers(Modifier.FINAL);
						serializerClass.addSuperinterface(ParameterizedTypeName.get(ClassName.get(
								"it.cavallium.data.generator",
								"DataSerializer"
						), classType));
						// Create the INSTANCE field
						{
							var thisSerializerClassType = ClassName.get(joinPackage(versionPackage, "serializers"),
									type + "Serializer"
							);
							var instanceField = FieldSpec
									.builder(thisSerializerClassType, "INSTANCE")
									.initializer("new $T()", thisSerializerClassType);
							instanceField.addModifiers(Modifier.PUBLIC);
							instanceField.addModifiers(Modifier.STATIC);
							instanceField.addModifiers(Modifier.FINAL);
							serializerClass.addField(instanceField.build());
						}
						// Create the serialize method
						{
							var serializeMethod = createEmptySerializeMethod(classType);
							if (baseTypeConfiguration.data != null) {
								for (Entry<String, String> entry : baseTypeConfiguration.data.entrySet()) {
									String field = entry.getKey();
									String fieldType = entry.getValue();
									serializeMethod.addStatement(typeSerializeStatement
											.get(fieldType)
											.generate("data." + field + "()"));
								}
							}
							serializerClass.addMethod(serializeMethod.build());
						}
						// Create the deserialize method
						{
							var deserializeMethod = createEmptyDeserializeMethod(classType);
							deserializeMethod.addCode("$[return $T.of(\n$]", classType);
							deserializeMethod.addCode("$>");
							AtomicInteger i = new AtomicInteger(baseTypeConfiguration.data.size());
							for (Entry<String, String> entry : baseTypeConfiguration.data.entrySet()) {
								String field = entry.getKey();
								String fieldType = entry.getValue();
								boolean isLast = i.decrementAndGet() == 0;
								deserializeMethod.addCode(typeDeserializeStatement.get(fieldType)).addCode((isLast ? "" : ",") + "\n");
							}
							deserializeMethod.addCode("$<");
							deserializeMethod.addStatement(")");
							serializerClass.addMethod(deserializeMethod.build());
						}
						// Save the resulting class in the main package
						try {
							writeClass(generatedFilesToDelete, outPath, joinPackage(versionPackage, "serializers"), serializerClass);
						} catch (IOException e) {
							throw new IOError(e);
						}
					}

					// Create the basic X upgrader class
					{
						if (nextVersion.isPresent()) {
							var nextVersionClassType = ClassName.get(joinPackage(nextVersionPackage.get(), "data"), type);

							var upgraderClass = TypeSpec.classBuilder(type + "Upgrader");
							upgraderClass.addModifiers(Modifier.PUBLIC);
							upgraderClass.addModifiers(Modifier.FINAL);
							upgraderClass.addSuperinterface(ParameterizedTypeName.get(ClassName.get(
									"it.cavallium.data.generator",
									"DataUpgrader"
							), classType, nextVersionClassType));
							// Create the INSTANCE field
							{
								var thisUpgraderClassType = ClassName.get(joinPackage(versionPackage, "upgraders"), type + "Upgrader");
								var instanceField = FieldSpec
										.builder(thisUpgraderClassType, "INSTANCE")
										.initializer("new $T()", thisUpgraderClassType);
								instanceField.addModifiers(Modifier.PUBLIC);
								instanceField.addModifiers(Modifier.STATIC);
								instanceField.addModifiers(Modifier.FINAL);
								upgraderClass.addField(instanceField.build());
							}
							// Create the upgrade method
							{
								var deserializeMethod = MethodSpec.methodBuilder("upgrade");
								deserializeMethod.addAnnotation(NotNull.class);
								deserializeMethod.addAnnotation(Override.class);
								deserializeMethod.addModifiers(Modifier.PUBLIC);
								deserializeMethod.addModifiers(Modifier.FINAL);
								deserializeMethod.returns(nextVersionClassType);
								deserializeMethod.addParameter(ParameterSpec
										.builder(classType, "data")
										.addAnnotation(NotNull.class)
										.build());
								deserializeMethod.addException(IOException.class);
								Object2IntLinkedOpenHashMap<String> currentVarNumber = new Object2IntLinkedOpenHashMap<>(
										baseTypeConfiguration.getData().size());
								Object2ObjectLinkedOpenHashMap<String, String> currentVarTypeName = new Object2ObjectLinkedOpenHashMap<>(
										baseTypeConfiguration.getData().size());
								Object2ObjectLinkedOpenHashMap<String, TypeName> currentVarTypeClass = new Object2ObjectLinkedOpenHashMap<>(
										baseTypeConfiguration.getData().size());
								Object2ObjectLinkedOpenHashMap<String, Family> currentVarFamily = new Object2ObjectLinkedOpenHashMap<>(
										baseTypeConfiguration.getData().size());
								ObjectOpenHashSet<String> currentVarUpgraded = new ObjectOpenHashSet<>(
										baseTypeConfiguration.getData().size());
								ObjectOpenHashSet<String> currentVarDeleted = new ObjectOpenHashSet<>();
								currentVarNumber.defaultReturnValue(-1);
								deserializeMethod.addStatement("$T.requireNonNull(data)", Objects.class);
								for (Entry<String, String> stringStringEntry : baseTypeConfiguration.getData().entrySet()) {
									String k = stringStringEntry.getKey();
									String value = stringStringEntry.getValue();
									currentVarNumber.addTo(k, 1);
									currentVarTypeName.put(k, value);
									currentVarTypeClass.put(k, typeTypes.get(value));
									currentVarFamily.put(k, typeFamily.get(value));
									currentVarUpgraded.remove(k);
									deserializeMethod.addStatement("var $$field$$" + 0 + "$$" + k + " = data." + k + "()");
								}

								List<VersionTransformation> list = new ArrayList<>();
								for (VersionTransformation versionTransformation : nextVersion.get().transformations) {
									if (versionTransformation.isForClass(type)) {
										list.add(versionTransformation);
									}
								}
								var transformations = Collections.unmodifiableList(list);
								AtomicInteger transformationNumber = new AtomicInteger(0);
								HashMap<String, TypeName> currentTransformedFieldTypes = new HashMap<>();
								for (Entry<String, ParsedClass> stringClassConfigurationEntry : versionConfiguration.getClassMap().entrySet()) {
									String className = stringClassConfigurationEntry.getKey();
									ParsedClass classConfiguration = stringClassConfigurationEntry.getValue();
									for (Entry<String, String> entry : classConfiguration.getData().entrySet()) {
										String fieldName = entry.getKey();
										String fieldType = entry.getValue();
										currentTransformedFieldTypes.put(className + "." + fieldName, typeTypes.get(fieldType));
									}
								}
								HashMap<ClassName, String> dataUpgraderInstanceFieldName = new HashMap<>();
								AtomicInteger nextDataUpgraderInstanceFieldId = new AtomicInteger(0);
								HashMap<ClassName, String> dataInitializerInstanceFieldName = new HashMap<>();
								AtomicInteger nextDataInitializerInstanceFieldId = new AtomicInteger(0);
								for (VersionTransformation transformationConfig : transformations) {
									var transformation = transformationConfig.getTransformation();
									deserializeMethod.addCode("\n");
									deserializeMethod.addComment("TRANSFORMATION #" + transformationNumber.incrementAndGet() + ": "
											+ transformation.getTransformName());
									switch (transformation.getTransformName()) {
										case "remove-data" -> {
											var removeDataTransformation = (RemoveDataConfiguration) transformation;
											{
												deserializeMethod.addComment(
														"Deleted $$field$$" + currentVarNumber.getInt(removeDataTransformation.from) + "$$"
																+ removeDataTransformation.from);
												currentVarNumber.addTo(removeDataTransformation.from, 1);
												currentVarTypeName.remove(removeDataTransformation.from);
												currentVarTypeClass.remove(removeDataTransformation.from);
												currentVarFamily.remove(removeDataTransformation.from);
												currentVarUpgraded.remove(removeDataTransformation.from);
												currentVarDeleted.add(removeDataTransformation.from);
											}
											Objects.requireNonNull(currentTransformedFieldTypes.remove(
													removeDataTransformation.transformClass + "." + removeDataTransformation.from));
										}
										case "move-data" -> {
											var moveDataTransformation = (MoveDataConfiguration) transformation;
											{
												currentVarNumber.addTo(moveDataTransformation.to, 1);
												currentVarTypeName.put(moveDataTransformation.to,
														Objects.requireNonNull(currentVarTypeName.get(moveDataTransformation.from))
												);
												currentVarTypeClass.put(moveDataTransformation.to,
														Objects.requireNonNull(currentVarTypeClass.get(moveDataTransformation.from))
												);
												currentVarFamily.put(moveDataTransformation.to,
														Objects.requireNonNull(currentVarFamily.get(moveDataTransformation.from))
												);
												if (currentVarUpgraded.remove(moveDataTransformation.from)) {
													currentVarUpgraded.add(moveDataTransformation.to);
												}
												currentVarDeleted.remove(moveDataTransformation.to);
												deserializeMethod.addStatement(
														"var $$field$$" + currentVarNumber.getInt(moveDataTransformation.to) + "$$"
																+ moveDataTransformation.to + " = $$field$$" + currentVarNumber.getInt(
																moveDataTransformation.from) + "$$" + moveDataTransformation.from);
											}
											{
												deserializeMethod.addComment(
														"Deleted $$field$$" + currentVarNumber.getInt(moveDataTransformation.from) + "$$"
																+ moveDataTransformation.from);
												currentVarNumber.addTo(moveDataTransformation.from, 1);
												currentVarTypeName.remove(moveDataTransformation.from);
												currentVarTypeClass.remove(moveDataTransformation.from);
												currentVarFamily.remove(moveDataTransformation.from);
												currentVarUpgraded.remove(moveDataTransformation.from);
												currentVarDeleted.add(moveDataTransformation.from);
											}
											currentTransformedFieldTypes.put(
													moveDataTransformation.transformClass + "." + moveDataTransformation.to,
													Objects.requireNonNull(currentTransformedFieldTypes.remove(
															moveDataTransformation.transformClass + "." + moveDataTransformation.from))
											);
										}
										case "upgrade-data" -> {
											var upgradeDataTransformation = (UpgradeDataConfiguration) transformation;
											TypeName fromType = currentTransformedFieldTypes.get(
													upgradeDataTransformation.transformClass + "." + upgradeDataTransformation.from);
											TypeName fromTypeBoxed = fromType.isPrimitive() ? fromType.box() : fromType;
											String toTypeName = nextVersion.get().getClassMap()
													.get(upgradeDataTransformation.transformClass)
													.getData()
													.get(upgradeDataTransformation.from);
											TypeName toType = nextVersionTypeTypes.get(toTypeName);
											if (toType == null) {
												throw new IllegalArgumentException(
														"Type " + toTypeName + " does not exist in version " + nextVersion
																.map(ComputedVersion::getName)
																.orElse("---"));
											}
											TypeName toTypeBoxed = toType.isPrimitive() ? toType.box() : toType;
											deserializeMethod.addStatement(
													"$T $$field$$" + (currentVarNumber.getInt(upgradeDataTransformation.from) + 1) + "$$"
															+ upgradeDataTransformation.from, toType);
											var dataUpgraderClass = ClassName.bestGuess(upgradeDataTransformation.upgrader);
											var dataUpgraderFieldName = dataUpgraderInstanceFieldName.computeIfAbsent(dataUpgraderClass,
													_unused -> {
														var dataUpgraderType = ParameterizedTypeName.get(ClassName.get(DataUpgrader.class),
																fromTypeBoxed,
																toTypeBoxed
														);
														var fieldName = "DATA_UPGRADER_" + nextDataUpgraderInstanceFieldId.getAndIncrement();
														var fieldSpec = FieldSpec.builder(dataUpgraderType,
																fieldName,
																Modifier.PRIVATE,
																Modifier.STATIC,
																Modifier.FINAL
														);
														fieldSpec.initializer("($T) new $T()", dataUpgraderType, dataUpgraderClass);
														upgraderClass.addField(fieldSpec.build());
														return fieldName;
													}
											);
											deserializeMethod.beginControlFlow("");
											deserializeMethod.addStatement(
													"$T upgraded = ($T) " + dataUpgraderFieldName + ".upgrade(($T) $$field$$"
															+ currentVarNumber.getInt(upgradeDataTransformation.from) + "$$"
															+ upgradeDataTransformation.from + ")", toType, toTypeBoxed, fromTypeBoxed);
											deserializeMethod.addStatement(
													"$$field$$" + (currentVarNumber.getInt(upgradeDataTransformation.from) + 1) + "$$"
															+ upgradeDataTransformation.from + " = upgraded");
											deserializeMethod.endControlFlow();
											Objects.requireNonNull(currentTransformedFieldTypes.remove(
													upgradeDataTransformation.transformClass + "." + upgradeDataTransformation.from));
											currentTransformedFieldTypes.put(
													upgradeDataTransformation.transformClass + "." + upgradeDataTransformation.from, toType);
											currentVarNumber.addTo(upgradeDataTransformation.from, 1);
											currentVarTypeName.put(upgradeDataTransformation.from, toTypeName);
											currentVarTypeClass.put(upgradeDataTransformation.from, toType);
											currentVarFamily.put(upgradeDataTransformation.from,
													Objects.requireNonNull(typeFamily.get(toTypeName),
															() -> "Type \"" + toTypeName + "\" has no type family!"
													)
											);
											currentVarUpgraded.add(upgradeDataTransformation.from);
											currentVarDeleted.remove(upgradeDataTransformation.from);
										}
										case "new-data" -> {
											var newDataTransformation = (NewDataConfiguration) transformation;
											String newTypeName = nextVersion.get().getClassMap()
													.get(newDataTransformation.transformClass)
													.getData()
													.get(newDataTransformation.to);
											TypeName newType = nextVersionTypeTypes.get(newTypeName);
											Objects.requireNonNull(newType,
													() -> "Type \"" + newTypeName + "\" is not present from next version " + versionConfiguration.getName()
															+ " to version " + nextVersion.get() + " in upgrader "
															+ newDataTransformation.transformClass + "." + newDataTransformation.to
											);
											TypeName newTypeBoxed = newType.isPrimitive() ? newType.box() : newType;
											{
												currentVarNumber.addTo(newDataTransformation.to, 1);
												currentVarTypeName.put(newDataTransformation.to, newTypeName);
												currentVarTypeClass.put(newDataTransformation.to, newType);
												currentVarFamily.put(newDataTransformation.to,
														Objects.requireNonNull(typeFamily.get(newTypeName),
																() -> "Type \"" + newTypeName + "\" has no type family!"
														)
												);
												currentVarUpgraded.add(newDataTransformation.to);
												currentVarDeleted.remove(newDataTransformation.to);

												var dataInitializerClass = ClassName.bestGuess(newDataTransformation.initializer);
												var dataInitializerFieldName = dataInitializerInstanceFieldName.computeIfAbsent(
														dataInitializerClass,
														_unused -> {
															var dataInitializerType = ParameterizedTypeName.get(ClassName.get(DataInitializer.class),
																	newTypeBoxed
															);
															var fieldName =
																	"DATA_INITIALIZER_" + nextDataInitializerInstanceFieldId.getAndIncrement();
															var fieldSpec = FieldSpec.builder(dataInitializerType,
																	fieldName,
																	Modifier.PRIVATE,
																	Modifier.STATIC,
																	Modifier.FINAL
															);
															fieldSpec.initializer("($T) new $T()", dataInitializerType, dataInitializerClass);
															upgraderClass.addField(fieldSpec.build());
															return fieldName;
														}
												);

												deserializeMethod.addStatement(
														"var $$field$$" + currentVarNumber.getInt(newDataTransformation.to) + "$$"
																+ newDataTransformation.to + " = " + dataInitializerFieldName + ".initialize()");
											}
											if (currentTransformedFieldTypes.put(
													newDataTransformation.transformClass + "." + newDataTransformation.to, newType) != null) {
												throw new IllegalStateException();
											}
										}
										default -> throw new UnsupportedOperationException(
												"Unknown transform type: " + transformation.getTransformName());
									}
								}
								deserializeMethod.addCode("\n");
								deserializeMethod.addComment(
										"Upgrade the remaining untouched values to the new version before returning");

								var nextVersionFieldTypes = nextVersion.get().getClassMap().get(type).getData();
								for (var e : currentVarNumber.object2IntEntrySet()) {
									String key = e.getKey();
									int number = e.getIntValue();
									if (!currentVarDeleted.contains(key) && !currentVarUpgraded.contains(key)) {
										Family currentFamily = Objects.requireNonNull(currentVarFamily.get(key));
										switch (currentFamily) {
											case OTHER:
											case SPECIAL_NATIVE:
												// Don't upgrade "other" families because they are already upgraded
												continue;
										}

										String toTypeName = nextVersionFieldTypes.get(key);
										Family toFamily = typeFamily.get(toTypeName);
										TypeName toType = nextVersionTypeTypes.get(toTypeName);
										Objects.requireNonNull(toType, "Field " + key + " of type "
												+ type + " is missing in version " + nextVersion.orElse(null));
										TypeName toTypeBoxed = toType.isPrimitive() ? toType.box() : toType;
										{
											currentVarNumber.addTo(key, 1);
											currentVarTypeName.put(key, toTypeName);
											currentVarTypeClass.put(key, toType);
											currentVarFamily.put(key, toFamily);
											currentVarUpgraded.add(key);
											currentVarDeleted.remove(key);

											switch (currentFamily) {
												case BASIC, GENERIC -> deserializeMethod.addCode(buildStatementUpgradeBaseType(versionPackage,
														nextVersionPackage.get(),
														number,
														key,
														toTypeName,
														toFamily,
														toType,
														toTypeBoxed
												));
												case I_TYPE_ARRAY -> deserializeMethod.addCode(buildStatementUpgradeITypeArrayField(
														versionPackage,
														nextVersionPackage.get(),
														number,
														key,
														toTypeName,
														toFamily,
														toType,
														toTypeBoxed
												));
												case NULLABLE_BASIC -> deserializeMethod.addCode(buildStatementUpgradeNullableBasicField(
														versionPackage,
														nextVersionPackage.get(),
														number,
														key,
														toTypeName,
														toFamily,
														toType,
														toTypeBoxed,
														nextVersionTypeTypes.get(toTypeName.substring(1))
												));
												case NULLABLE_GENERIC -> deserializeMethod.addCode(buildStatementUpgradeNullableGenericField(
														versionPackage,
														nextVersionPackage.get(),
														number,
														key,
														toTypeName,
														toFamily,
														toType,
														toTypeBoxed,
														nextVersionTypeTypes.get(toTypeName.substring(1))
												));
												case NULLABLE_OTHER -> deserializeMethod.addCode(buildStatementUpgradeNullableOtherField(
														versionPackage,
														nextVersionPackage.get(),
														number,
														key,
														toTypeName,
														toFamily,
														toType,
														toTypeBoxed
												));
												default -> throw new IllegalStateException("Unexpected value: " + currentFamily);
											}
										}
									}
								}

								deserializeMethod.addCode("return $T.of(", nextVersionClassType);
								AtomicBoolean isFirst = new AtomicBoolean(true);
								for (Entry<String, String> entry : nextVersionFieldTypes.entrySet()) {
									String field = entry.getKey();
									String fieldType = entry.getValue();
									if (!isFirst.getAndSet(false)) {
										deserializeMethod.addCode(", ");
									}
									if (currentVarNumber.getInt(field) < 0) {
										throw new IllegalStateException(
												"Field " + field + " in class " + type + " has an invalid var number ("
														+ currentVarNumber.getInt(field) + ") after upgrading from version " + versionConfiguration.getName()
														+ " to version " + nextVersion.map(ComputedVersion::getName).orElse("---"));
									}
									deserializeMethod.addCode("$$field$$" + currentVarNumber.getInt(field) + "$$" + field);
								}
								deserializeMethod.addStatement(")");
								upgraderClass.addMethod(deserializeMethod.build());
							}
							// Save the resulting class in the main package
							try {
								writeClass(generatedFilesToDelete, outPath, joinPackage(versionPackage, "upgraders"), upgraderClass);
							} catch (IOException e) {
								throw new IOError(e);
							}
						}
					}

				}
			}

			// Generate the super types serializers
			{
				for (Entry<String, Set<String>> entry : dataModel.getSuperTypes().entrySet()) {
					String type = entry.getKey();
					Set<String> superTypeConfiguration = entry.getValue();
					var classType = ClassName.get(joinPackage(versionPackage, "data"), type);

					// Create the super X serializer class
					{
						var serializerClass = TypeSpec.classBuilder(type + "Serializer");
						serializerClass.addModifiers(Modifier.PUBLIC);
						serializerClass.addModifiers(Modifier.FINAL);
						serializerClass.addSuperinterface(ParameterizedTypeName.get(ClassName.get(
								"it.cavallium.data.generator",
								"DataSerializer"
						), classType));
						// Create the INSTANCE field
						{
							var thisSerializerClassType = ClassName.get(joinPackage(versionPackage, "serializers"),
									type + "Serializer"
							);
							var instanceField = FieldSpec
									.builder(thisSerializerClassType, "INSTANCE")
									.initializer("new $T()", thisSerializerClassType);
							instanceField.addModifiers(Modifier.PUBLIC);
							instanceField.addModifiers(Modifier.STATIC);
							instanceField.addModifiers(Modifier.FINAL);
							serializerClass.addField(instanceField.build());
						}
						// Create the checkIdValidity method
						{
							var checkIdValidityMethod = MethodSpec.methodBuilder("checkIdValidity");
							checkIdValidityMethod.addModifiers(Modifier.PUBLIC);
							checkIdValidityMethod.addModifiers(Modifier.STATIC);
							checkIdValidityMethod.addModifiers(Modifier.FINAL);
							checkIdValidityMethod.returns(TypeName.VOID);
							checkIdValidityMethod.addParameter(ParameterSpec.builder(TypeName.INT, "id").build());
							checkIdValidityMethod.addException(IOException.class);
							checkIdValidityMethod.addStatement("if (id < 0) throw new $T(new $T(id))",
									IOException.class,
									IndexOutOfBoundsException.class
							);
							checkIdValidityMethod.addStatement(
									"if (id >= " + superTypeConfiguration.size() + ") throw new $T(new $T(id))",
									IOException.class,
									IndexOutOfBoundsException.class
							);
							serializerClass.addMethod(checkIdValidityMethod.build());
						}
						// Create the serialize method
						{
							var serializeMethod = createEmptySerializeMethod(classType);
							serializeMethod.addStatement("int id = data.getMetaId$$" + type + "()");
							serializeMethod.addCode("\n");
							serializeMethod.addStatement("checkIdValidity(id)");
							serializeMethod.addCode("\n");
							serializeMethod.addStatement("dataOutput.writeByte(id)");
							serializeMethod.addCode("\n");
							serializeMethod.beginControlFlow("switch (id)");
							int i = 0;
							for (String subType : superTypeConfiguration) {
								serializeMethod.beginControlFlow("case " + i + ":");
								serializeMethod.addStatement("$T." + subType + "SerializerInstance.serialize(dataOutput, ($T) data)",
										versionClassType,
										ClassName.get(joinPackage(versionPackage, "data"), subType)
								);
								serializeMethod.addStatement("break");
								serializeMethod.endControlFlow();
								i++;
							}
							serializeMethod.addStatement("default: throw new $T(new $T())",
									IOException.class,
									IndexOutOfBoundsException.class
							);
							serializeMethod.endControlFlow();
							serializerClass.addMethod(serializeMethod.build());
						}
						// Create the deserialize method
						{
							var deserializeMethod = createEmptyDeserializeMethod(classType);
							deserializeMethod.addStatement("int id = dataInput.readUnsignedByte()");
							deserializeMethod.addCode("\n");
							deserializeMethod.addStatement("checkIdValidity(id)");
							deserializeMethod.addCode("\n");
							deserializeMethod.beginControlFlow("switch (id)");
							int i = 0;
							for (String subType : superTypeConfiguration) {
								deserializeMethod.addStatement("case " + i + ": return " + typeDeserializeStatement.get(subType));
								i++;
							}
							deserializeMethod.addStatement("default: throw new $T(new $T())",
									IOException.class,
									IndexOutOfBoundsException.class
							);
							deserializeMethod.endControlFlow();
							serializerClass.addMethod(deserializeMethod.build());
						}
						// Save the resulting class in the main package
						try {
							writeClass(generatedFilesToDelete, outPath, joinPackage(versionPackage, "serializers"), serializerClass);
						} catch (IOException e) {
							throw new IOError(e);
						}
					}
				}
			}

			// Create the Version class
			{
				var versionClass = TypeSpec.classBuilder("Version");
				versionClass.addSuperinterface(ParameterizedTypeName.get(ClassName.get(joinPackage(basePackageName, ""), "IVersion"),
						ClassName.get(joinPackage(versionPackage, "data"), "IBaseType")
				));
				versionClass.addModifiers(Modifier.PUBLIC);
				versionClass.addModifiers(Modifier.FINAL);
				// Add a static variable for the current version
				{
					var versionNumberXField = FieldSpec
							.builder(TypeName.INT, "VERSION")
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.STATIC)
							.addModifiers(Modifier.FINAL)
							.initializer("$T." + versionConfiguration.getVersionVarName(),
									ClassName.get(joinPackage(basePackageName, ""), "Versions")
							)
							.build();
					versionClass.addField(versionNumberXField);
				}
				// Add a static instance for the current version
				{
					var versionInstanceField = FieldSpec
							.builder(versionClassType, "INSTANCE")
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.STATIC)
							.addModifiers(Modifier.FINAL)
							.initializer("new $T()", versionClassType)
							.build();
					versionClass.addField(versionInstanceField);
				}
				// Add upgrader instances static fields
				{
					for (Entry<String, TypeName> entry : typeOptionalUpgraders.entrySet()) {
						String type = entry.getKey();
						TypeName upgraderType = entry.getValue();
						var typeName = type;
						if (type.startsWith("§")) {
							typeName = "Array" + type.substring(1);
						} else if (type.startsWith("-")) {
							typeName = "Nullable" + type.substring(1);
						} else {
							typeName = type;
						}
						var upgraderStaticField = FieldSpec.builder(upgraderType, typeName + "UpgraderInstance");
						upgraderStaticField.addModifiers(Modifier.PUBLIC);
						upgraderStaticField.addModifiers(Modifier.STATIC);
						upgraderStaticField.addModifiers(Modifier.FINAL);
						upgraderStaticField.initializer("new $T()", upgraderType);
						versionClass.addField(upgraderStaticField.build());
					}
				}
				// Add serializer instances static fields
				{
					for (Entry<String, TypeName> entry : typeOptionalSerializers.entrySet()) {
						String type = entry.getKey();
						TypeName serializerType = entry.getValue();
						var typeName = type;
						if (type.startsWith("§")) {
							typeName = "Array" + type.substring(1);
						} else if (type.startsWith("-")) {
							typeName = "Nullable" + type.substring(1);
						} else {
							typeName = type;
						}
						var serializerStaticField = FieldSpec.builder(serializerType, typeName + "SerializerInstance");
						serializerStaticField.addModifiers(Modifier.PUBLIC);
						serializerStaticField.addModifiers(Modifier.STATIC);
						serializerStaticField.addModifiers(Modifier.FINAL);
						serializerStaticField.initializer("new $T()", serializerType);
						versionClass.addField(serializerStaticField.build());
					}
				}
				// Add upgradeToNextVersion method
				{
					if (nextVersion.isPresent()) {
						var upgradeToNextVersionMethodBuilder = MethodSpec
								.methodBuilder("upgradeToNextVersion")
								.addModifiers(Modifier.PUBLIC)
								.addModifiers(Modifier.STATIC)
								.addModifiers(Modifier.FINAL)
								.returns(ClassName.get(joinPackage(nextVersionPackage.get(), "data"), "IBaseType"))
								.addException(IOException.class)
								.addParameter(ParameterSpec
										.builder(ClassName.get(joinPackage(versionPackage, "data"), "IBaseType"), "oldData")
										.build())
								.beginControlFlow("switch (oldData.getBaseType$$()) ");
						for (Entry<String, ParsedClass> entry : versionConfiguration.getClassMap().entrySet()) {
							String type = entry.getKey();
							ParsedClass typeConfiguration = entry.getValue();
							var data = typeConfiguration.data;
							upgradeToNextVersionMethodBuilder.addStatement(
									"case " + type + ": return $T." + type + "UpgraderInstance.upgrade(($T) oldData)",
									versionClassType,
									ClassName.get(joinPackage(versionPackage, "data"), type)
							);
						}
						var upgradeToNextVersionMethod = upgradeToNextVersionMethodBuilder
								.beginControlFlow("default: ")
								.addStatement("throw new $T(\"Unknown type: \" + oldData.getBaseType$$())", IOException.class)
								.endControlFlow()
								.endControlFlow()
								.build();
						versionClass.addMethod(upgradeToNextVersionMethod);
					}
				}
				// Add getClass method
				{
					var getClassMethodBuilder = MethodSpec
							.methodBuilder("getClass")
							.addModifiers(Modifier.PUBLIC)
							.addAnnotation(Override.class)
							.returns(ParameterizedTypeName.get(ClassName.get(Class.class),
									WildcardTypeName.subtypeOf(ClassName.get(joinPackage(versionPackage, "data"), "IBaseType"))
							))
							.addParameter(ParameterSpec
									.builder(ClassName.get(joinPackage(basePackageName, ""), "BaseType"), "type")
									.build())
							.beginControlFlow("switch (type)");
					for (Entry<String, ParsedClass> entry : versionConfiguration.getClassMap().entrySet()) {
						String type = entry.getKey();
						ParsedClass typeConfiguration = entry.getValue();
						var data = typeConfiguration.data;

						getClassMethodBuilder.addStatement("case " + type + ": return $T.class",
								ClassName.get(joinPackage(versionPackage, "data"), type)
						);
					}
					getClassMethodBuilder
							.beginControlFlow("default: ")
							.addStatement("throw new $T(\"Unknown type: \" + type)", IllegalArgumentException.class)
							.endControlFlow()
							.endControlFlow();
					versionClass.addMethod(getClassMethodBuilder.build());
				}
				// Add getSerializer method
				{
					var getSerializerMethodBuilder = MethodSpec
							.methodBuilder("getSerializer")
							.addModifiers(Modifier.PUBLIC)
							.addAnnotation(Override.class)
							.addTypeVariable(TypeVariableName.get("T",
									ClassName.get(joinPackage(versionPackage, "data"), "IBaseType")
							))
							.returns(ParameterizedTypeName.get(ClassName.get(DataSerializer.class), TypeVariableName.get("T")))
							.addException(IOException.class)
							.addParameter(ParameterSpec
									.builder(ClassName.get(joinPackage(basePackageName, ""), "BaseType"), "type")
									.build())
							.beginControlFlow("switch (type)");
					for (Entry<String, ParsedClass> entry : versionConfiguration.getClassMap().entrySet()) {
						String type = entry.getKey();
						ParsedClass typeConfiguration = entry.getValue();
						var data = typeConfiguration.data;
						getSerializerMethodBuilder.addStatement("case " + type + ": return ($T) $T." + type + "SerializerInstance",
								ParameterizedTypeName.get(ClassName.get(DataSerializer.class), TypeVariableName.get("T")),
								versionClassType
						);
					}
					getSerializerMethodBuilder
							.beginControlFlow("default: ")
							.addStatement("throw new $T(\"Unknown type: \" + type)", IOException.class)
							.endControlFlow()
							.endControlFlow();
					versionClass.addMethod(getSerializerMethodBuilder.build());
				}
				// Add createNullableOf method
				{
					var createNullableOfMethod = MethodSpec
							.methodBuilder("createNullableOf")
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.FINAL)
							.returns(ClassName.get(joinPackage(versionPackage, "data.nullables"), "INullableIType"))
							.addException(IOException.class)
							.addParameter(ParameterSpec.builder(String.class, "type").build())
							.addParameter(ParameterSpec.builder(Object.class, "content").build())
							.beginControlFlow("switch (type)");
					for (String item : typeTypes.keySet()) {
						if (item.startsWith("-")) {
							String type = item.substring(1);
							if (!specialNativeTypes.contains(type)) {
								var nullableType = "Nullable" + type;
								createNullableOfMethod.addStatement("case \"" + nullableType + "\": return $T.ofNullable(($T) content)",
										typeTypes.get("-" + type),
										typeTypes.get(type)
								);
							}
						}
					}
					createNullableOfMethod
							.beginControlFlow("default: ")
							.addStatement("throw new $T(\"Unknown nullable type: \" + type)", IOException.class)
							.endControlFlow()
							.endControlFlow();
					versionClass.addMethod(createNullableOfMethod.build());
				}
				// Add createNullableOfBaseType method
				{
					var createNullableOfBaseTypeMethod = MethodSpec
							.methodBuilder("createNullableOfBaseType")
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.FINAL)
							.returns(ClassName.get(joinPackage(versionPackage, "data.nullables"), "INullableBaseType"))
							.addException(IOException.class)
							.addParameter(ParameterSpec.builder(ClassName.get(joinPackage(basePackageName, ""), "BaseType"), "type").build())
							.addParameter(ParameterSpec.builder(Object.class, "content").build())
							.beginControlFlow("switch (type)");
					for (String item : typeTypes.keySet()) {
						if (typeFamily.get(item) == Family.NULLABLE_BASIC) {
							String type = item.substring(1);
							if (!specialNativeTypes.contains(type)) {
								createNullableOfBaseTypeMethod.addStatement("case " + type + ": return $T.ofNullable(($T) content)",
										typeTypes.get("-" + type),
										typeTypes.get(type)
								);
							}
						}
					}
					createNullableOfBaseTypeMethod
							.beginControlFlow("default: ")
							.addStatement("throw new $T(\"Unknown nullable type: \" + type)", IOException.class)
							.endControlFlow()
							.endControlFlow();
					versionClass.addMethod(createNullableOfBaseTypeMethod.build());
				}
				// Add createNullableOfSuperType method
				{
					var createNullableOfSuperTypeMethod = MethodSpec
							.methodBuilder("createNullableOfSuperType")
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.FINAL)
							.returns(ClassName.get(joinPackage(versionPackage, "data.nullables"), "INullableSuperType"))
							.addException(IOException.class)
							.addParameter(ParameterSpec.builder(ClassName.get(joinPackage(basePackageName, ""), "SuperType"), "type").build())
							.addParameter(ParameterSpec.builder(Object.class, "content").build())
							.beginControlFlow("switch (type)");
					for (String item : typeTypes.keySet()) {
						if (typeFamily.get(item) == Family.NULLABLE_GENERIC) {
							String type = item.substring(1);
							if (!specialNativeTypes.contains(type)) {
								createNullableOfSuperTypeMethod.addStatement("case " + type + ": return $T.ofNullable(($T) content)",
										typeTypes.get("-" + type),
										typeTypes.get(type)
								);
							}
						}
					}
					createNullableOfSuperTypeMethod
							.beginControlFlow("default: ")
							.addStatement("throw new $T(\"Unknown nullable type: \" + type)", IOException.class)
							.endControlFlow()
							.endControlFlow();
					versionClass.addMethod(createNullableOfSuperTypeMethod.build());
				}
				// Add createArrayOf method
				{
					var createArrayOfMethod = MethodSpec
							.methodBuilder("createArrayOf")
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.FINAL)
							.returns(Object.class)
							.addException(IOException.class)
							.addParameter(ParameterSpec.builder(String.class, "type").build())
							.addParameter(ParameterSpec.builder(TypeName.INT, "length").build())
							.beginControlFlow("switch (type)");
					for (String item : typeTypes.keySet()) {
						if (item.startsWith("§")) {
							String type = item.substring(1);
							if (!specialNativeTypes.contains(type)) {
								createArrayOfMethod.addStatement("case \"" + type + "\": return new $T[length]", typeTypes.get(type));
							}
						}
					}
					createArrayOfMethod
							.beginControlFlow("default: ")
							.addStatement("throw new $T(\"Unknown nullable type: \" + type)", IOException.class)
							.endControlFlow()
							.endControlFlow();
					versionClass.addMethod(createArrayOfMethod.build());
				}
				// Add getVersion method
				{
					var getVersionMethod = MethodSpec
							.methodBuilder("getVersion")
							.addModifiers(Modifier.PUBLIC)
							.addAnnotation(Override.class)
							.returns(TypeName.INT)
							.addStatement("return VERSION")
							.build();
					versionClass.addMethod(getVersionMethod);
				}
				// Save the resulting class in the main package
				try {
					writeClass(generatedFilesToDelete, outPath, joinPackage(versionPackage, ""), versionClass);
				} catch (IOException e) {
					throw new IOError(e);
				}
			}

			// Create the interface IType
			{
				var iTypeInterfaceType = ClassName.get(joinPackage(versionPackage, "data"), "IType");
				var iTypeInterface = TypeSpec.interfaceBuilder("IType");
				iTypeInterface.addModifiers(Modifier.PUBLIC);
				iTypeInterface.addSuperinterface(ClassName.get(Serializable.class));
				try {
					writeClass(generatedFilesToDelete, outPath, joinPackage(versionPackage, "data"), iTypeInterface);
				} catch (IOException e) {
					throw new IOError(e);
				}
			}

			// Create the interface IBaseType
			{
				var iTypeInterfaceType = ClassName.get(joinPackage(versionPackage, "data"), "IType");
				var ibaseTypeInterfaceType = ClassName.get(joinPackage(versionPackage, "data"), "IBaseType");
				var ibaseTypeInterface = TypeSpec.interfaceBuilder("IBaseType");
				ibaseTypeInterface.addModifiers(Modifier.PUBLIC);
				ibaseTypeInterface.addSuperinterface(iTypeInterfaceType);
				// Create getBaseType$type method
				{
					var getBaseTypeMethod = MethodSpec
							.methodBuilder("getBaseType$")
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.ABSTRACT)
							.returns(ClassName.get(joinPackage(basePackageName, ""), "BaseType"));
					ibaseTypeInterface.addMethod(getBaseTypeMethod.build());
				}
				try {
					writeClass(generatedFilesToDelete, outPath, joinPackage(versionPackage, "data"), ibaseTypeInterface);
				} catch (IOException e) {
					throw new IOError(e);
				}
			}

			// Create the interface INullableIType
			{
				var iTypeInterfaceType = ClassName.get(joinPackage(versionPackage, "data"), "IType");
				var inullableITypeInterface = TypeSpec.interfaceBuilder("INullableIType");
				inullableITypeInterface.addModifiers(Modifier.PUBLIC);
				inullableITypeInterface.addSuperinterface(iTypeInterfaceType);
				inullableITypeInterface.addSuperinterface(INullable.class);
				try {
					writeClass(generatedFilesToDelete,
							outPath, joinPackage(versionPackage, "data.nullables"), inullableITypeInterface);
				} catch (IOException e) {
					throw new IOError(e);
				}
			}

			// Create the interface INullableBaseType
			{
				var iTypeInterfaceType = ClassName.get(joinPackage(versionPackage, "data.nullables"), "INullableIType");
				var inullableBaseTypeInterface = TypeSpec.interfaceBuilder("INullableBaseType");
				inullableBaseTypeInterface.addModifiers(Modifier.PUBLIC);
				inullableBaseTypeInterface.addSuperinterface(iTypeInterfaceType);
				var getBaseTypeMethod = MethodSpec
						.methodBuilder("getBaseType$")
						.addModifiers(Modifier.PUBLIC)
						.addModifiers(Modifier.ABSTRACT)
						.returns(ClassName.get(joinPackage(basePackageName, ""), "BaseType"));
				inullableBaseTypeInterface.addMethod(getBaseTypeMethod.build());
				try {
					writeClass(generatedFilesToDelete,
							outPath, joinPackage(versionPackage, "data.nullables"), inullableBaseTypeInterface);
				} catch (IOException e) {
					throw new IOError(e);
				}
			}

			// Create the interface INullableSuperType
			{
				var iTypeInterfaceType = ClassName.get(joinPackage(versionPackage, "data.nullables"), "INullableIType");
				var inullablesuperTypeInterface = TypeSpec.interfaceBuilder("INullableSuperType");
				inullablesuperTypeInterface.addModifiers(Modifier.PUBLIC);
				inullablesuperTypeInterface.addSuperinterface(iTypeInterfaceType);
				var getBaseTypeMethod = MethodSpec
						.methodBuilder("getSuperType$")
						.addModifiers(Modifier.PUBLIC)
						.addModifiers(Modifier.ABSTRACT)
						.returns(ClassName.get(joinPackage(basePackageName, ""), "SuperType"));
				inullablesuperTypeInterface.addMethod(getBaseTypeMethod.build());
				try {
					writeClass(generatedFilesToDelete,
							outPath, joinPackage(versionPackage, "data.nullables"), inullablesuperTypeInterface);
				} catch (IOException e) {
					throw new IOError(e);
				}
			}

			// Create the interfaces
			{
				for (Entry<String, Set<String>> superType : dataModel.getSuperTypes().entrySet()) {
					String type = superType.getKey();
					Set<String> superTypeConfiguration = superType.getValue();
					var iBaseTypeInterfaceType = ClassName.get(joinPackage(versionPackage, "data"), "IBaseType");
					var typeInterfaceType = ClassName.get(joinPackage(versionPackage, "data"), type);
					var typeInterface = TypeSpec.interfaceBuilder(type);
					typeInterface.addModifiers(Modifier.PUBLIC);
					typeInterface.addSuperinterface(iBaseTypeInterfaceType);
					var getMetaTypeMethod = MethodSpec
							.methodBuilder("getMetaId$" + type)
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.ABSTRACT)
							.returns(TypeName.INT);
					typeInterface.addMethod(getMetaTypeMethod.build());
					var getBaseTypeMethod = MethodSpec
							.methodBuilder("getBaseType$")
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.ABSTRACT)
							.returns(ClassName.get(joinPackage(basePackageName, ""), "BaseType"));
					typeInterface.addMethod(getBaseTypeMethod.build());

					// If it's the latest version, add the common methods
					if (nextVersion.isEmpty()) {
						var interfaceDataConfiguration = dataModel.getInterfaces().get(type);
						if (interfaceDataConfiguration != null) {
							// Extend this interface
							for (String extendedInterface : interfaceDataConfiguration.extendInterfaces) {
								typeInterface.addSuperinterface(ClassName.get(joinPackage(versionPackage, "data"), extendedInterface));
							}
							Map<String, CommonField> commonFields = new HashMap<>();
							for (Entry<String, String> e : interfaceDataConfiguration.commonData.entrySet()) {
								String key = e.getKey();
								String value = e.getValue();
								commonFields.put(key, new CommonField(key, value, true));
							}
							for (Entry<String, String> entry : interfaceDataConfiguration.commonGetters.entrySet()) {
								String field = entry.getKey();
								String fieldType = entry.getValue();
								commonFields.put(field, new CommonField(field, fieldType, false));
							}
							for (CommonField fieldInfo : commonFields.values()) {
								var fieldTypeType = fieldInfo.fieldType.equals("?") ? ClassName.get("java.lang", "Object")
										: typeTypes.get(fieldInfo.fieldType);
								// Add common data getter
								{
									var getterMethod = MethodSpec
											.methodBuilder(fieldInfo.fieldName)
											.addModifiers(Modifier.PUBLIC)
											.addModifiers(Modifier.ABSTRACT)
											.returns(fieldTypeType);
									if (!fieldTypeType.isPrimitive()) {
										getterMethod.addAnnotation(NotNull.class);
									}
									typeInterface.addMethod(getterMethod.build());
								}

								// Add common data setter
								if (fieldInfo.hasSetter) {
									if (!fieldInfo.fieldType.equals("?")) {
										var param = ParameterSpec.builder(fieldTypeType, fieldInfo.fieldName, Modifier.FINAL);
										if (!fieldTypeType.isPrimitive()) {
											param.addAnnotation(NotNull.class);
										}
										var setterMethod = MethodSpec
												.methodBuilder("set" + capitalize(fieldInfo.fieldName))
												.addModifiers(Modifier.PUBLIC)
												.addModifiers(Modifier.ABSTRACT)
												.addParameter(param.build())
												.returns(typeInterfaceType)
												.addAnnotation(NotNull.class);
										typeInterface.addMethod(setterMethod.build());
									}
								}
							}
						}
					}

					try {
						writeClass(generatedFilesToDelete, outPath, joinPackage(versionPackage, "data"), typeInterface);
					} catch (IOException e) {
						throw new IOError(e);
					}
				}
			}

			// Create the base types classes
			{
				for (Entry<String, ParsedClass> stringClassConfigurationEntry : versionConfiguration.getClassMap().entrySet()) {
					String type = stringClassConfigurationEntry.getKey();
					ParsedClass classConfiguration = stringClassConfigurationEntry.getValue();
					var typeClass = TypeSpec.recordBuilder(type);
					typeClass.addModifiers(Modifier.PUBLIC);
					typeClass.addModifiers(Modifier.STATIC);
					typeClass.addModifiers(Modifier.FINAL);
					typeClass.addSuperinterface(ClassName.get(joinPackage(versionPackage, "data"), "IBaseType"));
					if (nextVersion.isEmpty() && useRecordBuilders) {
						typeClass.addAnnotation(RecordBuilder.class);
					}
					var getBaseTypeMethod = MethodSpec
							.methodBuilder("getBaseType$")
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.FINAL)
							.addAnnotation(Override.class)
							.returns(ClassName.get(joinPackage(basePackageName, ""), "BaseType"))
							.addStatement("return $T." + type, ClassName.get(joinPackage(basePackageName, ""), "BaseType"));
					typeClass.addMethod(getBaseTypeMethod.build());
					var superTypes = dataModel.getSuperTypes()
							.entrySet()
							.parallelStream()
							.filter(((entry) -> entry.getValue().contains(type)))
							.map((entry) -> Map.entry(entry.getKey(), indexOf(entry.getValue(), type)))
							.collect(Collectors.toUnmodifiableSet());
					for (Entry<String, Integer> superType : superTypes) {
						typeClass.addSuperinterface(ClassName.get(joinPackage(versionPackage, "data"), superType.getKey()));

						var getMetaIdTypeField = FieldSpec
								.builder(TypeName.INT, "META_ID$" + capitalizeAll(superType.getKey()))
								.addModifiers(Modifier.PUBLIC)
								.addModifiers(Modifier.STATIC)
								.addModifiers(Modifier.FINAL)
								.initializer("" + superType.getValue());
						typeClass.addField(getMetaIdTypeField.build());

						var getMetaTypeMethod = MethodSpec
								.methodBuilder("getMetaId$" + superType.getKey())
								.addModifiers(Modifier.PUBLIC)
								.addModifiers(Modifier.FINAL)
								.addAnnotation(Override.class)
								.returns(TypeName.INT)
								.addStatement("return this." + "META_ID$$" + capitalizeAll(superType.getKey()));
						typeClass.addMethod(getMetaTypeMethod.build());
					}
					for (Entry<String, String> stringStringEntry : classConfiguration.getData().entrySet()) {
						String key = stringStringEntry.getKey();
						String value = stringStringEntry.getValue();
						var isGetterOverride = false;
						var isSetterOverride = false;
						if (nextVersion.isEmpty()) {
							for (Entry<String, Integer> superType : superTypes) {
								if (superType != null) {
									var interfaceCommonDataConfiguration = dataModel.getInterfaces().getOrDefault(superType.getKey(),
											null
									);
									if (interfaceCommonDataConfiguration != null
											&& interfaceCommonDataConfiguration.commonData.containsKey(key)
											&& !interfaceCommonDataConfiguration.commonData.get(key).equals("?")) {
										isGetterOverride = true;
										isSetterOverride = true;
									}
									if (interfaceCommonDataConfiguration != null
											&& interfaceCommonDataConfiguration.commonGetters.containsKey(key)
											&& !interfaceCommonDataConfiguration.commonGetters.get(key).equals("?")) {
										isGetterOverride = true;
									}
								}
							}
						}

						addField(typeClass, key, typeTypes.get(value), true, true, false);
						addImmutableSetter(typeClass,
								ClassName.get(joinPackage(versionPackage, "data"), type),
								classConfiguration.getData().keySet(),
								key,
								typeTypes.get(value),
								isSetterOverride
						);
					}
					// Add string representer only if this object is at the current version, the old data don't need a string representation...
					if (nextVersion.isEmpty() && classConfiguration.getStringRepresenter() != null) {
						typeClass.addMethod(MethodSpec
								.methodBuilder("toString")
								.addModifiers(Modifier.PUBLIC)
								.addAnnotation(Override.class)
								.returns(String.class)
								.addStatement("return " + classConfiguration.getStringRepresenter() + "(this)")
								.build());
					}

					var ofConstructor = MethodSpec
							.methodBuilder("of")
							.returns(typeTypes.get(type))
							.addModifiers(Modifier.STATIC)
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.FINAL);
					var mapConstructor = MethodSpec
							.methodBuilder("parse")
							.returns(typeTypes.get(type))
							.addModifiers(Modifier.STATIC)
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.FINAL);
					mapConstructor.addException(IllegalStateException.class);
					mapConstructor.addParameter(ParameterizedTypeName.get(Map.class, String.class, Object.class), "fields");
					mapConstructor.addStatement(
							"if (fields.size() != " + classConfiguration.getData().size() + ") throw new $T()",
							IllegalStateException.class
					);
					var returnMapNewInstanceStamentBuilder = CodeBlock.builder().add("return new $T(", typeTypes.get(type));
					var returnOfNewInstanceStamentBuilder = CodeBlock.builder().add("return new $T(", typeTypes.get(type));
					boolean first = true;
					for (Entry<String, String> entry : classConfiguration.getData().entrySet()) {
						if (!first) {
							returnMapNewInstanceStamentBuilder.add(", ");
							returnOfNewInstanceStamentBuilder.add(", ");
						} else {
							first = false;
						}
						String field = entry.getKey();
						String fieldType = entry.getValue();
						var fieldTypeType = typeTypes.get(fieldType);
						mapConstructor.addStatement("if (!fields.containsKey(\"" + field + "\")) throw new $T()",
								IllegalStateException.class
						);
						boolean requiresNotNull = !fieldTypeType.isPrimitive();
						if (requiresNotNull) {
							returnMapNewInstanceStamentBuilder.add("$T.requireNonNull(", Objects.class);
							returnOfNewInstanceStamentBuilder.add("$T.requireNonNull(", Objects.class);
						}
						returnMapNewInstanceStamentBuilder.add("($T) fields.get(\"" + field + "\")", typeTypes.get(fieldType));
						returnOfNewInstanceStamentBuilder.add("($T) " + field + "", typeTypes.get(fieldType));
						var fieldTypeName = typeTypes.get(fieldType);
						var parameterSpecBuilder = ParameterSpec.builder(fieldTypeName, "" + field);
						if (!fieldTypeName.isPrimitive()) {
							parameterSpecBuilder.addAnnotation(NotNull.class);
						}
						ofConstructor.addParameter(parameterSpecBuilder.build());
						if (requiresNotNull) {
							returnMapNewInstanceStamentBuilder.add(")");
							returnOfNewInstanceStamentBuilder.add(")");
						}
					}
					if (first) {
						typeClass.addField(FieldSpec
								.builder(typeTypes.get(type), "INSTANCE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
								.initializer("new $T()", typeTypes.get(type))
								.build());
						mapConstructor.addStatement("return INSTANCE");
						ofConstructor.addStatement("return INSTANCE");
					} else {
						mapConstructor.addStatement(returnMapNewInstanceStamentBuilder.add(")").build());
						ofConstructor.addStatement(returnOfNewInstanceStamentBuilder.add(")").build());
					}
					typeClass.addMethod(mapConstructor.build());
					typeClass.addMethod(ofConstructor.build());

					try {
						writeClass(generatedFilesToDelete, outPath, joinPackage(versionPackage, "data"), typeClass);
					} catch (IOException e) {
						throw new IOError(e);
					}
				}
			}

			for (Path pathToDelete : generatedFilesToDelete) {
				if (Files.exists(pathToDelete)) {
					Files.delete(outPath.resolve(pathToDelete));
					logger.info("Deleting unused file: {}", pathToDelete);
				}
			}
		}
	}

	private void markFileAsCreated(Set<Path> generatedFilesToDelete, Path basePath, Path filePath) {
		generatedFilesToDelete.remove(basePath.relativize(filePath));
	}

	private TypeName getImmutableArrayType(HashMap<String, TypeName> typeTypes, String typeString) {
		var type = typeTypes.get(typeString);
		return getImmutableArrayType(type);
	}

	private TypeName getImmutableArrayType(TypeName type) {
		return switch (type.toString()) {
			case "boolean" -> ClassName.get(BooleanList.class);
			case "byte" -> ClassName.get(ByteList.class);
			case "short" -> ClassName.get(ShortList.class);
			case "char" -> ClassName.get(CharList.class);
			case "int" -> ClassName.get(IntList.class);
			case "long" -> ClassName.get(LongList.class);
			case "float" -> ClassName.get(FloatList.class);
			case "double" -> ClassName.get(DoubleList.class);
			default -> ParameterizedTypeName.get(ClassName.get(List.class), type);
		};
	}

	private TypeName getArrayComponentType(TypeName listType) {
		if (listType instanceof ParameterizedTypeName) {
			return ((ParameterizedTypeName) listType).typeArguments.get(0);
		} else {
			return switch (listType.toString()) {
				case "BooleanList" -> ClassName.BOOLEAN;
				case "ByteList" -> ClassName.BYTE;
				case "ShortList" -> ClassName.SHORT;
				case "CharList" -> ClassName.CHAR;
				case "IntList" -> ClassName.INT;
				case "LongList" -> ClassName.LONG;
				case "FloatList" -> ClassName.FLOAT;
				case "DoubleList" -> ClassName.DOUBLE;
				default -> throw new IllegalStateException("Unexpected value: " + listType);
			};
		}
	}

	private CodeBlock buildStatementUpgradeBaseType(
			String versionPackage,
			String nextVersionPackage,
			int number,
			String key,
			String toTypeName,
			Family toFamily,
			TypeName toType,
			TypeName toTypeBoxed) {
		var deserializeMethod = CodeBlock.builder();
		String inputFieldName = "$field$" + number + "$" + key;
		String resultFieldName = "$field$" + (number + 1) + "$" + key;
		deserializeMethod.addStatement("$T $N", toType, resultFieldName);
		deserializeMethod.beginControlFlow("");
		deserializeMethod.addStatement("var value = $N", inputFieldName);

		var oldIBaseType = ClassName.get(joinPackage(versionPackage, "data"), "IBaseType");
		var upgradeBaseTypeField = MethodSpec.methodBuilder("upgradeBaseTypeField");
		upgradeBaseTypeField.addModifiers(Modifier.PRIVATE);
		upgradeBaseTypeField.addModifiers(Modifier.STATIC);
		upgradeBaseTypeField.addModifiers(Modifier.FINAL);
		upgradeBaseTypeField.returns(Object.class);
		upgradeBaseTypeField.addParameter(ParameterSpec
				.builder(oldIBaseType, "value")
				.addAnnotation(NotNull.class)
				.build());
		upgradeBaseTypeField.addException(IOException.class);

		var oldVersionType = ClassName.get(joinPackage(versionPackage, ""), "Version");
		var oldIType = ClassName.get(joinPackage(versionPackage, "data"), "IType");
		var oldINullableBaseType = ClassName.get(joinPackage(versionPackage, "data.nullables"),
				"INullableBaseType"
		);
		upgradeBaseTypeField.addStatement("$T.requireNonNull(value)", Objects.class);
		deserializeMethod.addStatement("$N = ($T) $T.upgradeToNextVersion(($T) value)",
				resultFieldName,
				toTypeBoxed,
				oldVersionType,
				oldIBaseType
		);

		deserializeMethod.endControlFlow();
		return deserializeMethod.build();
	}

	private CodeBlock buildStatementUpgradeNullableOtherField(
			String versionPackage,
			String nextVersionPackage,
			int number,
			String key,
			String toTypeName,
			Family toFamily,
			TypeName toType,
			TypeName toTypeBoxed) {
		var deserializeMethod = CodeBlock.builder();
		String inputFieldName = "$field$" + number + "$" + key;
		String resultFieldName = "$field$" + (number + 1) + "$" + key;
		deserializeMethod.addStatement("$T $N", toType, resultFieldName);
		deserializeMethod.beginControlFlow("");
		deserializeMethod.addStatement("var value = $N", inputFieldName);


		var oldINullableSuperType = ClassName.get(joinPackage(versionPackage, "data.nullables"),
				"INullableSuperType"
		);

		var oldVersionType = ClassName.get(joinPackage(versionPackage, ""), "Version");
		var oldIBaseType = ClassName.get(joinPackage(versionPackage, "data"), "IBaseType");
		var oldIType = ClassName.get(joinPackage(versionPackage, "data"), "IType");
		deserializeMethod.addStatement("$T.requireNonNull(value)", Objects.class);
		deserializeMethod.addStatement("var content = value.$$getNullable()");
		deserializeMethod.addStatement("$N = $T.ofNullable(content)", resultFieldName, toTypeBoxed);

		deserializeMethod.endControlFlow();
		return deserializeMethod.build();
	}

	private CodeBlock buildStatementUpgradeNullableGenericField(
			String versionPackage,
			String nextVersionPackage,
			int number,
			String key,
			String toTypeName,
			Family toFamily,
			TypeName toType,
			TypeName toTypeBoxed,
			TypeName toSuperType) {
		var deserializeMethod = CodeBlock.builder();
		String inputFieldName = "$field$" + number + "$" + key;
		String resultFieldName = "$field$" + (number + 1) + "$" + key;
		deserializeMethod.addStatement("$T $N", toType, resultFieldName);
		deserializeMethod.beginControlFlow("");
		deserializeMethod.addStatement("var value = $N", inputFieldName);


		var oldINullableSuperType = ClassName.get(joinPackage(versionPackage, "data.nullables"),
				"INullableSuperType"
		);

		var oldVersionType = ClassName.get(joinPackage(versionPackage, ""), "Version");
		var oldIBaseType = ClassName.get(joinPackage(versionPackage, "data"), "IBaseType");
		var oldIType = ClassName.get(joinPackage(versionPackage, "data"), "IType");
		deserializeMethod.addStatement("$T.requireNonNull(value)", Objects.class);
		deserializeMethod.addStatement("var content = value.$$getNullable()");
		deserializeMethod.addStatement("var newContent = content == null ? null : $T.requireNonNull(($T) $T.upgradeToNextVersion(($T) content))",
				ClassName.get(Objects.class),
				toSuperType,
				oldVersionType,
				oldIBaseType
		);
		deserializeMethod.addStatement("$N = $T.ofNullable(newContent)", resultFieldName, toTypeBoxed);

		deserializeMethod.endControlFlow();
		return deserializeMethod.build();
	}

	private CodeBlock buildStatementUpgradeNullableBasicField(
			String versionPackage,
			String nextVersionPackage,
			int number,
			String key,
			String toTypeName,
			Family toFamily,
			TypeName toType,
			TypeName toTypeBoxed,
			TypeName toBaseType) {
		var deserializeMethod = CodeBlock.builder();
		String inputFieldName = "$field$" + number + "$" + key;
		String resultFieldName = "$field$" + (number + 1) + "$" + key;
		deserializeMethod.addStatement("$T $N", toType, resultFieldName);
		deserializeMethod.beginControlFlow("");
		deserializeMethod.addStatement("var value = $N", inputFieldName);


		var oldINullableBaseType = ClassName.get(joinPackage(versionPackage, "data.nullables"),
				"INullableBaseType"
		);

		var oldVersionType = ClassName.get(joinPackage(versionPackage, ""), "Version");
		var oldIBaseType = ClassName.get(joinPackage(versionPackage, "data"), "IBaseType");
		var oldIType = ClassName.get(joinPackage(versionPackage, "data"), "IType");
		deserializeMethod.addStatement("$T.requireNonNull(value)", Objects.class);
		deserializeMethod.addStatement("var content = value.$$getNullable()");
		deserializeMethod.addStatement("var newContent = content == null ? null : $T.requireNonNull(($T) $T.upgradeToNextVersion(($T) content))",
				ClassName.get(Objects.class),
				toBaseType,
				oldVersionType,
				oldIBaseType
		);
		deserializeMethod.addStatement("$N = $T.ofNullable(newContent)", resultFieldName, toTypeBoxed);


		deserializeMethod.endControlFlow();
		return deserializeMethod.build();
	}

	private CodeBlock buildStatementUpgradeITypeArrayField(
			String versionPackage,
			@Nullable String nextVersionPackage,
			int number,
			String key,
			String toTypeName,
			Family toFamily,
			TypeName toType,
			TypeName toTypeBoxed) {
		var deserializeMethod = CodeBlock.builder();
		String inputFieldName = "$field$" + number + "$" + key;
		String resultFieldName = "$field$" + (number + 1) + "$" + key;
		deserializeMethod.addStatement("$T $N", toType, resultFieldName);
		deserializeMethod.beginControlFlow("");
		deserializeMethod.addStatement("var value = $N", inputFieldName);

		var oldVersionType = ClassName.get(joinPackage(versionPackage, ""), "Version");
		var oldIBaseType = ClassName.get(joinPackage(versionPackage, "data"), "IBaseType");
		var oldINullableBaseType = ClassName.get(joinPackage(versionPackage, "data.nullables"),
				"INullableBaseType"
		);
		deserializeMethod.addStatement("$T.requireNonNull(value)", Objects.class);
		deserializeMethod.addStatement("var newArray = new $T[value.size()]", getArrayComponentType(toType));
		deserializeMethod.beginControlFlow("for (int i = 0; i < value.size(); i++)");
		deserializeMethod.addStatement("var item = value.get(i)", Array.class);
		deserializeMethod.addStatement("var updatedItem = ($T) $T.upgradeToNextVersion(($T) item)",
				getArrayComponentType(toType),
				oldVersionType,
				oldIBaseType
		);
		deserializeMethod.addStatement("newArray[i] = updatedItem");
		deserializeMethod.endControlFlow();
		deserializeMethod.addStatement("$N = $T.of(newArray)",
				resultFieldName,
				toType instanceof ParameterizedTypeName ? ((ParameterizedTypeName) toType).rawType : toType
		);


		deserializeMethod.endControlFlow();
		return deserializeMethod.build();
	}

	private static String getSpecialNativePackage(String specialNativeType) {
		//noinspection SwitchStatementWithTooFewBranches
		return switch (specialNativeType) {
			case "Int52" -> "it.cavallium.data.generator.nativedata";
			default -> "java.lang";
		};
	}

	private void registerArrayType(String versionPackage,
			ClassName versionClassType,
			HashMap<String, TypeName> typeOptionalSerializers,
			HashMap<String, SerializeCodeBlockGenerator> typeSerializeStatement,
			HashMap<String, CodeBlock> typeDeserializeStatement,
			HashMap<String, Boolean> typeMustGenerateSerializer,
			String type) {
		typeOptionalSerializers.put("§" + type,
				ClassName.get(joinPackage(versionPackage, "serializers"), "Array" + type + "Serializer"));
		typeSerializeStatement.put("§" + type, new SerializeCodeBlockGenerator(
				CodeBlock.builder().add("$T.Array" + type + "SerializerInstance.serialize(dataOutput, ", versionClassType)
						.build(), CodeBlock.builder().add(")").build()));
		typeDeserializeStatement.put("§" + type,
				CodeBlock.builder().add("$T.Array" + type + "SerializerInstance.deserialize(dataInput)", versionClassType)
						.build());
		typeMustGenerateSerializer.put("§" + type, true);
	}

	private MethodSpec.Builder createEmptySerializeMethod(TypeName classType) {
		var serializeMethod = MethodSpec.methodBuilder("serialize");
		serializeMethod.addAnnotation(Override.class);
		serializeMethod.addModifiers(Modifier.PUBLIC);
		serializeMethod.addModifiers(Modifier.FINAL);
		serializeMethod.returns(TypeName.VOID);
		serializeMethod.addParameter(ParameterSpec.builder(DataOutput.class, "dataOutput").build());
		serializeMethod
				.addParameter(ParameterSpec.builder(classType, "data").addAnnotation(NotNull.class).build());
		serializeMethod.addException(IOException.class);
		serializeMethod.addStatement("$T.requireNonNull(data)", Objects.class);
		return serializeMethod;
	}

	private MethodSpec.Builder createEmptyDeserializeMethod(TypeName classType) {
		var deserializeMethod = MethodSpec.methodBuilder("deserialize");
		deserializeMethod.addAnnotation(Override.class);
		deserializeMethod.addAnnotation(NotNull.class);
		deserializeMethod.addModifiers(Modifier.PUBLIC);
		deserializeMethod.addModifiers(Modifier.FINAL);
		deserializeMethod.returns(classType);
		deserializeMethod.addParameter(ParameterSpec.builder(DataInput.class, "dataInput").build());
		deserializeMethod.addException(IOException.class);
		return deserializeMethod;
	}

	public record NeededTypes(
				boolean nullableTypeNeeded,
				boolean nextVersionNullableTypeNeeded,
				boolean arrayTypeNeeded,
				boolean nextVersionArrayTypeNeeded){}

	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	public NeededTypes registerNeededTypes(ComputedVersion versionConfiguration,
			Family family,
			String type,
			Optional<ComputedVersion> nextVersion,
			Optional<String> nextVersionPackage,
			ClassName versionClassType,
			String versionPackage,
			HashMap<String, TypeName> typeOptionalSerializers,
			HashMap<String, SerializeCodeBlockGenerator> typeSerializeStatement,
			HashMap<String, CodeBlock> typeDeserializeStatement,
			HashMap<String, Boolean> typeMustGenerateSerializer,
			HashMap<String, TypeName> typeTypes,
			HashMap<String, Family> typeFamily,
			@Nullable HashMap<String, TypeName> nextVersionTypeTypes,
			@Nullable HashMap<String, Family> nextVersionTypeFamily,
			Supplier<TypeName> arrayClassName,
			Supplier<TypeName> nextArrayClassName) {
		// Check if the nullable type is needed
		boolean nullableTypeNeeded = versionConfiguration.getClassMap()
				.values()
				.parallelStream()
				.map(ParsedClass::getData)
				.map(Map::values)
				.flatMap(Collection::parallelStream)
				.filter((typeZ) -> typeZ.startsWith("-"))
				.map((typeZ) -> typeZ.substring(1))
				.anyMatch((typeZ) -> typeZ.equals(type));

		boolean nextVersionNullableTypeNeeded = nextVersion
				.filter(s -> s.getClassMap()
						.values()
						.parallelStream()
						.map(ParsedClass::getData)
						.map(Map::values)
						.flatMap(Collection::parallelStream)
						.filter((typeZ) -> typeZ.startsWith("-"))
						.map((typeZ) -> typeZ.substring(1))
						.anyMatch((typeZ) -> typeZ.equals(type)))
				.isPresent();

		Family nullableFamily = switch (family) {
			case BASIC -> Family.NULLABLE_BASIC;
			case GENERIC -> Family.NULLABLE_GENERIC;
			case OTHER -> Family.NULLABLE_OTHER;
			default -> throw new IllegalStateException("Unexpected value: " + family);
		};

		if (nullableTypeNeeded) {
			typeOptionalSerializers.put("-" + type,
					ClassName.get(joinPackage(versionPackage, "serializers"), "Nullable" + type + "Serializer"));
			typeSerializeStatement.put("-" + type, new SerializeCodeBlockGenerator(CodeBlock.builder()
					.add("$T.Nullable" + type + "SerializerInstance.serialize(dataOutput, ", versionClassType).build(),
					CodeBlock.builder().add(")").build()));
			typeDeserializeStatement.put("-" + type, CodeBlock.builder()
					.add("$T.Nullable" + type + "SerializerInstance.deserialize(dataInput)", versionClassType).build());
			typeMustGenerateSerializer.put("-" + type, true);
			typeTypes.put("-" + type, ClassName.get(joinPackage(versionPackage, "data.nullables"), "Nullable" + type));
		}
		if (typeFamily != null) {
			typeFamily.put("-" + type, nullableFamily);
		}

		if (nextVersionNullableTypeNeeded) {
			assert nextVersionTypeTypes != null;
			assert nextVersionTypeFamily != null;
			nextVersionTypeTypes.put("-" + type, ClassName.get(joinPackage(nextVersionPackage.orElseThrow(), "data.nullables"), "Nullable" + type));
		}
		if (nextVersionTypeFamily != null) {
			nextVersionTypeFamily.put("-" + type, nullableFamily);
		}

		// Check if the array type is needed
		boolean arrayTypeNeeded = versionConfiguration.getClassMap()
				.values()
				.parallelStream()
				.map(ParsedClass::getData)
				.map(Map::values)
				.flatMap(Collection::parallelStream)
				.filter((typeZ) -> typeZ.startsWith("§"))
				.map((typeZ) -> typeZ.substring(1))
				.anyMatch((typeZ) -> typeZ.equals(type));

		boolean nextVersionArrayTypeNeeded = nextVersion.filter(s -> s.getClassMap()
				.values()
				.parallelStream()
				.map(ParsedClass::getData)
				.map(Map::values)
				.flatMap(Collection::parallelStream)
				.filter((typeZ) -> typeZ.startsWith("§"))
				.map((typeZ) -> typeZ.substring(1))
				.anyMatch((typeZ) -> typeZ.equals(type))
		).isPresent();

		if (arrayTypeNeeded) {
			registerArrayType(versionPackage,
					versionClassType,
					typeOptionalSerializers,
					typeSerializeStatement,
					typeDeserializeStatement,
					typeMustGenerateSerializer,
					type
			);
			typeTypes.put("§" + type, getImmutableArrayType(arrayClassName.get()));
		}
		if (typeFamily != null) {
			typeFamily.put("§" + type, Family.I_TYPE_ARRAY);
		}

		if (nextVersionArrayTypeNeeded) {
			assert nextVersionTypeTypes != null;
			assert nextVersionTypeFamily != null;
			nextVersionTypeTypes.put("§" + type, getImmutableArrayType(nextArrayClassName.get()));
		}
		if (nextVersionTypeFamily != null) {
			nextVersionTypeFamily.put("§" + type, Family.I_TYPE_ARRAY);
		}

		return new NeededTypes(nullableTypeNeeded,
				nextVersionNullableTypeNeeded,
				arrayTypeNeeded,
				nextVersionArrayTypeNeeded
		);
	}

	private String capitalizeAll(String text) {
		StringBuilder sb = new StringBuilder();
		boolean firstChar = true;
		for (char c : text.toCharArray()) {
			if (Character.isUpperCase(c) && !firstChar) {
				sb.append('_');
				sb.append(c);
			} else {
				sb.append(Character.toUpperCase(c));
			}
			firstChar = false;
		}
		return sb.toString();
	}

	private void addImmutableSetter(Builder classBuilder, TypeName classType, Collection<String> fieldNames,
			String fieldName, TypeName fieldType, boolean isOverride) {
		var setterMethod = MethodSpec.methodBuilder("set" + capitalize(fieldName));
		setterMethod.addModifiers(Modifier.PUBLIC);
		setterMethod.addModifiers(Modifier.FINAL);
		setterMethod.addAnnotation(NotNull.class);
		var param = ParameterSpec.builder(fieldType, fieldName, Modifier.FINAL);
		if (!fieldType.isPrimitive()) {
			param.addAnnotation(NotNull.class);
		}
		if (isOverride) {
			setterMethod.addAnnotation(Override.class);
		}
		setterMethod.addParameter(param.build());
		setterMethod.returns(classType);
		if (!fieldType.isPrimitive()) {
			setterMethod.addStatement("$T.requireNonNull(" + fieldName + ")", Objects.class);

			setterMethod.beginControlFlow("if ($T.equals(" + fieldName + ", this." + fieldName + "))", Objects.class);
			setterMethod.addStatement("return this");
			setterMethod.endControlFlow();
		} else {
			setterMethod.beginControlFlow("if (" + fieldName + " == this." + fieldName + ")");
			setterMethod.addStatement("return this");
			setterMethod.endControlFlow();
		}
		setterMethod.addCode("$[return $T.of(\n$]", classType);
		setterMethod.addCode("$>");
		AtomicInteger i = new AtomicInteger(fieldNames.size());
		for (String otherFieldName : fieldNames) {
			boolean isLast = i.decrementAndGet() == 0;
			setterMethod.addCode(otherFieldName).addCode((isLast ? "" : ",") + "\n");
		}
		setterMethod.addCode("$<");
		setterMethod.addStatement(")");
		classBuilder.addMethod(setterMethod.build());
	}

	private void addField(Builder classBuilder, String fieldName,
			TypeName fieldType, boolean isRecord, boolean isFinal, boolean hasSetter) {
		if (isFinal && hasSetter) {
			throw new IllegalStateException();
		}
		if (hasSetter) {
			throw new UnsupportedOperationException();
		}
		if (isRecord) {
			var field = ParameterSpec.builder(fieldType, fieldName);
			if (!fieldType.isPrimitive()) {
				field.addAnnotation(NotNull.class);
			}
			if (!isFinal) {
				throw new IllegalArgumentException("Record fields must be final");
			}
			classBuilder.addRecordComponent(field.build());
		} else {
			var field = FieldSpec.builder(fieldType, fieldName, Modifier.PRIVATE);
			if (!fieldType.isPrimitive()) {
				field.addAnnotation(NotNull.class);
			}
			if (isFinal) {
				field.addModifiers(Modifier.FINAL);
			}
			classBuilder.addField(field.build());
		}
	}

	private int indexOf(Set<String> value, String type) {
		if (!value.contains(type)) {
			return -1;
		}
		int i = 0;
		for (String s : value) {
			if (type.equals(s)) {
				break;
			}
			i++;
		}
		return i;
	}

	private String capitalize(String field) {
		return Character.toUpperCase(field.charAt(0)) + field.substring(1);
	}

	@Deprecated
	private String getVersionPackage(String basePackageName, ComputedVersion version) {
		return version.getPackage(basePackageName);
	}

	private String joinPackage(String basePackageName, String packageName) {
		if (basePackageName.isBlank()) {
			basePackageName = "org.generated";
		}
		if (packageName.isBlank()) {
			return basePackageName;
		} else {
			return basePackageName + "." + packageName;
		}
	}

	private void writeClass(HashSet<Path> generatedFilesToDelete,
			Path outPath,
			String classPackage,
			Builder versionsClass) throws IOException {
		var sb = new StringBuilder();
		var typeSpec = versionsClass.build();
		var outJavaFile = outPath;
		for (String part : classPackage.split("\\.")) {
			outJavaFile = outJavaFile.resolve(part);
		}
		if (Files.notExists(outJavaFile)) {
			Files.createDirectories(outJavaFile);
		}
		outJavaFile = outJavaFile.resolve(typeSpec.name + ".java");
		JavaFile.builder(classPackage, typeSpec).build().writeTo(sb);
		String newFile = sb.toString();
		boolean mustWrite;
		if (Files.isRegularFile(outJavaFile) && Files.isReadable(outJavaFile)) {
			String oldFile = Files.readString(outJavaFile, StandardCharsets.UTF_8);
			mustWrite = !oldFile.equals(newFile);
		} else {
			mustWrite = true;
		}
		if (mustWrite) {
			logger.debug("File {} changed", outJavaFile);
			Files.writeString(outJavaFile, newFile, StandardCharsets.UTF_8, TRUNCATE_EXISTING, CREATE, WRITE);
		} else {
			logger.debug("File {} is the same, unchanged", outJavaFile);
		}
		markFileAsCreated(generatedFilesToDelete, outPath, outJavaFile);
	}

	@Deprecated
	private String getVersionVarName(ComputedVersion computedVersion) {
		return computedVersion.getVersionVarName();
	}

	private String getVersionShortInt(ComputedVersion version) {
		return Integer.toString(version.getVersion());
	}

	private enum Family {
		BASIC,
		NULLABLE_BASIC,
		NULLABLE_OTHER,
		I_TYPE_ARRAY,
		SPECIAL_NATIVE, GENERIC, NULLABLE_GENERIC, OTHER
	}
}
