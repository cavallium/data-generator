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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

public class SourcesGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SourcesGenerator.class);
    private static final String SERIAL_VERSION = "19";
    private static final String MANIFEST_NAME = ".datagen-manifest-v1";
    private static final String MANIFEST_HEADER = "data-generator-manifest-v1";
    private static final String FINGERPRINT_PREFIX = "fingerprint=";
    private static final String GENERATED_FILES_SECTION = "files:";

    private final SourcesGeneratorConfiguration configuration;
    private final byte[] yamlBytes;

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
        var manifestPath = basePackageNamePath.resolve(MANIFEST_NAME);
        var legacyHashPath = basePackageNamePath.resolve(".hash");
        var dataModel = configuration.buildDataModel(binaryStrings);
        String fingerprint = generationFingerprint(basePackageName, generateOldSerializers,
                binaryStrings, vectorKernels, yamlBytes);
        Manifest previousManifest = readManifest(manifestPath);
        if (!force && previousManifest != null
                && previousManifest.fingerprint().equals(fingerprint)
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
            Path fileToDelete = outPath.resolve(generatedFileToDelete);
            if (Files.isRegularFile(fileToDelete)) {
                logger.debug("Deleting stale generated file {}", fileToDelete);
                Files.delete(fileToDelete);
            }
        }

        var fileDigests = new LinkedHashMap<Path, String>();
        for (Path relativePath : generatedFiles.stream().sorted(Comparator.comparing(Path::toString)).toList()) {
            fileDigests.put(relativePath, sha256(Files.readAllBytes(outPath.resolve(relativePath))));
        }
        writeManifestAtomically(manifestPath, new Manifest(fingerprint, fileDigests));
        Files.deleteIfExists(legacyHashPath);
    }

    private static String generationFingerprint(String basePackageName,
                                                boolean generateOldSerializers,
                                                boolean binaryStrings,
                                                boolean vectorKernels,
                                                byte[] yamlBytes) {
        MessageDigest digest = newDigest();
        updateLengthPrefixed(digest, SERIAL_VERSION.getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(digest, basePackageName.getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(digest, new byte[] {(byte) (generateOldSerializers ? 1 : 0)});
        updateLengthPrefixed(digest, new byte[] {(byte) (binaryStrings ? 1 : 0)});
        updateLengthPrefixed(digest, new byte[] {(byte) (vectorKernels ? 1 : 0)});
        updateLengthPrefixed(digest, yamlBytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateLengthPrefixed(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private static String sha256(byte[] value) {
        return HexFormat.of().formatHex(newDigest().digest(value));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java implementation must provide SHA-256", exception);
        }
    }

    private static Manifest readManifest(Path manifestPath) throws IOException {
        if (!Files.isRegularFile(manifestPath) || !Files.isReadable(manifestPath)) {
            return null;
        }
        List<String> lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
        if (lines.size() < 3 || !MANIFEST_HEADER.equals(lines.get(0))
                || !lines.get(1).startsWith(FINGERPRINT_PREFIX)
                || !GENERATED_FILES_SECTION.equals(lines.get(2))) {
            return null;
        }
        String fingerprint = lines.get(1).substring(FINGERPRINT_PREFIX.length());
        if (!isSha256(fingerprint)) return null;
        var files = new LinkedHashMap<Path, String>();
        for (int index = 3; index < lines.size(); index++) {
            String line = lines.get(index);
            int separator = line.indexOf('\t');
            if (separator != 64) return null;
            String digest = line.substring(0, separator);
            Path relativePath = Path.of(line.substring(separator + 1));
            if (!isSha256(digest) || relativePath.isAbsolute() || relativePath.normalize().startsWith("..")
                    || files.put(relativePath, digest) != null) {
                return null;
            }
        }
        return new Manifest(fingerprint, Map.copyOf(files));
    }

    private static boolean manifestFilesMatch(Path outPath, Manifest manifest) throws IOException {
        for (var file : manifest.files().entrySet()) {
            Path generatedFile = outPath.resolve(file.getKey());
            if (!Files.isRegularFile(generatedFile)
                    || !sha256(Files.readAllBytes(generatedFile)).equals(file.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static void writeManifestAtomically(Path manifestPath, Manifest manifest) throws IOException {
        StringBuilder contents = new StringBuilder()
                .append(MANIFEST_HEADER).append('\n')
                .append(FINGERPRINT_PREFIX).append(manifest.fingerprint()).append('\n')
                .append(GENERATED_FILES_SECTION).append('\n');
        manifest.files().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Path::toString)))
                .forEach(entry -> contents.append(entry.getValue()).append('\t')
                        .append(entry.getKey()).append('\n'));
        Path temporary = Files.createTempFile(manifestPath.getParent(), ".datagen-manifest-", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8, TRUNCATE_EXISTING, WRITE);
            try {
                Files.move(temporary, manifestPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, manifestPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean isSha256(String value) {
        if (value.length() != 64) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) return false;
        }
        return true;
    }

    private record Manifest(String fingerprint, Map<Path, String> files) {}

    public static String capitalize(String field) {
        return Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

}
