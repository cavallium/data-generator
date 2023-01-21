package it.cavallium.data.generator.plugin;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec.Builder;
import it.cavallium.data.generator.plugin.ClassGenerator.ClassGeneratorParams;
import it.cavallium.data.generator.plugin.classgen.GenBaseType;
import it.cavallium.data.generator.plugin.classgen.GenCurrentVersion;
import it.cavallium.data.generator.plugin.classgen.GenDataBaseX;
import it.cavallium.data.generator.plugin.classgen.GenDataSuperX;
import it.cavallium.data.generator.plugin.classgen.GenIBaseType;
import it.cavallium.data.generator.plugin.classgen.GenINullableBaseType;
import it.cavallium.data.generator.plugin.classgen.GenINullableIType;
import it.cavallium.data.generator.plugin.classgen.GenINullableSuperType;
import it.cavallium.data.generator.plugin.classgen.GenIType;
import it.cavallium.data.generator.plugin.classgen.GenIVersion;
import it.cavallium.data.generator.plugin.classgen.GenNullableX;
import it.cavallium.data.generator.plugin.classgen.GenSerializerArrayX;
import it.cavallium.data.generator.plugin.classgen.GenSerializerBaseX;
import it.cavallium.data.generator.plugin.classgen.GenSerializerNullableX;
import it.cavallium.data.generator.plugin.classgen.GenSerializerSuperX;
import it.cavallium.data.generator.plugin.classgen.GenSuperType;
import it.cavallium.data.generator.plugin.classgen.GenVersion;
import it.cavallium.data.generator.plugin.classgen.GenVersions;
import it.unimi.dsi.fastutil.booleans.BooleanList;
import it.unimi.dsi.fastutil.bytes.ByteList;
import it.unimi.dsi.fastutil.chars.CharList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.floats.FloatList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import org.jetbrains.annotations.NotNull;
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
	 * @param force           force overwrite
	 * @param deepCheckBeforeCreatingNewEqualInstances if true, use equals, if false, use ==
	 */
	public void generateSources(String basePackageName, Path outPath, boolean useRecordBuilders, boolean force, boolean deepCheckBeforeCreatingNewEqualInstances) throws IOException {
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

				if (!force
						&& prevBasePackageName.equals(basePackageName)
						&& (prevRecordBuilders.equalsIgnoreCase("true") == useRecordBuilders) 
						&& prevHash.equals(Integer.toString(curHash))) {
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

		var genParams = new ClassGeneratorParams(generatedFilesToDelete, dataModel, basePackageName, outPath, deepCheckBeforeCreatingNewEqualInstances);

		// Create the Versions class
		new GenVersions(genParams).run();

		// Create the BaseType class
		new GenBaseType(genParams).run();

		// Create the SuperType class
		new GenSuperType(genParams).run();

		// Create the IVersion class
		new GenIVersion(genParams).run();

		// Create the CurrentVersion class
		new GenCurrentVersion(genParams).run();

		new GenVersion(genParams).run();

		new GenIBaseType(genParams).run();

		new GenIType(genParams).run();

		new GenNullableX(genParams).run();

		new GenINullableIType(genParams).run();

		new GenINullableBaseType(genParams).run();

		new GenINullableSuperType(genParams).run();

		new GenDataBaseX(genParams).run();

		new GenDataSuperX(genParams).run();

		new GenSerializerSuperX(genParams).run();

		new GenSerializerBaseX(genParams).run();

		new GenSerializerArrayX(genParams).run();

		new GenSerializerNullableX(genParams).run();

		// Update the hash at the end
		Files.writeString(hashPath, basePackageName + '\n' + useRecordBuilders + '\n' + curHash + '\n',
				StandardCharsets.UTF_8, TRUNCATE_EXISTING, WRITE, CREATE);
		generatedFilesToDelete.remove(outPath.relativize(hashPath));
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

	private static String getSpecialNativePackage(String specialNativeType) {
		//noinspection SwitchStatementWithTooFewBranches
		return switch (specialNativeType) {
			case "Int52" -> "it.cavallium.data.generator.nativedata";
			default -> "java.lang";
		};
	}

	private void registerArrayType(ComputedVersion version,
			String basePackageName,
			ClassName versionClassType,
			HashMap<String, TypeName> typeOptionalSerializers,
			HashMap<String, SerializeCodeBlockGenerator> typeSerializeStatement,
			HashMap<String, CodeBlock> typeDeserializeStatement,
			HashMap<String, Boolean> typeMustGenerateSerializer,
			String type) {
		typeOptionalSerializers.put("§" + type,
				ClassName.get(version .getSerializersPackage(basePackageName), "Array" + type + "Serializer"));
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

	private void addField(Builder classBuilder, @NotNull String fieldName,
			@NotNull TypeName fieldType, boolean isRecord, boolean isFinal, boolean hasSetter) {
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

}
