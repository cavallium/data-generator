package it.cavallium.datagen.plugin;

import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The generated-file manifest ({@value #FILE_NAME}) format: its constants, its reader and its writer.
 *
 * <p>Moved verbatim from the former private {@code SourcesGenerator} statics
 * ({@code readManifest}, {@code manifestFingerprint}, {@code writeManifestAtomically},
 * {@code resolveManifestFile} and the {@code Manifest} record) so that callers can reuse the exact same
 * read/write logic the generator uses. Behavior is unchanged, including every validation: an absent,
 * unreadable, malformed, forged or self-inconsistent manifest yields {@code null} rather than an exception,
 * which makes the generator regenerate instead of reporting a cache hit.
 */
public final class Manifests {

    /** Name of the manifest file, written inside the generated base-package directory. */
    public static final String FILE_NAME = ".datagen-manifest-v1";

    private static final String TEMPORARY_PREFIX = ".datagen-manifest-";
    static final String MANIFEST_HEADER = "data-generator-manifest-v2";
    static final String GENERATION_PREFIX = "generation=";
    static final String FINGERPRINT_PREFIX = "fingerprint=";
    static final String GENERATED_FILES_SECTION = "files:";

    private Manifests() {}

    /**
     * @param generationFingerprint fingerprint of the schema and generation options (see {@link Fingerprints})
     * @param fingerprint           fingerprint of {@code generationFingerprint} plus every generated file digest
     * @param files                 generated file, relative to the generator output path, to its sha256 hex digest
     */
    public record Manifest(String generationFingerprint, String fingerprint, Map<Path, String> files) {}

    /**
     * Reads the manifest at {@code manifestPath}, which is the full path of the manifest file
     * (a directory resolved with {@link #FILE_NAME}), not a directory.
     *
     * @return the parsed manifest, or {@code null} when there is no usable manifest at that path
     */
    public static Manifest read(Path manifestPath) throws IOException {
        if (!Files.isRegularFile(manifestPath) || !Files.isReadable(manifestPath)) {
            return null;
        }
        List<String> lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
        if (lines.size() < 4 || !MANIFEST_HEADER.equals(lines.get(0))
                || !lines.get(1).startsWith(GENERATION_PREFIX)
                || !lines.get(2).startsWith(FINGERPRINT_PREFIX)
                || !GENERATED_FILES_SECTION.equals(lines.get(3))) {
            return null;
        }
        String generationFingerprint = lines.get(1).substring(GENERATION_PREFIX.length());
        String fingerprint = lines.get(2).substring(FINGERPRINT_PREFIX.length());
        if (!isSha256(generationFingerprint) || !isSha256(fingerprint)) return null;
        var files = new LinkedHashMap<Path, String>();
        for (int index = 4; index < lines.size(); index++) {
            String line = lines.get(index);
            int separator = line.indexOf('\t');
            if (separator != 64) return null;
            String digest = line.substring(0, separator);
            final Path relativePath;
            try {
                relativePath = Path.of(line.substring(separator + 1));
            } catch (InvalidPathException exception) {
                return null;
            }
            Path normalized = relativePath.normalize();
            if (!isSha256(digest) || relativePath.isAbsolute() || normalized.startsWith("..")
                    || normalized.toString().isEmpty() || !relativePath.equals(normalized)
                    || files.put(normalized, digest) != null) {
                return null;
            }
        }
        Map<Path, String> immutableFiles = Map.copyOf(files);
        if (!fingerprint.equals(fingerprint(generationFingerprint, immutableFiles))) return null;
        return new Manifest(generationFingerprint, fingerprint, immutableFiles);
    }

    public static String fingerprint(String generationFingerprint, Map<Path, String> generatedFiles) {
        MessageDigest digest = Fingerprints.newDigest();
        Fingerprints.updateLengthPrefixed(digest, generationFingerprint.getBytes(StandardCharsets.UTF_8));
        generatedFiles.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Path::toString)))
                .forEach(entry -> {
                    Fingerprints.updateLengthPrefixed(digest, entry.getKey().toString().getBytes(StandardCharsets.UTF_8));
                    Fingerprints.updateLengthPrefixed(digest, entry.getValue().getBytes(StandardCharsets.UTF_8));
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    public static void write(Path manifestPath, Manifest manifest) throws IOException {
        StringBuilder contents = new StringBuilder()
                .append(MANIFEST_HEADER).append('\n')
                .append(GENERATION_PREFIX).append(manifest.generationFingerprint()).append('\n')
                .append(FINGERPRINT_PREFIX).append(manifest.fingerprint()).append('\n')
                .append(GENERATED_FILES_SECTION).append('\n');
        manifest.files().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Path::toString)))
                .forEach(entry -> contents.append(entry.getValue()).append('\t')
                        .append(entry.getKey()).append('\n'));
        writeTextAtomically(manifestPath, contents.toString(), TEMPORARY_PREFIX);
    }

    /**
     * Resolves a manifest-relative path under {@code outPath}, refusing anything that escapes the output
     * root through {@code ..} or a symbolic link.
     *
     * @return the resolved file, or {@code null} when the entry must not be touched
     */
    static Path resolveFile(Path outPath, Path relativePath) {
        Path root = outPath.toAbsolutePath().normalize();
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root)) return null;
        Path current = root;
        for (Path component : root.relativize(candidate)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) return null;
        }
        return candidate;
    }

    static void writeTextAtomically(Path target, String contents, String temporaryPrefix) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), temporaryPrefix, ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8, TRUNCATE_EXISTING, WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
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
}
