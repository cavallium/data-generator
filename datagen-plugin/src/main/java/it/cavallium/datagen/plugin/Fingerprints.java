package it.cavallium.datagen.plugin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Fingerprint of a schema plus the generation options that influence its generated output.
 *
 * <p>Extracted verbatim from the former private {@code SourcesGenerator.generationFingerprint} so that
 * callers (for example a fuzz campaign) can fingerprint a schema cheaply, without touching the filesystem.
 * The schema is fingerprinted from its exact raw YAML bytes, which is the same input the generator uses, so
 * the value is byte-identical to the one stored in the generated manifest.
 *
 * <p>Alongside the schema and the generation options, the fingerprint mixes in the generator's own version
 * ({@link GeneratorVersion#current()}) and {@code SERIAL_VERSION}. Version 22 of the fingerprint inputs added
 * the generator version, so a manifest written by an earlier generator, whose fingerprint came from the older
 * inputs, can never be read as a match.
 */
public final class Fingerprints {

    private static final String SERIAL_VERSION = "22";

    private Fingerprints() {}

    /**
     * @param schemaYaml                         the exact YAML bytes of the {@link SourcesGeneratorConfiguration}
     *                                           (the pre-{@code buildDataModel} configuration), never the built model
     * @param basePackageName                    org.example
     * @param generateOldSerializers             whether historical serializers may write values
     * @param binaryStrings                      use binary strings
     * @param vectorKernels                      use vector kernels
     * @param generatorVersion                   the version of the generator producing this fingerprint, mixed in as
     *                                           an ordinary length-prefixed input; the caller supplies it from
     *                                           {@link GeneratorVersion#current()}. This method stays pure: the
     *                                           version is passed in, never looked up here.
     */
    public static String ofSchema(byte[] schemaYaml,
                                  String basePackageName,
                                  boolean generateOldSerializers,
                                  boolean binaryStrings,
                                  boolean vectorKernels,
                                  String generatorVersion) {
        Objects.requireNonNull(generatorVersion, "generatorVersion");
        MessageDigest digest = newDigest();
        updateLengthPrefixed(digest, SERIAL_VERSION.getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(digest, basePackageName.getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(digest, new byte[] {(byte) (generateOldSerializers ? 1 : 0)});
        updateLengthPrefixed(digest, new byte[] {(byte) (binaryStrings ? 1 : 0)});
        updateLengthPrefixed(digest, new byte[] {(byte) (vectorKernels ? 1 : 0)});
        updateLengthPrefixed(digest, generatorVersion.getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(digest, schemaYaml);
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java implementation must provide SHA-256", exception);
        }
    }

    static void updateLengthPrefixed(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    static String sha256(byte[] value) {
        return HexFormat.of().formatHex(newDigest().digest(value));
    }
}
