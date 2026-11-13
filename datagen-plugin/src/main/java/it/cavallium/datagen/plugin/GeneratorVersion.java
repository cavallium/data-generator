package it.cavallium.datagen.plugin;

import java.io.InputStream;
import java.util.Properties;

/**
 * The plugin's own version string, which {@link Fingerprints} mixes into the generation fingerprint so that
 * generated code cannot be considered up to date when a different version of the generator wrote the manifest.
 *
 * <p>The value is read from the runtime metadata of this artifact, never from a digest of the generator classes:
 * <ol>
 *     <li>the jar manifest's {@code Implementation-Version}, through {@link Package#getImplementationVersion()};</li>
 *     <li>otherwise, the {@code version} property of the {@code pom.properties} the Maven archiver writes into the
 *         same jar, which is present even when the manifest carries no version attribute;</li>
 *     <li>otherwise, {@link #UNKNOWN}.</li>
 * </ol>
 *
 * <p>Reading happens once, in this class's initializer, so every call sees the same {@link String} instance and a
 * running generator is stable by construction. Sources that ship no version metadata — {@code target/classes} in
 * an IDE, or a shaded or hand-assembled jar — all resolve to {@link #UNKNOWN}, which keeps the fingerprint
 * reproducible there instead of making generation fail.
 *
 * <p><strong>What this input does not do.</strong> It identifies the <em>artifact</em>, not the code inside it. The
 * reactor builds every module from one revision, so two builds of changed generator sources at the same version
 * hash alike, and an existing checkout keeps its generated code: {@link #UNKNOWN} collapses that further for anyone
 * running the plugin from {@code target/classes}. A generator change is therefore still exercised end to end only by
 * forcing regeneration or a {@code clean} (see this repository's working agreements), and {@code SERIAL_VERSION}
 * stays the hand-maintained signal for a change in what the generator produces.
 */
public final class GeneratorVersion {

    /**
     * The single fallback reported when the running generator carries no version metadata at all. It is non-empty
     * on purpose: the fingerprint mixes it in as an ordinary input, and an empty value would hash like a missing
     * one.
     */
    public static final String UNKNOWN = "unknown";

    /** Maven's archive descriptor for this artifact, written next to {@code META-INF/MANIFEST.MF}. */
    private static final String POM_PROPERTIES = "/META-INF/maven/it.cavallium/datagen-plugin/pom.properties";

    private static final String CURRENT = readVersion();

    private GeneratorVersion() {}

    /**
     * @return the plugin's implementation version, or {@link #UNKNOWN}; never {@code null}, never empty
     */
    public static String current() {
        return CURRENT;
    }

    private static String readVersion() {
        String fromManifest = fromManifest();
        if (fromManifest != null) return fromManifest;
        String fromPomProperties = fromPomProperties();
        if (fromPomProperties != null) return fromPomProperties;
        return UNKNOWN;
    }

    /** @return the manifest's {@code Implementation-Version}, or {@code null} when there is none */
    private static String fromManifest() {
        try {
            Package self = GeneratorVersion.class.getPackage();
            return self == null ? null : meaningful(self.getImplementationVersion());
        } catch (RuntimeException unexpected) {
            // A version lookup must never be the reason generation fails; the fallback is deterministic.
            return null;
        }
    }

    /** @return the {@code version} of this artifact's {@code pom.properties}, or {@code null} when there is none */
    private static String fromPomProperties() {
        Properties properties = new Properties();
        try (InputStream resource = GeneratorVersion.class.getResourceAsStream(POM_PROPERTIES)) {
            if (resource == null) return null;
            properties.load(resource);
        } catch (Exception unreadable) {
            return null;
        }
        return meaningful(properties.getProperty("version"));
    }

    private static String meaningful(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        // A filtered-but-unresolved property, such as "${revision}", is not a version either.
        if (trimmed.isEmpty() || trimmed.startsWith("${")) return null;
        return trimmed;
    }
}
