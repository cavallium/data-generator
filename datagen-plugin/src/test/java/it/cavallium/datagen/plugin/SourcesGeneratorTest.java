package it.cavallium.datagen.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourcesGeneratorTest {

    private static final String BASE_PACKAGE = "org.example";
    private static final Path USER_CLASS = Path.of("org/example/current/data/User.java");
    private static final Path MESSAGE_CLASS = Path.of("org/example/current/data/Message.java");

    @Test
    void supportsNoOpVersionWithoutTransformations(@TempDir Path out) throws Exception {
        generate(userSchema(), out);

        assertTrue(Files.isRegularFile(out.resolve(USER_CLASS)));
    }

    @Test
    void deletesStaleGeneratedFiles(@TempDir Path out) throws Exception {
        generate(userSchema(), out);
        assertTrue(Files.isRegularFile(out.resolve(USER_CLASS)));

        generate(messageSchema(), out);

        assertFalse(Files.exists(out.resolve(USER_CLASS)));
        assertTrue(Files.isRegularFile(out.resolve(MESSAGE_CLASS)));
    }

    @Test
    void rejectsUnsupportedAdvancedVersionControls(@TempDir Path out) {
        assertThrows(IllegalArgumentException.class, () -> generate("""
                currentVersion: v1
                baseTypesData:
                  User:
                    data:
                      id: long
                versions:
                  v1:
                    typeVersions:
                      User: 0
                """, out));
    }

    private static void generate(String yaml, Path out) throws Exception {
        SourcesGenerator
                .load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                .generateSources(BASE_PACKAGE, out, false, false, true, false, false);
    }

    private static String userSchema() {
        return """
                currentVersion: v2
                baseTypesData:
                  User:
                    stringRepresenter: name
                    data:
                      id: long
                      name: String
                versions:
                  v1:
                  v2:
                    previousVersion: v1
                """;
    }

    private static String messageSchema() {
        return """
                currentVersion: v2
                baseTypesData:
                  Message:
                    data:
                      id: long
                versions:
                  v1:
                  v2:
                    previousVersion: v1
                """;
    }
}
