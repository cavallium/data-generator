package it.cavallium.datagen.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WireSchemaLedgerTest {

	@TempDir
	Path temp;

	@Test
	void freezesExistingNumberedVersionUntilExplicitlyUpdated() throws Exception {
		Path ledger = temp.resolve("schema.wire");
		SourcesGenerator original = generator("""
				currentVersion: v1
				baseTypesData:
				  Row:
				    data:
				      id: long
				versions:
				  v1:
				""");

		IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
				() -> original.verifyOrUpdateWireSchemaLedger(ledger, false, false));
		assertTrue(missing.getMessage().contains("ledger is missing"));

		original.verifyOrUpdateWireSchemaLedger(ledger, false, true);
		String frozen = Files.readString(ledger);
		assertTrue(frozen.startsWith("datagen-wire-schema-ledger-v1\n"));
		original.verifyOrUpdateWireSchemaLedger(ledger, false, false);

		SourcesGenerator incompatible = generator("""
				currentVersion: v1
				baseTypesData:
				  Row:
				    data:
				      id: long
				      active: boolean
				versions:
				  v1:
				""");
		IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
				() -> incompatible.verifyOrUpdateWireSchemaLedger(ledger, false, false));
		assertTrue(mismatch.getMessage().contains("existing numbered version changed"));
		assertEquals(frozen, Files.readString(ledger), "failed validation must not rewrite the ledger");

		incompatible.verifyOrUpdateWireSchemaLedger(ledger, false, true);
		incompatible.verifyOrUpdateWireSchemaLedger(ledger, false, false);
		assertTrue(!frozen.equals(Files.readString(ledger)));
	}

	private static SourcesGenerator generator(String yaml) throws Exception {
		return SourcesGenerator.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
	}
}
