package it.cavallium.datagen.plugin;

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
import it.cavallium.datagen.plugin.classgen.GenReadPlan;
import it.cavallium.datagen.plugin.classgen.GenSerializerArrayX;
import it.cavallium.datagen.plugin.classgen.GenSerializerBaseX;
import it.cavallium.datagen.plugin.classgen.GenSerializerNullableX;
import it.cavallium.datagen.plugin.classgen.GenSerializerSuperX;
import it.cavallium.datagen.plugin.classgen.GenSuperType;
import it.cavallium.datagen.plugin.classgen.GenUpgraderBaseX;
import it.cavallium.datagen.plugin.classgen.GenUpgraderSuperX;
import it.cavallium.datagen.plugin.classgen.GenVersion;
import it.cavallium.datagen.plugin.classgen.GenVersions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

public class SourcesGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SourcesGenerator.class);

    private final SourcesGeneratorConfiguration configuration;
    private final byte[] yamlBytes;
    private Integer wireSchemaBaselineVersion;

    private SourcesGenerator(InputStream yamlDataStream) throws IOException {
        this.yamlBytes = yamlDataStream.readAllBytes();
        Yaml yaml = new Yaml();
        this.configuration = Objects.requireNonNull(
                yaml.loadAs(new ByteArrayInputStream(yamlBytes), SourcesGeneratorConfiguration.class),
                "YAML document is empty");
    }

    public static SourcesGenerator load(InputStream yamlData) throws IOException {
        return new SourcesGenerator(yamlData);
    }

    public static SourcesGenerator load(Path yamlPath) throws IOException {
        try (InputStream in = Files.newInputStream(yamlPath)) {
            return new SourcesGenerator(in);
        }
    }

    public String wireSchemaLedger(boolean binaryStrings) {
        return WireSchemaLedger.render(configuration.buildDataModel(binaryStrings), binaryStrings);
    }

    public void verifyOrUpdateWireSchemaLedger(Path ledgerPath,
            boolean binaryStrings,
            boolean update) throws IOException {
        Path normalizedLedger = Objects.requireNonNull(ledgerPath, "ledgerPath")
                .toAbsolutePath().normalize();
        if (Files.notExists(normalizedLedger)) {
            if (update) {
                Files.createDirectories(normalizedLedger.getParent());
                wireSchemaBaselineVersion = configuration.buildDataModel(binaryStrings).getCurrentVersionNumber();
                Manifests.writeTextAtomically(normalizedLedger, wireSchemaLedger(binaryStrings), ".datagen-wire-ledger-");
                return;
            }
            throw new IllegalArgumentException("Wire-schema ledger is missing: " + normalizedLedger
                    + "; regenerate it explicitly with -Ddatagen.updateWireSchemaLedger=true");
        }
        String expected = Files.readString(normalizedLedger, StandardCharsets.UTF_8);
        wireSchemaBaselineVersion = WireSchemaLedger.baselineVersion(expected);
        String actual = WireSchemaLedger.render(configuration.buildDataModel(binaryStrings, wireSchemaBaselineVersion),
                binaryStrings, expected);
        if (update) {
            Manifests.writeTextAtomically(normalizedLedger, actual, ".datagen-wire-ledger-");
            return;
        }
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Wire-schema ledger mismatch: " + normalizedLedger
                    + "; an existing numbered version changed or a new version was added. "
                    + "Review the schema and regenerate explicitly with -Ddatagen.updateWireSchemaLedger=true");
        }
    }

    /**
     * @param basePackageName                          org.example
     * @param outPath                                  path/to/output
     * @param force                                    force overwrite
     * @param generateOldSerializers                   whether historical serializers may write values
     * @param binaryStrings                            use binary strings
     */
    public void generateSources(String basePackageName,
                                Path outPath,
                                boolean force,
                                boolean generateOldSerializers,
                                boolean binaryStrings) throws IOException {
        generateSources(basePackageName, outPath, force,
                generateOldSerializers, binaryStrings, false);
    }

    public void generateSources(String basePackageName,
                                Path outPath,
                                boolean force,
                                boolean generateOldSerializers,
                                boolean binaryStrings,
                                boolean vectorKernels) throws IOException {
        Path basePackageNamePath;
        {
            Path basePackageNamePathPartial = outPath;
            for (String part : basePackageName.split("\\.")) {
                basePackageNamePathPartial = basePackageNamePathPartial.resolve(part);
            }
            basePackageNamePath = basePackageNamePathPartial;
        }
        var manifestPath = basePackageNamePath.resolve(Manifests.FILE_NAME);
        var legacyHashPath = basePackageNamePath.resolve(".hash");
        var dataModel = configuration.buildDataModel(binaryStrings, wireSchemaBaselineVersion);
        String generationFingerprint = generationFingerprint(basePackageName, generateOldSerializers,
                binaryStrings, vectorKernels, yamlBytes);
        Manifests.Manifest previousManifest = Manifests.read(manifestPath);
        if (!force && previousManifest != null
                && previousManifest.generationFingerprint().equals(generationFingerprint)
                && manifestFilesMatch(outPath, previousManifest)) {
            logger.info("Skipped sources generation because the fingerprint and every generated file digest match");
            return;
        }

        // Create the base dir
        if (Files.notExists(outPath)) {
            Files.createDirectories(outPath);
        }
        if (Files.notExists(basePackageNamePath)) {
            Files.createDirectories(basePackageNamePath);
        }

        var generatedFilesToDelete = new HashSet<Path>();
        if (previousManifest != null) {
            generatedFilesToDelete.addAll(previousManifest.files().keySet());
        }
        var generatedFiles = new HashSet<Path>();

        var genParams = new ClassGeneratorParams(generatedFilesToDelete, generatedFiles, dataModel, basePackageName, outPath,
                generateOldSerializers, binaryStrings,
                vectorKernels);

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

		new GenReadPlan(genParams).run();

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

        for (Path generatedFileToDelete : generatedFilesToDelete) {
            Path fileToDelete = Manifests.resolveFile(outPath, generatedFileToDelete);
            if (fileToDelete != null && Files.isRegularFile(fileToDelete, LinkOption.NOFOLLOW_LINKS)) {
                logger.debug("Deleting stale generated file {}", fileToDelete);
                Files.delete(fileToDelete);
            }
        }

        var fileDigests = new LinkedHashMap<Path, String>();
        for (Path relativePath : generatedFiles.stream().sorted(Comparator.comparing(Path::toString)).toList()) {
            fileDigests.put(relativePath, Fingerprints.sha256(Files.readAllBytes(outPath.resolve(relativePath))));
        }
        Manifests.write(manifestPath, new Manifests.Manifest(
                generationFingerprint, Manifests.fingerprint(generationFingerprint, fileDigests), fileDigests));
        Files.deleteIfExists(legacyHashPath);
    }

    private static String generationFingerprint(String basePackageName,
                                                boolean generateOldSerializers,
                                                boolean binaryStrings,
                                                boolean vectorKernels,
                                                byte[] yamlBytes) {
        return Fingerprints.ofSchema(yamlBytes, basePackageName, generateOldSerializers, binaryStrings,
                vectorKernels, GeneratorVersion.current());
    }

    private static boolean manifestFilesMatch(Path outPath, Manifests.Manifest manifest) throws IOException {
        for (var file : manifest.files().entrySet()) {
            Path generatedFile = Manifests.resolveFile(outPath, file.getKey());
            if (generatedFile == null || !Files.isRegularFile(generatedFile, LinkOption.NOFOLLOW_LINKS)
                    || !Fingerprints.sha256(Files.readAllBytes(generatedFile)).equals(file.getValue())) {
                return false;
            }
        }
        return true;
    }


    public static String capitalize(String field) {
        return Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

}
