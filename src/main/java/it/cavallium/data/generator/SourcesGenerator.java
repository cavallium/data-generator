package it.cavallium.data.generator;

import com.squareup.javapoet.AnnotationSpec;
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
import it.cavallium.data.generator.nativedata.IGenericNullable;
import it.cavallium.data.generator.nativedata.StringSerializer;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import it.cavallium.data.generator.SourcesGeneratorConfiguration.ClassConfiguration;
import it.cavallium.data.generator.SourcesGeneratorConfiguration.CustomTypesConfiguration;
import it.cavallium.data.generator.SourcesGeneratorConfiguration.InterfaceDataConfiguration;
import it.cavallium.data.generator.SourcesGeneratorConfiguration.MoveDataConfiguration;
import it.cavallium.data.generator.SourcesGeneratorConfiguration.NewDataConfiguration;
import it.cavallium.data.generator.SourcesGeneratorConfiguration.RemoveDataConfiguration;
import it.cavallium.data.generator.SourcesGeneratorConfiguration.UpgradeDataConfiguration;
import it.cavallium.data.generator.SourcesGeneratorConfiguration.VersionConfiguration;
import it.cavallium.data.generator.SourcesGeneratorConfiguration.VersionTransformation;

@SuppressWarnings({"SameParameterValue", "unused"})
public class SourcesGenerator {

	private static final Logger logger = LoggerFactory.getLogger(SourcesGenerator.class);

	private final SourcesGeneratorConfiguration configuration;

