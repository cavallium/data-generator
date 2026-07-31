package it.cavallium.datagen.plugin;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import it.cavallium.datagen.plugin.ClassGenerator.ClassGeneratorParams;
import it.cavallium.datagen.plugin.classgen.GenBaseType;
import it.cavallium.datagen.plugin.classgen.GenCurrentVersion;
import it.cavallium.datagen.plugin.classgen.GenDataBaseX;
import it.cavallium.datagen.plugin.classgen.GenDataSuperX;
import it.cavallium.datagen.plugin.classgen.GenIBaseType;
import it.cavallium.datagen.plugin.classgen.GenINullableBaseType;
import it.cavallium.datagen.plugin.classgen.GenINullableIType;
import it.cavallium.datagen.plugin.classgen.GenINullableSuperType;
import it.cavallium.datagen.plugin.classgen.GenIType;
import it.cavallium.datagen.plugin.classgen.GenIVersion;
import it.cavallium.datagen.plugin.classgen.GenNullableX;
import it.cavallium.datagen.plugin.classgen.GenProjection;
import it.cavallium.datagen.plugin.classgen.GenSerializerArrayX;
import it.cavallium.datagen.plugin.classgen.GenSerializerBaseX;
import it.cavallium.datagen.plugin.classgen.GenSerializerNullableX;
import it.cavallium.datagen.plugin.classgen.GenSerializerSuperX;
import it.cavallium.datagen.plugin.classgen.GenSuperType;
import it.cavallium.datagen.plugin.classgen.GenUpgraderBaseX;
import it.cavallium.datagen.plugin.classgen.GenUpgraderSuperX;
import it.cavallium.datagen.plugin.classgen.GenVersion;
import it.cavallium.datagen.plugin.classgen.GenVersions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

public class SourcesGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SourcesGenerator.class);
    private static final String SERIAL_VERSION = "8";
    private static final String GENERATED_FILES_SECTION = "generatedFiles:";

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

    /**
     * @param basePackageName                          org.example
     * @param outPath                                  path/to/output
     * @param useRecordBuilders                        if true, the data will have @RecordBuilder annotation
     * @param force                                    force overwrite
     * @param deepCheckBeforeCreatingNewEqualInstances if true, use equals, if false, use ==
     * @param binaryStrings                            use binary strings
     */
    public void generateSources(String basePackageName,
                                Path outPath,
                                boolean useRecordBuilders,
                                boolean force,
                                boolean deepCheckBeforeCreatingNewEqualInstances,
                                boolean generateOldSerializers,
                                boolean binaryStrings) throws IOException {
        Path basePackageNamePath;
        {
            Path basePackageNamePathPartial = outPath;
            for (String part : basePackageName.split("\\.")) {
                basePackageNamePathPartial = basePackageNamePathPartial.resolve(part);
            }
            basePackageNamePath = basePackageNamePathPartial;
        }
        var hashPath = basePackageNamePath.resolve(".hash");
        var dataModel = configuration.buildDataModel(binaryStrings);
        var curHash = dataModel.computeHash();
        List<String> hashLines = null;
        if (Files.isRegularFile(hashPath) && Files.isReadable(hashPath)) {
            hashLines = Files.readAllLines(hashPath, StandardCharsets.UTF_8);
            if (hashLines.size() >= 7) {
                var prevBasePackageName = hashLines.get(0);
                var prevRecordBuilders = hashLines.get(1);
                var prevHash = hashLines.get(2);
                var prevDeepCheckBeforeCreatingNewEqualInstances = hashLines.get(3);
                var prevGenerateOldSerializers = hashLines.get(4);
                var prevSerialVersion = hashLines.get(5);
                var prevBinaryStrings = hashLines.get(6);

                if (!force
                    && prevBasePackageName.equals(basePackageName)
                    && (prevRecordBuilders.equalsIgnoreCase("true") == useRecordBuilders)
                    && (prevDeepCheckBeforeCreatingNewEqualInstances.equalsIgnoreCase("true") == deepCheckBeforeCreatingNewEqualInstances)
                    && (prevGenerateOldSerializers.equalsIgnoreCase("true") == generateOldSerializers)
                    && (prevBinaryStrings.equalsIgnoreCase("true") == binaryStrings)
                    && (prevSerialVersion.equals(SERIAL_VERSION))
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

        var generatedFilesToDelete = new HashSet<>(readGeneratedFiles(hashLines));
        var generatedFiles = new HashSet<Path>();

        var genParams = new ClassGeneratorParams(generatedFilesToDelete, generatedFiles, dataModel, basePackageName, outPath,
                deepCheckBeforeCreatingNewEqualInstances, useRecordBuilders, generateOldSerializers, binaryStrings);

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

        new GenUpgraderBaseX(genParams).run();

        new GenUpgraderSuperX(genParams).run();

		new GenProjection(genParams).run();

        generatedFilesToDelete.remove(outPath.relativize(hashPath));
        for (Path generatedFileToDelete : generatedFilesToDelete) {
            Path fileToDelete = outPath.resolve(generatedFileToDelete);
            if (Files.isRegularFile(fileToDelete)) {
                logger.debug("Deleting stale generated file {}", fileToDelete);
                Files.delete(fileToDelete);
            }
        }

        // Update the hash at the end
        var newHashRaw = newHashRaw(basePackageName, useRecordBuilders, curHash,
                deepCheckBeforeCreatingNewEqualInstances, generateOldSerializers, binaryStrings, generatedFiles);
        String oldHashRaw;
        if (Files.exists(hashPath)) {
            oldHashRaw = Files.readString(hashPath, StandardCharsets.UTF_8);
        } else {
            oldHashRaw = null;
        }
        if (!Objects.equals(newHashRaw, oldHashRaw)) {
            Files.writeString(hashPath,
                    newHashRaw,
                    StandardCharsets.UTF_8,
                    TRUNCATE_EXISTING,
                    WRITE,
                    CREATE
            );
        }
    }

    private static List<Path> readGeneratedFiles(List<String> hashLines) {
        if (hashLines == null) {
            return List.of();
        }
        int generatedFilesStart = hashLines.indexOf(GENERATED_FILES_SECTION);
        if (generatedFilesStart < 0) {
            return List.of();
        }
        var result = new ArrayList<Path>();
        for (int i = generatedFilesStart + 1; i < hashLines.size(); i++) {
            var line = hashLines.get(i);
            if (!line.isBlank()) {
                result.add(Path.of(line));
            }
        }
        return result;
    }

    private static String newHashRaw(String basePackageName,
                                     boolean useRecordBuilders,
                                     int curHash,
                                     boolean deepCheckBeforeCreatingNewEqualInstances,
                                     boolean generateOldSerializers,
                                     boolean binaryStrings,
                                     HashSet<Path> generatedFiles) {
        var generatedFileLines = generatedFiles
                .stream()
                .map(Path::toString)
                .sorted()
                .collect(Collectors.joining("\n"));
        return basePackageName + '\n'
                + useRecordBuilders + '\n'
                + curHash + '\n'
                + deepCheckBeforeCreatingNewEqualInstances + '\n'
                + generateOldSerializers + '\n'
                + SERIAL_VERSION + '\n'
                + binaryStrings + '\n'
                + GENERATED_FILES_SECTION + '\n'
                + generatedFileLines
                + (generatedFileLines.isEmpty() ? "" : "\n");
    }

    public static String capitalize(String field) {
        return Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

}