	private SourcesGenerator(InputStream yamlDataStream) {
		Yaml yaml = new Yaml();
		this.configuration = yaml.loadAs(yamlDataStream, SourcesGeneratorConfiguration.class);
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
	 */
	public void generateSources(String basePackageName, Path outPath) throws IOException {

		// Fix the configuration
		for (Entry<String, InterfaceDataConfiguration> interfacesDatum : configuration.interfacesData.entrySet()) {
			String k = interfacesDatum.getKey();
			InterfaceDataConfiguration value = interfacesDatum.getValue();
			value.commonData.replaceAll((field, fieldType) -> fixType(fieldType));
		}
		for (Entry<String, InterfaceDataConfiguration> interfacesDatum : configuration.interfacesData.entrySet()) {
			String name = interfacesDatum.getKey();
			InterfaceDataConfiguration value = interfacesDatum.getValue();
			value.commonGetters.replaceAll((field, fieldType) -> fixType(fieldType));
		}
		for (Entry<String, VersionConfiguration> stringVersionConfigurationEntry : configuration.versions.entrySet()) {
			String k = stringVersionConfigurationEntry.getKey();
			VersionConfiguration config = stringVersionConfigurationEntry.getValue();
			for (Entry<String, ClassConfiguration> entry : config.classes.entrySet()) {
				String clazz = entry.getKey();
				ClassConfiguration classConfiguration = entry.getValue();
				classConfiguration.getData().replaceAll((field, fieldType) -> fixType(fieldType));
			}
		}

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
		for (Entry<String, VersionConfiguration> stringVersionConfigurationEntry : configuration.versions.entrySet()) {
			String version = stringVersionConfigurationEntry.getKey();
			VersionConfiguration value = stringVersionConfigurationEntry.getValue();
			// Add a static variable for this version, containing the normalized version number
			var versionNumberField = FieldSpec
					.builder(TypeName.INT, getVersionVarName(version))
					.addModifiers(Modifier.PUBLIC)
					.addModifiers(Modifier.STATIC)
					.addModifiers(Modifier.FINAL)
					.initializer(getVersionShortInt(version))
					.build();
			// Add the fields to the class
			versionsClass.addField(versionNumberField);

			var versionPackage = getVersionPackage(configuration.currentVersion, basePackageName, version);
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
		writeClass(outPath, joinPackage(basePackageName, ""), versionsClass);

		// Create the BasicType class
		{
			var basicTypeClass = TypeSpec.enumBuilder("BasicType");
			basicTypeClass.addModifiers(Modifier.PUBLIC);
			for (Entry<String, VersionConfiguration> stringVersionConfigurationEntry : configuration.versions.entrySet()) {
				String k = stringVersionConfigurationEntry.getKey();
				VersionConfiguration value = stringVersionConfigurationEntry.getValue();
				for (String basicTypeName : value.classes.keySet()) {
					if (!basicTypeClass.enumConstants.containsKey(basicTypeName)) {
						basicTypeClass.addEnumConstant(basicTypeName);
					}
				}
			}
			// Save the resulting class in the main package
			writeClass(outPath, joinPackage(basePackageName, ""), basicTypeClass);
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
								.builder(ClassName.get(joinPackage(basePackageName, ""), "BasicType"), "type")
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
								.builder(ClassName.get(joinPackage(basePackageName, ""), "BasicType"), "type")
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
			writeClass(outPath, joinPackage(basePackageName, ""), iVersionClass);
		}

		// Create the CurrentVersion class
		{
			var currentVersionClass = TypeSpec.classBuilder("CurrentVersion");
			currentVersionClass.addModifiers(Modifier.PUBLIC);
			currentVersionClass.addModifiers(Modifier.FINAL);
			// Add a static variable for the current version
			{
				var versionNumberField = FieldSpec.builder(ClassName
						.get(getVersionPackage(configuration.currentVersion, basePackageName, configuration.currentVersion),
								"Version"), "VERSION").addModifiers(Modifier.PUBLIC).addModifiers(Modifier.STATIC)
						.addModifiers(Modifier.FINAL).initializer(
								"new " + getVersionPackage(configuration.currentVersion, basePackageName, configuration.currentVersion)
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
												.get(joinPackage(getVersionPackage(configuration.currentVersion, basePackageName, configuration.currentVersion), "data"),
														"IType")))))
						.addCode("return $T.of(\n", Set.class);
				AtomicBoolean isFirst = new AtomicBoolean(true);
				for (Entry<String, Set<String>> entry : configuration.versions.get(configuration.currentVersion).superTypes.entrySet()) {
					String superTypeName = entry.getKey();
					Set<String> superTypeConfig = entry.getValue();
					if (!isFirst.getAndSet(false)) {
						getSuperTypeClasses.addCode(",\n");
					}
					getSuperTypeClasses.addCode("$T.class",
							ClassName.get(joinPackage(getVersionPackage(configuration.currentVersion,
									basePackageName,
									configuration.currentVersion
							), "data"), superTypeName)
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
												.get(joinPackage(getVersionPackage(configuration.currentVersion, basePackageName, configuration.currentVersion), "data"),
														"IBasicType")))));
				getSuperTypeSubtypesClasses.addParameter(ParameterSpec.builder(ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(ClassName
						.get(joinPackage(getVersionPackage(configuration.currentVersion, basePackageName, configuration.currentVersion), "data"),
								"IType"))), "superTypeClass").build());
				getSuperTypeSubtypesClasses.beginControlFlow("switch (superTypeClass.getCanonicalName())");
				for (Entry<String, Set<String>> entry : configuration.versions.get(configuration.currentVersion).superTypes.entrySet()) {
					String superTypeName = entry.getKey();
					Set<String> subTypes = entry.getValue();
					getSuperTypeSubtypesClasses.beginControlFlow("case \"" + ClassName
							.get(joinPackage(getVersionPackage(configuration.currentVersion,
									basePackageName,
									configuration.currentVersion
							), "data"), superTypeName)
							.canonicalName() + "\":");
					getSuperTypeSubtypesClasses.addCode("return $T.of(\n", Set.class);
					AtomicBoolean isFirst = new AtomicBoolean(true);
					for (String subTypeName : subTypes) {
						if (!isFirst.getAndSet(false)) {
							getSuperTypeSubtypesClasses.addCode(",\n");
						}
						getSuperTypeSubtypesClasses.addCode("$T.class",
								ClassName.get(joinPackage(getVersionPackage(configuration.currentVersion,
										basePackageName,
										configuration.currentVersion
								), "data"), subTypeName)
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
						.get(joinPackage(getVersionPackage(configuration.currentVersion, basePackageName, configuration.currentVersion), "data"),
								"IBasicType")))
						.addModifiers(Modifier.PUBLIC).addModifiers(Modifier.STATIC).addModifiers(Modifier.FINAL).returns(TypeVariableName.get("U"))
						.addParameter(ParameterSpec.builder(TypeName.INT, "oldVersion").build()).addParameter(
								ParameterSpec.builder(ClassName.get(joinPackage(basePackageName, ""), "BasicType"), "type").build())
						.addParameter(ParameterSpec.builder(DataInput.class, "oldDataInput").build())
						.addException(IOException.class).beginControlFlow("switch (oldVersion)");
				AtomicInteger seqNumber = new AtomicInteger(0);
				for (Entry<String, VersionConfiguration> entry : configuration.versions.entrySet()) {
					String version = entry.getKey();
					VersionConfiguration versionConfiguration = entry.getValue();
// Add a case in which the data version deserializes the serialized data and upgrades it
					upgradeDataToLatestVersion1MethodBuilder.beginControlFlow("case $T." + getVersionVarName(version) + ":",
							ClassName.get(joinPackage(basePackageName, ""), "Versions")
					);
					upgradeDataToLatestVersion1MethodBuilder.addStatement(
							"var deserialized" + seqNumber.incrementAndGet() + " = " + getVersionPackage(configuration.currentVersion,
									basePackageName,
									version
							) + ".Version.INSTANCE.getSerializer(type).deserialize(oldDataInput)");
					upgradeDataToLatestVersion1MethodBuilder.addStatement(
							"return upgradeDataToLatestVersion(Versions." + getVersionVarName(version) + ", deserialized"
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
				var upgradeDataToLatestVersion2MethodBuilder = MethodSpec.methodBuilder("upgradeDataToLatestVersion")
						.addModifiers(Modifier.PUBLIC).addModifiers(Modifier.STATIC).addModifiers(Modifier.FINAL).addTypeVariable(TypeVariableName.get("T"))
						.addTypeVariable(TypeVariableName.get("U", ClassName
								.get(joinPackage(getVersionPackage(configuration.currentVersion, basePackageName, configuration.currentVersion), "data"),
										"IBasicType"))).returns(TypeVariableName.get("U"))
						.addParameter(ParameterSpec.builder(TypeName.INT, "oldVersion").build())
						.addParameter(ParameterSpec.builder(TypeVariableName.get("T"), "oldData").build())
						.addException(IOException.class).beginControlFlow("switch (oldVersion)");
				AtomicInteger seqNumber = new AtomicInteger(0);
				for (Entry<String, VersionConfiguration> entry : configuration.versions.entrySet()) {
					String version = entry.getKey();
					VersionConfiguration versionConfiguration = entry.getValue();
// Add a case in which the data version deserializes the serialized data and upgrades it
					upgradeDataToLatestVersion2MethodBuilder.beginControlFlow("case $T." + getVersionVarName(version) + ":",
							ClassName.get(joinPackage(basePackageName, ""), "Versions")
					);
					if (version.equalsIgnoreCase(configuration.currentVersion)) {
						// This is the latest version, don't upgrade.
						upgradeDataToLatestVersion2MethodBuilder.addStatement("return ($T) oldData", TypeVariableName.get("U"));
					} else {
						// Upgrade
						upgradeDataToLatestVersion2MethodBuilder.addStatement(
								"var upgradedData" + seqNumber.incrementAndGet() + " = "
										+ getVersionPackage(configuration.currentVersion, basePackageName, version)
										+ ".Version.upgradeToNextVersion(($T) oldData)",
								ClassName.get(joinPackage(getVersionPackage(configuration.currentVersion, basePackageName, version),
										"data"
								), "IBasicType")
						);
						upgradeDataToLatestVersion2MethodBuilder.addStatement(
								"return upgradeDataToLatestVersion(Versions." + getVersionVarName(findNextVersion(configuration,
										version
								).orElseThrow()) + ", upgradedData" + seqNumber.get() + ")");
					}
					upgradeDataToLatestVersion2MethodBuilder.endControlFlow();
				}
				var upgradeDataToLatestVersion2Method = upgradeDataToLatestVersion2MethodBuilder.beginControlFlow("default:")
						.addStatement("throw new $T(\"Unknown version: \" + oldVersion)", IOException.class).endControlFlow()
						.endControlFlow().build();
				currentVersionClass.addMethod(upgradeDataToLatestVersion2Method);
			}
			// Save the resulting class in the main package
			writeClass(outPath, joinPackage(basePackageName, "current"), currentVersionClass);
		}

		for (Entry<String, VersionConfiguration> mapEntry : configuration.versions.entrySet()) {
			String version = mapEntry.getKey();
			VersionConfiguration versionConfiguration = mapEntry.getValue();
			var versionPackage = getVersionPackage(configuration.currentVersion, basePackageName, version);
			var versionClassType = ClassName.get(joinPackage(versionPackage, ""), "Version");
			var nextVersion = findNextVersion(configuration, version);
			var nextVersionPackage = nextVersion.map((nextVersionValue) -> getVersionPackage(configuration.currentVersion,
					basePackageName,
					nextVersionValue
			));

			logger.info(
					"Found version configuration:\n{\n\tversion: \"" + version + "\",\n\tversionPackage: \"" + versionPackage
							+ "\",\n\tnextVersion: \"" + nextVersion.orElse("unknown") + "\",\n\tnextVersionPackage: \""
							+ nextVersionPackage.orElse("unknown") + "\"\n}");

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
			@Nullable HashMap<String, TypeName> nextVersionTypeTypes;
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
			} else {
				nextVersionTypeTypes = null;
			}
			Set<String> specialNativeTypes = Set.of("String",
					"boolean",
					"short",
					"char",
					"int",
					"long",
					"float",
					"double",
					"byte"
			);

			// Generate the type statements
			{
				// Generate the native types
				for (String specialNativeType : specialNativeTypes) {
					if (Character.isUpperCase(specialNativeType.charAt(0))) {
						typeTypes.put(specialNativeType, ClassName.get("java.lang", specialNativeType));
						if (nextVersion.isPresent()) {
							nextVersionTypeTypes.put(specialNativeType, ClassName.get("java.lang", specialNativeType));
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
					typeTypes.put("§" + specialNativeType, ArrayTypeName.of(typeTypes.get(specialNativeType)));
					if (nextVersion.isPresent()) {
						nextVersionTypeTypes.put("-" + specialNativeType,
								ClassName.get("it.cavallium.data.generator.nativedata", "Nullable" + specialNativeType)
						);
						nextVersionTypeTypes.put("§" + specialNativeType, ArrayTypeName.of(typeTypes.get(specialNativeType)));
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

				// Setup only the basic types upgraders variables
				for (String s : versionConfiguration.classes.keySet()) {
					if (nextVersion.isPresent()) {
						typeOptionalUpgraders.put(s, ClassName.get(joinPackage(versionPackage, "upgraders"), s + "Upgrader"));
					}
				}

				// Generate the basic and super types
				Stream
						.concat(versionConfiguration.classes.keySet().stream(), versionConfiguration.superTypes.keySet().stream())
						.forEach((type) -> {
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
							if (nextVersion.isPresent()) {
								nextVersionTypeTypes.put(type, ClassName.get(joinPackage(nextVersionPackage.get(), "data"), type));
							}

							NeededTypes neededTypes = registerNeededTypes(versionConfiguration,
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
									nextVersionTypeTypes,
									() -> ClassName.get(joinPackage(versionPackage, "data"), type),
									() -> ClassName.get(joinPackage(nextVersionPackage.orElseThrow(), "data"), type)
							);
						});

				// Generate the special types
				for (Entry<String, CustomTypesConfiguration> entry : versionConfiguration.customTypes.entrySet()) {
					String key = entry.getKey();
					CustomTypesConfiguration customTypeConfiguration = entry.getValue();
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
					typeTypes.put(key, ClassName.bestGuess(customTypeConfiguration.javaClass));
					if (nextVersion.isPresent()) {
						nextVersionTypeTypes.put(key, ClassName.bestGuess(customTypeConfiguration.javaClass));
					}

					var arrayClassName = ClassName.bestGuess(customTypeConfiguration.javaClass);
					var neededTypes = registerNeededTypes(versionConfiguration,
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
							nextVersionTypeTypes,
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
							case '$':
								logger.debug("Found array: " + type.substring(1));
								break;
							case '-':
								logger.debug("Found nullable type: " + type.substring(1));
								break;
							default:
								logger.debug("Found type: " + type);
								break;
						}
					}
				}
			}

			// Check if all types exist
			{
				for (Entry<String, ClassConfiguration> e : versionConfiguration.classes.entrySet()) {
					String type = e.getKey();
					ClassConfiguration typeConfig = e.getValue();
					for (Entry<String, String> entry : typeConfig.data.entrySet()) {
						String field = entry.getKey();
						String fieldType = entry.getValue();
						if (!typeTypes.containsKey(fieldType)) {
							throw new UnsupportedOperationException(
									"Unknown type '" + fieldType + "' of field '" + field + "' in class '" + type + "' in version '"
											+ version + "'");
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
									writeClass(outPath, joinPackage(versionPackage, "serializers"), nullableSerializerClass);
								} catch (IOException e) {
									throw new IOError(e);
								}
							}

							// Create the nullable X types classes
							{
								var typeType = typeTypes.get(substring);
								var nullableTypeType = typeTypes.get("-" + substring);
								var nullableTypeClass = TypeSpec.classBuilder("Nullable" + capitalize(substring));
								nullableTypeClass.addModifiers(Modifier.PUBLIC);
								nullableTypeClass.addModifiers(Modifier.FINAL);
								nullableTypeClass.addAnnotation(EqualsAndHashCode.class);
								nullableTypeClass.addAnnotation(ToString.class);
								nullableTypeClass.addSuperinterface(ClassName.get(joinPackage(versionPackage, "data.nullables"),
										"INullableBasicType"
								));
								nullableTypeClass.addSuperinterface(IGenericNullable.class);
								var constructor = MethodSpec.constructorBuilder();
								constructor.addModifiers(Modifier.PUBLIC);
								constructor.addParameter(ParameterSpec.builder(typeType, "value").build());
								constructor.addStatement("this.value = value");
								nullableTypeClass.addMethod(constructor.build());
								var valueField = FieldSpec.builder(typeType, "value");
								nullableTypeClass.addField(valueField.build());
								var ofMethod = MethodSpec.methodBuilder("of");
								ofMethod.addModifiers(Modifier.PUBLIC);
								ofMethod.addModifiers(Modifier.STATIC);
								ofMethod.addModifiers(Modifier.FINAL);
								ofMethod.addException(NullPointerException.class);
								ofMethod.returns(nullableClassType);
								ofMethod.addParameter(ParameterSpec.builder(typeType, "value").build());
								ofMethod.beginControlFlow("if (value == null)");
								ofMethod.addStatement("throw new $T()", NullPointerException.class);
								ofMethod.nextControlFlow("else");
								ofMethod.addStatement("return new $T(value)", nullableTypeType);
								ofMethod.endControlFlow();
								nullableTypeClass.addMethod(ofMethod.build());
								var ofNullableMethod = MethodSpec.methodBuilder("ofNullable");
								ofNullableMethod.addModifiers(Modifier.PUBLIC);
								ofNullableMethod.addModifiers(Modifier.STATIC);
								ofNullableMethod.addModifiers(Modifier.FINAL);
								ofNullableMethod.returns(nullableClassType);
								ofNullableMethod.addParameter(ParameterSpec.builder(typeType, "value").build());
								ofNullableMethod.addStatement("return new $T(value)", nullableTypeType);
								nullableTypeClass.addMethod(ofNullableMethod.build());
								var emptyMethod = MethodSpec.methodBuilder("empty");
								emptyMethod.addModifiers(Modifier.PUBLIC);
								emptyMethod.addModifiers(Modifier.STATIC);
								emptyMethod.addModifiers(Modifier.FINAL);
								emptyMethod.returns(nullableClassType);
								emptyMethod.addStatement("return new $T(null)", nullableTypeType);
								nullableTypeClass.addMethod(emptyMethod.build());
								var isEmptyMethod = MethodSpec.methodBuilder("isEmpty");
								isEmptyMethod.addModifiers(Modifier.PUBLIC);
								isEmptyMethod.addModifiers(Modifier.FINAL);
								isEmptyMethod.returns(TypeName.BOOLEAN);
								isEmptyMethod.addStatement("return value == null");
								nullableTypeClass.addMethod(isEmptyMethod.build());
								var isPresentMethod = MethodSpec.methodBuilder("isPresent");
								isPresentMethod.addModifiers(Modifier.PUBLIC);
								isPresentMethod.addModifiers(Modifier.FINAL);
								isPresentMethod.returns(TypeName.BOOLEAN);
								isPresentMethod.addStatement("return value != null");
								nullableTypeClass.addMethod(isPresentMethod.build());
								var getMethod = MethodSpec.methodBuilder("get");
								getMethod.addModifiers(Modifier.PUBLIC);
								getMethod.addModifiers(Modifier.FINAL);
								getMethod.addException(NullPointerException.class);
								getMethod.addAnnotation(NotNull.class);
								getMethod.addAnnotation(NonNull.class);
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
										.addAnnotation(NonNull.class)
										.build());
								orElseMethod.addModifiers(Modifier.PUBLIC);
								orElseMethod.addModifiers(Modifier.FINAL);
								orElseMethod.addException(NullPointerException.class);
								orElseMethod.addAnnotation(NotNull.class);
								orElseMethod.addAnnotation(NonNull.class);
								orElseMethod.returns(typeType);
								orElseMethod.beginControlFlow("if (value == null)");
								orElseMethod.addStatement("return defaultValue");
								orElseMethod.nextControlFlow("else");
								orElseMethod.addStatement("return value");
								orElseMethod.endControlFlow();
								nullableTypeClass.addMethod(orElseMethod.build());
								var getNullableMethod = MethodSpec.methodBuilder("getNullable");
								getNullableMethod.addModifiers(Modifier.PUBLIC);
								getNullableMethod.addModifiers(Modifier.FINAL);
								getNullableMethod.addAnnotation(Nullable.class);
								getNullableMethod.returns(typeType);
								getNullableMethod.addStatement("return value");
								nullableTypeClass.addMethod(getNullableMethod.build());
								var getDollarNullableMethod = MethodSpec.methodBuilder("$getNullable");
								getDollarNullableMethod.addModifiers(Modifier.PUBLIC);
								getDollarNullableMethod.addModifiers(Modifier.FINAL);
								getDollarNullableMethod.addAnnotation(Nullable.class);
								getDollarNullableMethod.addAnnotation(Override.class);
								getDollarNullableMethod.returns(typeType);
								getDollarNullableMethod.addStatement("return this.getNullable()");
								nullableTypeClass.addMethod(getDollarNullableMethod.build());

								try {
									writeClass(outPath, joinPackage(versionPackage, "data.nullables"), nullableTypeClass);
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
							var arrayClassType = ArrayTypeName.of(classType);

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
									serializeMethod.addStatement("dataOutput.writeInt(data.length)");
									serializeMethod.beginControlFlow("for (int i = 0; i < data.length; i++)");
									serializeMethod.addStatement(typeSerializeStatement.get(substring).generate("data[i]"));
									serializeMethod.endControlFlow();
									arraySerializerClass.addMethod(serializeMethod.build());
								}
								// Create the deserialize method
								{
									var deserializeMethod = createEmptyDeserializeMethod(arrayClassType);
									deserializeMethod.addStatement("var data = new $T[dataInput.readInt()]", classType);
									deserializeMethod.beginControlFlow("for (int i = 0; i < data.length; i++)");
									deserializeMethod.addStatement(CodeBlock.join(List.of(CodeBlock.of("data[i] ="),
											typeDeserializeStatement.get(substring)
									), " "));
									deserializeMethod.endControlFlow();
									deserializeMethod.addStatement("return data");
									arraySerializerClass.addMethod(deserializeMethod.build());
								}
								// Save the resulting class in the main package
								try {
									writeClass(outPath, joinPackage(versionPackage, "serializers"), arraySerializerClass);
								} catch (IOException e) {
									throw new IOError(e);
								}
							}
						}
					}
				}
			}

			// Generate the basic types serializers
			{
				for (Entry<String, ClassConfiguration> classConfigurationEntry : versionConfiguration.classes.entrySet()) {
					String type = classConfigurationEntry.getKey();
					ClassConfiguration basicTypeConfiguration = classConfigurationEntry.getValue();
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
							if (basicTypeConfiguration.data != null) {
								for (Entry<String, String> entry : basicTypeConfiguration.data.entrySet()) {
									String field = entry.getKey();
									String fieldType = entry.getValue();
									serializeMethod.addStatement(typeSerializeStatement
											.get(fieldType)
											.generate("data.get" + capitalize(field) + "()"));
								}
							}
							serializerClass.addMethod(serializeMethod.build());
						}
						// Create the deserialize method
						{
							var deserializeMethod = createEmptyDeserializeMethod(classType);
							deserializeMethod.addCode("$[return $T.of(\n$]", classType);
							deserializeMethod.addCode("$>");
							AtomicInteger i = new AtomicInteger(basicTypeConfiguration.data.size());
							for (Entry<String, String> entry : basicTypeConfiguration.data.entrySet()) {
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
							writeClass(outPath, joinPackage(versionPackage, "serializers"), serializerClass);
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
								deserializeMethod.addAnnotation(NonNull.class);
								deserializeMethod.addAnnotation(Override.class);
								deserializeMethod.addModifiers(Modifier.PUBLIC);
								deserializeMethod.addModifiers(Modifier.FINAL);
								deserializeMethod.returns(nextVersionClassType);
								deserializeMethod.addParameter(ParameterSpec
										.builder(classType, "data")
										.addAnnotation(NotNull.class)
										.addAnnotation(NonNull.class)
										.build());
								deserializeMethod.addException(IOException.class);
								Object2IntLinkedOpenHashMap<String> currentVarNumber = new Object2IntLinkedOpenHashMap<>(
										basicTypeConfiguration.getData().size());
								ObjectOpenHashSet<String> currentVarDeleted = new ObjectOpenHashSet<>();
								currentVarNumber.defaultReturnValue(-1);
								deserializeMethod.addStatement("$T.requireNonNull(data)", Objects.class);
								for (Entry<String, String> stringStringEntry : basicTypeConfiguration.getData().entrySet()) {
									String k = stringStringEntry.getKey();
									String value = stringStringEntry.getValue();
									currentVarNumber.addTo(k, 1);
									deserializeMethod.addStatement("var $$field$$" + 0 + "$$" + k + " = data.get" + capitalize(k) + "()");
								}

								List<VersionTransformation> list = new ArrayList<>();
								for (VersionTransformation versionTransformation : configuration.versions.get(nextVersion.get()).transformations) {
									if (versionTransformation.isForClass(type)) {
										list.add(versionTransformation);
									}
								}
								var transformations = Collections.unmodifiableList(list);
								AtomicInteger transformationNumber = new AtomicInteger(0);
								HashMap<String, TypeName> currentTransformedFieldTypes = new HashMap<>();
								for (Entry<String, ClassConfiguration> stringClassConfigurationEntry : versionConfiguration.classes.entrySet()) {
									String className = stringClassConfigurationEntry.getKey();
									ClassConfiguration classConfiguration = stringClassConfigurationEntry.getValue();
									for (Entry<String, String> entry : classConfiguration.getData().entrySet()) {
										String fieldName = entry.getKey();
										String fieldType = entry.getValue();
										currentTransformedFieldTypes.put(className + "." + fieldName, typeTypes.get(fieldType));
									}
								}
								for (VersionTransformation transformationConfig : transformations) {
									var transformation = transformationConfig.getTransformation();
									deserializeMethod.addCode("\n");
									deserializeMethod.addComment("TRANSFORMATION #" + transformationNumber.incrementAndGet() + ": "
											+ transformation.getTransformName());
									switch (transformation.getTransformName()) {
										case "remove-data":
											var removeDataTransformation = (RemoveDataConfiguration) transformation;
										{
											deserializeMethod.addComment(
													"Deleted $$field$$" + currentVarNumber.getInt(removeDataTransformation.from) + "$$"
															+ removeDataTransformation.from);
											currentVarNumber.addTo(removeDataTransformation.from, 1);
											currentVarDeleted.add(removeDataTransformation.from);
										}

										Objects.requireNonNull(currentTransformedFieldTypes.remove(
												removeDataTransformation.transformClass + "." + removeDataTransformation.from));
										break;
										case "move-data":
											var moveDataTransformation = (MoveDataConfiguration) transformation;

										{
											currentVarNumber.addTo(moveDataTransformation.to, 1);
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
											currentVarDeleted.add(moveDataTransformation.from);
										}

										currentTransformedFieldTypes.put(
												moveDataTransformation.transformClass + "." + moveDataTransformation.to,
												Objects.requireNonNull(currentTransformedFieldTypes.remove(
														moveDataTransformation.transformClass + "." + moveDataTransformation.from))
										);
										break;
										case "upgrade-data":
											var upgradeDataTransformation = (UpgradeDataConfiguration) transformation;
											TypeName fromType = currentTransformedFieldTypes.get(
													upgradeDataTransformation.transformClass + "." + upgradeDataTransformation.from);
											TypeName fromTypeBoxed = fromType.isPrimitive() ? fromType.box() : fromType;
											String toTypeName = configuration.versions.get(nextVersion.get()).classes
													.get(upgradeDataTransformation.transformClass)
													.getData()
													.get(upgradeDataTransformation.from);
											TypeName toType = nextVersionTypeTypes.get(toTypeName);
											TypeName toTypeBoxed = toType.isPrimitive() ? toType.box() : toType;
											deserializeMethod.addStatement(
													"$T $$field$$" + (currentVarNumber.getInt(upgradeDataTransformation.from) + 1) + "$$"
															+ upgradeDataTransformation.from, toType);
											deserializeMethod.beginControlFlow("try");
											deserializeMethod.addStatement("var upgrader = (($T) new $T())",
													ParameterizedTypeName.get(ClassName.get(DataUpgrader.class), fromTypeBoxed, toTypeBoxed),
													ClassName.bestGuess(upgradeDataTransformation.upgrader)
											);
											deserializeMethod.addStatement(
													"$T upgraded = ($T) upgrader.upgrade(($T) $$field$$" + currentVarNumber.getInt(
															upgradeDataTransformation.from) + "$$" + upgradeDataTransformation.from + ")",
													toType,
													toTypeBoxed,
													fromTypeBoxed
											);

											deserializeMethod.addStatement(
													"$$field$$" + (currentVarNumber.getInt(upgradeDataTransformation.from) + 1) + "$$"
															+ upgradeDataTransformation.from + " = upgraded");
											deserializeMethod.nextControlFlow(" catch ($T e)", ClassCastException.class);
											deserializeMethod.addStatement("throw new $T(e)", IllegalArgumentException.class);
											deserializeMethod.endControlFlow();
											Objects.requireNonNull(currentTransformedFieldTypes.remove(
													upgradeDataTransformation.transformClass + "." + upgradeDataTransformation.from));
											currentTransformedFieldTypes.put(
													upgradeDataTransformation.transformClass + "." + upgradeDataTransformation.from, toType);

											currentVarNumber.addTo(upgradeDataTransformation.from, 1);
											currentVarDeleted.remove(upgradeDataTransformation.from);
											break;
										case "new-data":
											var newDataTransformation = (NewDataConfiguration) transformation;
											String newTypeName = configuration.versions.get(nextVersion.get()).classes
													.get(newDataTransformation.transformClass)
													.getData()
													.get(newDataTransformation.to);
											TypeName newType = nextVersionTypeTypes.get(newTypeName);
											TypeName newTypeBoxed = newType.isPrimitive() ? newType.box() : newType;
										{
											currentVarNumber.addTo(newDataTransformation.to, 1);
											currentVarDeleted.remove(newDataTransformation.to);
											deserializeMethod.addStatement(
													"var $$field$$" + currentVarNumber.getInt(newDataTransformation.to) + "$$"
															+ newDataTransformation.to + " = (($T) new $T()).initialize()",
													ParameterizedTypeName.get(ClassName.get(DataInitializer.class), newTypeBoxed),
													ClassName.bestGuess(newDataTransformation.initializer)
											);
										}
										if (currentTransformedFieldTypes.put(
												newDataTransformation.transformClass + "." + newDataTransformation.to, newType) != null) {
											throw new IllegalStateException();
										}
										break;
										default:
											throw new UnsupportedOperationException(
													"Unknown transform type: " + transformation.getTransformName());
									}
								}
								deserializeMethod.addCode("\n");
								deserializeMethod.addComment(
										"Upgrade the remaining untouched values to the new version before returning");

								var nextVersionFieldTypes = configuration.versions.get(nextVersion.get()).classes.get(type).getData();
								for (var e : currentVarNumber.object2IntEntrySet()) {
									String key = e.getKey();
									int number = e.getIntValue();
									if (!currentVarDeleted.contains(key)) {
										String toTypeName = nextVersionFieldTypes.get(key);
										TypeName toType = nextVersionTypeTypes.get(toTypeName);
										TypeName toTypeBoxed = toType.isPrimitive() ? toType.box() : toType;
										{
											currentVarNumber.addTo(key, 1);
											currentVarDeleted.remove(key);
											deserializeMethod.addStatement(
													"$T $$field$$" + (number + 1) + "$$" + key + " = ($T) " + "upgradeUnknownField($$field$$"
															+ number + "$$" + key + ")", toType, toTypeBoxed);
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
									deserializeMethod.addCode("$$field$$" + currentVarNumber.getInt(field) + "$$" + field);
								}
								deserializeMethod.addStatement(")");
								upgraderClass.addMethod(deserializeMethod.build());
							}
							// Create the upgradeUnknownField method
							{
								var upgradeUnknownField = MethodSpec.methodBuilder("upgradeUnknownField");
								upgradeUnknownField.addModifiers(Modifier.PRIVATE);
								upgradeUnknownField.addModifiers(Modifier.STATIC);
								upgradeUnknownField.addModifiers(Modifier.FINAL);
								upgradeUnknownField.returns(Object.class);
								upgradeUnknownField.addParameter(ParameterSpec
										.builder(Object.class, "value")
										.addAnnotation(NotNull.class)
										.addAnnotation(NonNull.class)
										.build());
								upgradeUnknownField.addException(IOException.class);

								var oldVersionType = ClassName.get(joinPackage(versionPackage, ""), "Version");
								var oldIBasicType = ClassName.get(joinPackage(versionPackage, "data"), "IBasicType");
								var oldIType = ClassName.get(joinPackage(versionPackage, "data"), "IType");
								var oldINullableBasicType = ClassName.get(joinPackage(versionPackage, "data.nullables"),
										"INullableBasicType"
								);
								var newIBasicType = ClassName.get(joinPackage(nextVersionPackage.get(), "data"), "IBasicType");
								upgradeUnknownField.addStatement("$T.requireNonNull(value)", Objects.class);
								upgradeUnknownField.addStatement("Class<?> type = ((Object) value).getClass()");
								upgradeUnknownField.beginControlFlow(
										"if (type.isArray() && $T.class.isAssignableFrom(type.getComponentType()))",
										oldIType
								);
								upgradeUnknownField.addStatement("int arrayLength = $T.getLength(value)", Array.class);
								upgradeUnknownField.addStatement(
										"Object newArray = $T.INSTANCE.createArrayOf(type.getComponentType().getSimpleName(), arrayLength)",
										ClassName.get(nextVersionPackage.get(), "Version")
								);
								upgradeUnknownField.beginControlFlow("for (int i = 0; i < arrayLength; i++)");
								upgradeUnknownField.addStatement("var item = $T.get(value, i)", Array.class);
								upgradeUnknownField.addStatement("var updatedItem = $T.upgradeToNextVersion(($T) item)",
										oldVersionType,
										oldIBasicType
								);
								upgradeUnknownField.addStatement("$T.set(newArray, i, updatedItem)", Array.class);
								upgradeUnknownField.endControlFlow();
								upgradeUnknownField.addStatement("return newArray");
								upgradeUnknownField.nextControlFlow("else if (value instanceof $T)", oldINullableBasicType);
								upgradeUnknownField.addStatement("var content = (($T) value).$$getNullable()", IGenericNullable.class);
								upgradeUnknownField.addStatement("$T newContent", Object.class);
								upgradeUnknownField.beginControlFlow("if (content instanceof $T)", oldIBasicType);
								upgradeUnknownField.addStatement("newContent = ($T) $T.upgradeToNextVersion(($T) content)",
										newIBasicType,
										oldVersionType,
										oldIBasicType
								);
								upgradeUnknownField.nextControlFlow("else");
								upgradeUnknownField.addStatement("newContent = content");
								upgradeUnknownField.endControlFlow();
								upgradeUnknownField.addStatement("return $T.INSTANCE.createNullableOf(type.getSimpleName(), newContent)",
										ClassName.get(nextVersionPackage.get(), "Version")
								);
								upgradeUnknownField.nextControlFlow("else if (value instanceof $T)", oldIBasicType);
								upgradeUnknownField.addStatement("return ($T) $T.upgradeToNextVersion(($T) value)",
										newIBasicType,
										oldVersionType,
										oldIBasicType
								);
								upgradeUnknownField.nextControlFlow("else");
								upgradeUnknownField.addStatement("return value");
								upgradeUnknownField.endControlFlow();
								upgraderClass.addMethod(upgradeUnknownField.build());
							}
							// Save the resulting class in the main package
							try {
								writeClass(outPath, joinPackage(versionPackage, "upgraders"), upgraderClass);
							} catch (IOException e) {
								throw new IOError(e);
							}
						}
					}

				}
			}

			// Generate the super types serializers
			{
				for (Entry<String, Set<String>> entry : versionConfiguration.superTypes.entrySet()) {
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
							writeClass(outPath, joinPackage(versionPackage, "serializers"), serializerClass);
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
						ClassName.get(joinPackage(versionPackage, "data"), "IBasicType")
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
							.initializer("$T." + getVersionVarName(version),
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
								.returns(ClassName.get(joinPackage(nextVersionPackage.get(), "data"), "IBasicType"))
								.addException(IOException.class)
								.addParameter(ParameterSpec
										.builder(ClassName.get(joinPackage(versionPackage, "data"), "IBasicType"), "oldData")
										.build())
								.beginControlFlow("switch (oldData.getBasicType$$()) ");
						for (Entry<String, ClassConfiguration> entry : versionConfiguration.classes.entrySet()) {
							String type = entry.getKey();
							ClassConfiguration typeConfiguration = entry.getValue();
							var data = typeConfiguration.data;
							upgradeToNextVersionMethodBuilder.addStatement(
									"case " + type + ": return $T." + type + "UpgraderInstance.upgrade(($T) oldData)",
									versionClassType,
									ClassName.get(joinPackage(versionPackage, "data"), type)
							);
						}
						var upgradeToNextVersionMethod = upgradeToNextVersionMethodBuilder
								.beginControlFlow("default: ")
								.addStatement("throw new $T(\"Unknown type: \" + oldData.getBasicType$$())", IOException.class)
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
									WildcardTypeName.subtypeOf(ClassName.get(joinPackage(versionPackage, "data"), "IBasicType"))
							))
							.addParameter(ParameterSpec
									.builder(ClassName.get(joinPackage(basePackageName, ""), "BasicType"), "type")
									.build())
							.beginControlFlow("switch (type)");
					for (Entry<String, ClassConfiguration> entry : versionConfiguration.classes.entrySet()) {
						String type = entry.getKey();
						ClassConfiguration typeConfiguration = entry.getValue();
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
									ClassName.get(joinPackage(versionPackage, "data"), "IBasicType")
							))
							.returns(ParameterizedTypeName.get(ClassName.get(DataSerializer.class), TypeVariableName.get("T")))
							.addException(IOException.class)
							.addParameter(ParameterSpec
									.builder(ClassName.get(joinPackage(basePackageName, ""), "BasicType"), "type")
									.build())
							.beginControlFlow("switch (type)");
					for (Entry<String, ClassConfiguration> entry : versionConfiguration.classes.entrySet()) {
						String type = entry.getKey();
						ClassConfiguration typeConfiguration = entry.getValue();
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
							.returns(ClassName.get(joinPackage(versionPackage, "data.nullables"), "INullableBasicType"))
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
					writeClass(outPath, joinPackage(versionPackage, ""), versionClass);
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
					writeClass(outPath, joinPackage(versionPackage, "data"), iTypeInterface);
				} catch (IOException e) {
					throw new IOError(e);
				}
			}

			// Create the interface IBasicType
			{
				var iTypeInterfaceType = ClassName.get(joinPackage(versionPackage, "data"), "IType");
				var ibasicTypeInterfaceType = ClassName.get(joinPackage(versionPackage, "data"), "IBasicType");
				var ibasicTypeInterface = TypeSpec.interfaceBuilder("IBasicType");
				ibasicTypeInterface.addModifiers(Modifier.PUBLIC);
				ibasicTypeInterface.addSuperinterface(iTypeInterfaceType);
				// Create getBasicType$type method
				{
					var getBasicTypeMethod = MethodSpec
							.methodBuilder("getBasicType$")
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.ABSTRACT)
							.returns(ClassName.get(joinPackage(basePackageName, ""), "BasicType"));
					ibasicTypeInterface.addMethod(getBasicTypeMethod.build());
				}
				try {
					writeClass(outPath, joinPackage(versionPackage, "data"), ibasicTypeInterface);
				} catch (IOException e) {
					throw new IOError(e);
				}
			}

			// Create the interface INullableBasicType
			{
				var iTypeInterfaceType = ClassName.get(joinPackage(versionPackage, "data"), "IType");
				var inullableBasicTypeInterface = TypeSpec.interfaceBuilder("INullableBasicType");
				inullableBasicTypeInterface.addModifiers(Modifier.PUBLIC);
				inullableBasicTypeInterface.addSuperinterface(iTypeInterfaceType);
				try {
					writeClass(outPath, joinPackage(versionPackage, "data.nullables"), inullableBasicTypeInterface);
				} catch (IOException e) {
					throw new IOError(e);
				}
			}

			// Create the interfaces
			{
				for (Entry<String, Set<String>> superType : versionConfiguration.superTypes.entrySet()) {
					String type = superType.getKey();
					Set<String> superTypeConfiguration = superType.getValue();
					var iTypeInterfaceType = ClassName.get(joinPackage(versionPackage, "data"), "IType");
					var typeInterfaceType = ClassName.get(joinPackage(versionPackage, "data"), type);
					var typeInterface = TypeSpec.interfaceBuilder(type);
					typeInterface.addModifiers(Modifier.PUBLIC);
					typeInterface.addSuperinterface(iTypeInterfaceType);
					var getMetaTypeMethod = MethodSpec
							.methodBuilder("getMetaId$" + type)
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.ABSTRACT)
							.returns(TypeName.INT);
					typeInterface.addMethod(getMetaTypeMethod.build());
					var getBasicTypeMethod = MethodSpec
							.methodBuilder("getBasicType$")
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.ABSTRACT)
							.returns(ClassName.get(joinPackage(basePackageName, ""), "BasicType"));
					typeInterface.addMethod(getBasicTypeMethod.build());

					// If it's the latest version, add the common methods
					if (nextVersion.isEmpty()) {
						var interfaceDataConfiguration = configuration.interfacesData.get(type);
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
											.methodBuilder("get" + capitalize(fieldInfo.fieldName))
											.addModifiers(Modifier.PUBLIC)
											.addModifiers(Modifier.ABSTRACT)
											.returns(fieldTypeType);
									if (!fieldTypeType.isPrimitive()) {
										getterMethod.addAnnotation(NotNull.class);
										getterMethod.addAnnotation(NonNull.class);
									}
									typeInterface.addMethod(getterMethod.build());
								}

								// Add common data setter
								if (fieldInfo.hasSetter) {
									if (!fieldInfo.fieldType.equals("?")) {
										var param = ParameterSpec.builder(fieldTypeType, fieldInfo.fieldName, Modifier.FINAL);
										if (!fieldTypeType.isPrimitive()) {
											param.addAnnotation(NotNull.class);
											param.addAnnotation(NonNull.class);
										}
										var setterMethod = MethodSpec
												.methodBuilder("set" + capitalize(fieldInfo.fieldName))
												.addModifiers(Modifier.PUBLIC)
												.addModifiers(Modifier.ABSTRACT)
												.addParameter(param.build())
												.returns(typeInterfaceType)
												.addAnnotation(NotNull.class)
												.addAnnotation(NonNull.class);
										typeInterface.addMethod(setterMethod.build());
									}
								}
							}
						}
					}

					try {
						writeClass(outPath, joinPackage(versionPackage, "data"), typeInterface);
					} catch (IOException e) {
						throw new IOError(e);
					}
				}
			}

			// Create the basic types classes
			{
				for (Entry<String, ClassConfiguration> stringClassConfigurationEntry : versionConfiguration.classes.entrySet()) {
					String type = stringClassConfigurationEntry.getKey();
					ClassConfiguration classConfiguration = stringClassConfigurationEntry.getValue();
					var typeClass = TypeSpec.classBuilder(type);
					typeClass.addModifiers(Modifier.PUBLIC);
					typeClass.addModifiers(Modifier.FINAL);
					typeClass.addSuperinterface(ClassName.get(joinPackage(versionPackage, "data"), "IBasicType"));
					typeClass.addAnnotation(EqualsAndHashCode.class);
					typeClass.addAnnotation(AnnotationSpec
							.builder(AllArgsConstructor.class)
							.addMember("staticName", "$S", "of")
							.build());
					typeClass.addAnnotation(lombok.Builder.class);
					typeClass.addAnnotation(ToString.class);
					var constructor = MethodSpec.constructorBuilder();
					constructor.addModifiers(Modifier.PUBLIC);
					var getBasicTypeMethod = MethodSpec
							.methodBuilder("getBasicType$")
							.addModifiers(Modifier.PUBLIC)
							.addModifiers(Modifier.FINAL)
							.addAnnotation(Override.class)
							.returns(ClassName.get(joinPackage(basePackageName, ""), "BasicType"))
							.addStatement("return $T." + type, ClassName.get(joinPackage(basePackageName, ""), "BasicType"));
					typeClass.addMethod(getBasicTypeMethod.build());
					var superTypes = versionConfiguration.superTypes
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
									var interfaceCommonDataConfiguration = configuration.interfacesData.getOrDefault(superType.getKey(),
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

						addField(typeClass, constructor, key, typeTypes.get(value), true, true, false, isGetterOverride);
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
					//todo: now we use lombok to generate the constructor.
					//typeClass.addMethod(constructor.build());

					var mapConstructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
					mapConstructor.addException(IllegalStateException.class);
					mapConstructor.addParameter(ParameterizedTypeName.get(Map.class, String.class, Object.class), "fields");
					mapConstructor.addStatement(
							"if (fields.size() != " + classConfiguration.getData().size() + ") throw new $T()",
							IllegalStateException.class
					);
					for (Entry<String, String> entry : classConfiguration.getData().entrySet()) {
						String field = entry.getKey();
						String fieldType = entry.getValue();
						var fieldTypeType = typeTypes.get(fieldType);
						mapConstructor.addStatement("if (!fields.containsKey(\"" + field + "\")) throw new $T()",
								IllegalStateException.class
						);
						boolean requiresNotNull = !fieldTypeType.isPrimitive();
						var assignMethodStatementBuilder = CodeBlock.builder().add("this." + field + " = ");
						if (requiresNotNull) {
							assignMethodStatementBuilder.add("$T.requireNonNull(", Objects.class);
						}
						assignMethodStatementBuilder.add("($T) fields.get(\"" + field + "\")", typeTypes.get(fieldType));
						if (requiresNotNull) {
							assignMethodStatementBuilder.add(")");
						}
						mapConstructor.addStatement(assignMethodStatementBuilder.build());
					}
					typeClass.addMethod(mapConstructor.build());

					try {
						writeClass(outPath, joinPackage(versionPackage, "data"), typeClass);
					} catch (IOException e) {
						throw new IOError(e);
					}
				}
			}

			// Create an upgrader

		}
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
				.addParameter(ParameterSpec.builder(classType, "data").addAnnotation(NotNull.class).addAnnotation(NonNull.class).build());
		serializeMethod.addException(IOException.class);
		serializeMethod.addStatement("$T.requireNonNull(data)", Objects.class);
		return serializeMethod;
	}

	private MethodSpec.Builder createEmptyDeserializeMethod(TypeName classType) {
		var deserializeMethod = MethodSpec.methodBuilder("deserialize");
		deserializeMethod.addAnnotation(Override.class);
		deserializeMethod.addAnnotation(NotNull.class);
		deserializeMethod.addAnnotation(NonNull.class);
		deserializeMethod.addModifiers(Modifier.PUBLIC);
		deserializeMethod.addModifiers(Modifier.FINAL);
		deserializeMethod.returns(classType);
		deserializeMethod.addParameter(ParameterSpec.builder(DataInput.class, "dataInput").build());
		deserializeMethod.addException(IOException.class);
		return deserializeMethod;
	}

	@Value
	public static class NeededTypes {
		boolean nullableTypeNeeded;
		boolean nextVersionNullableTypeNeeded;
		boolean arrayTypeNeeded;
		boolean nextVersionArrayTypeNeeded;
	}

	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	public NeededTypes registerNeededTypes(VersionConfiguration versionConfiguration,
			String type,
			Optional<String> nextVersion,
			Optional<String> nextVersionPackage,
			ClassName versionClassType,
			String versionPackage,
			HashMap<String, TypeName> typeOptionalSerializers,
			HashMap<String, SerializeCodeBlockGenerator> typeSerializeStatement,
			HashMap<String, CodeBlock> typeDeserializeStatement,
			HashMap<String, Boolean> typeMustGenerateSerializer,
			HashMap<String, TypeName> typeTypes, @Nullable HashMap<String, TypeName> nextVersionTypeTypes,
			Supplier<ClassName> arrayClassName,
			Supplier<ClassName> nextArrayClassName) {
		// Check if the nullable type is needed
		boolean nullableTypeNeeded = versionConfiguration.classes
				.values()
				.parallelStream()
				.map(ClassConfiguration::getData)
				.map(Map::values)
				.flatMap(Collection::parallelStream)
				.filter((typeZ) -> typeZ.startsWith("-"))
				.map((typeZ) -> typeZ.substring(1))
				.anyMatch((typeZ) -> typeZ.equals(type));

		boolean nextVersionNullableTypeNeeded = nextVersion
				.filter(s -> configuration.versions.get(s).classes
						.values()
						.parallelStream()
						.map(ClassConfiguration::getData)
						.map(Map::values)
						.flatMap(Collection::parallelStream)
						.filter((typeZ) -> typeZ.startsWith("-"))
						.map((typeZ) -> typeZ.substring(1))
						.anyMatch((typeZ) -> typeZ.equals(type)))
				.isPresent();


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
		if (nextVersionNullableTypeNeeded) {
			assert nextVersionTypeTypes != null;
			nextVersionTypeTypes.put("-" + type, ClassName.get(joinPackage(nextVersionPackage.orElseThrow(), "data.nullables"), "Nullable" + type));
		}

		// Check if the array type is needed
		boolean arrayTypeNeeded = versionConfiguration.classes
				.values()
				.parallelStream()
				.map(ClassConfiguration::getData)
				.map(Map::values)
				.flatMap(Collection::parallelStream)
				.filter((typeZ) -> typeZ.startsWith("§"))
				.map((typeZ) -> typeZ.substring(1))
				.anyMatch((typeZ) -> typeZ.equals(type));

		boolean nextVersionArrayTypeNeeded = nextVersion.filter(s -> configuration.versions.get(s).classes
				.values()
				.parallelStream()
				.map(ClassConfiguration::getData)
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
			typeTypes.put("§" + type, ArrayTypeName.of(arrayClassName.get()));
		}
		if (nextVersionArrayTypeNeeded) {
			assert nextVersionTypeTypes != null;
			nextVersionTypeTypes.put("§" + type, ArrayTypeName.of(nextArrayClassName.get()));
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

	private String fixType(String fieldType) {
		if (fieldType.endsWith("[]") && fieldType.startsWith("-")) {
			throw new UnsupportedOperationException("Arrays cannot be null");
		}
		if (fieldType.endsWith("[]")) {
			return "§" + fieldType.substring(0, fieldType.length() - 2);
		} else {
			return fieldType;
		}
	}

	private void addImmutableSetter(Builder classBuilder, TypeName classType, Collection<String> fieldNames,
			String fieldName, TypeName fieldType, boolean isOverride) {
		var setterMethod = MethodSpec.methodBuilder("set" + capitalize(fieldName));
		setterMethod.addModifiers(Modifier.PUBLIC);
		setterMethod.addModifiers(Modifier.FINAL);
		setterMethod.addAnnotation(NotNull.class);
		setterMethod.addAnnotation(NonNull.class);
		var param = ParameterSpec.builder(fieldType, fieldName, Modifier.FINAL);
		if (!fieldType.isPrimitive()) {
			param.addAnnotation(NotNull.class);
			param.addAnnotation(NonNull.class);
		}
		if (isOverride) {
			setterMethod.addAnnotation(Override.class);
		}
		setterMethod.addParameter(param.build());
		setterMethod.returns(classType);
		if (!fieldType.isPrimitive()) {
			setterMethod.addStatement("$T.requireNonNull(" + fieldName + ")", Objects.class);
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

	private void addField(Builder classBuilder, @Nullable MethodSpec.Builder constructorBuilder, String fieldName,
			TypeName fieldType, boolean isFinal, boolean hasGetter, boolean hasSetter, boolean isOverride) {
		if (isFinal && constructorBuilder == null) {
			throw new IllegalStateException();
		}
		if (isFinal && hasSetter) {
			throw new IllegalStateException();
		}
		if (hasSetter) {
			throw new UnsupportedOperationException();
		}
		var field = FieldSpec.builder(fieldType, fieldName, Modifier.PRIVATE);
		if (!fieldType.isPrimitive()) {
			field.addAnnotation(NotNull.class);
			field.addAnnotation(NonNull.class);
		}
		if (isFinal) {
			field.addModifiers(Modifier.FINAL);
		}
		classBuilder.addField(field.build());
		if (hasGetter) {
			var getter = MethodSpec.methodBuilder("get" + capitalize(fieldName));
			getter.addModifiers(Modifier.PUBLIC);
			getter.addModifiers(Modifier.FINAL);
			if (!fieldType.isPrimitive()) {
				getter.addAnnotation(NotNull.class);
				getter.addAnnotation(NonNull.class);
			}
			if (isOverride) {
				getter.addAnnotation(Override.class);
			}
			getter.returns(fieldType);
			getter.addStatement("return this." + fieldName);
			classBuilder.addMethod(getter.build());
		}
		if (constructorBuilder != null) {
			var param = ParameterSpec.builder(fieldType, fieldName, Modifier.FINAL);
			boolean requiresNotNull = !fieldType.isPrimitive();
			if (requiresNotNull) {
				param.addAnnotation(NotNull.class);
				param.addAnnotation(NonNull.class);
			}
			constructorBuilder.addParameter(param.build());

			var assignStatementBuilder = CodeBlock
					.builder()
					.add("this." + fieldName + " = ");
			if (requiresNotNull) {
				assignStatementBuilder.add("$T.requireNonNull(", Objects.class);
			}
			assignStatementBuilder.add(fieldName);
			if (requiresNotNull) {
				assignStatementBuilder.add(")");
			}

			constructorBuilder.addStatement(assignStatementBuilder.build());
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

	private Optional<String> findNextVersion(SourcesGeneratorConfiguration config, String version) {
		int currentVersion = Integer.parseInt(getVersionShortInt(version));
		int maxVersion = Integer.parseInt(getVersionShortInt(config.currentVersion));
		if (currentVersion >= maxVersion) {
			return Optional.empty();
		}

		AtomicInteger smallestNextVersion = new AtomicInteger(Integer.MAX_VALUE);
		AtomicReference<String> smallestNextVersionString = new AtomicReference<>(null);
		for (Entry<String, VersionConfiguration> entry : config.versions.entrySet()) {
			String possibleNextVersionString = entry.getKey();
			VersionConfiguration conf = entry.getValue();
			int possibleNextVersion = Integer.parseInt(getVersionShortInt(possibleNextVersionString));
			if (possibleNextVersion <= maxVersion && possibleNextVersion > currentVersion
					&& possibleNextVersion <= smallestNextVersion.get()) {
				smallestNextVersion.set(possibleNextVersion);
				smallestNextVersionString.set(possibleNextVersionString);
			}
		}

		String value = smallestNextVersionString.get();
		return Optional.ofNullable(value);
	}

	private String getVersionPackage(String latestVersion, String basePackageName, String version) {
		if (latestVersion.equals(version)) {
			return joinPackage(basePackageName, "current");
		} else {
			return joinPackage(basePackageName, "v" + getVersionCompleteInt(version));
		}
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

	private void writeClass(Path outPath, String classPackage, Builder versionsClass) throws IOException {
		JavaFile.builder(classPackage, versionsClass.build()).build().writeTo(outPath);
	}

	private String getVersionVarName(String version) {
		return "V" + version.replace('-', '_').replace('.', '_');
	}

	private String getVersionCompleteInt(String version) {
		String[] parts = version.split("\\.");
		while (parts[0].length() < 2) {
			parts[0] = "0" + parts[0];
		}
		while (parts[1].length() < 3) {
			parts[1] = "0" + parts[1];
		}
		while (parts[2].length() < 4) {
			parts[2] = "0" + parts[2];
		}
		return parts[0] + parts[1] + parts[2];
	}

	private String getVersionShortInt(String version) {
		version = getVersionCompleteInt(version);
		while (version.startsWith("0")) {
			version = version.substring(1);
		}
		if (version.isBlank()) {
			return "0";
		}
		return version;
	}
}
