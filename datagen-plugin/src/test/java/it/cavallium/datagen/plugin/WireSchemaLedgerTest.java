package it.cavallium.datagen.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
		assertTrue(frozen.startsWith("datagen-wire-schema-ledger-v2\n"));
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

	@Test
	void appendingVersionDoesNotChangeHistoricalDigest() throws Exception {
		Path ledger = temp.resolve("append-version.wire");
		generator("""
				currentVersion: v1
				baseTypesData:
				  Row:
				    data:
				      id: long
				versions:
				  v1:
				""").verifyOrUpdateWireSchemaLedger(ledger, false, true);
		String original = Files.readString(ledger);
		generator("""
				currentVersion: v2
				baseTypesData:
				  Row:
				    data:
				      id: long
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				""").verifyOrUpdateWireSchemaLedger(ledger, false, true);
		String evolved = Files.readString(ledger);

		assertEquals(versionLine(original, 0), versionLine(evolved, 0));
		assertTrue(evolved.contains("version=1 name=v2"));
	}

	@Test
	void migratesV1WithoutChangingFrozenSha256() throws Exception {
		Path ledger = temp.resolve("legacy-v1.wire");
		String frozenSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
		Files.writeString(ledger, """
				datagen-wire-schema-ledger-v1
				binary-strings=false
				version=0 name=v1 sha256=%s
				""".formatted(frozenSha));
		SourcesGenerator generator = generator("""
				currentVersion: v2
				baseTypesData:
				  Row: {data: {id: long}}
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				""");

		generator.verifyOrUpdateWireSchemaLedger(ledger, false, true);
		String migrated = Files.readString(ledger);
		assertTrue(migrated.startsWith("datagen-wire-schema-ledger-v2\n"));
		assertTrue(versionLine(migrated, 0).contains("sha256=" + frozenSha + " "));
		assertTrue(versionLine(migrated, 0).contains("schema-sha256="));
		generator.verifyOrUpdateWireSchemaLedger(ledger, false, false);
	}

	@Test
	void newTypeAndSubtypeExistOnlySinceContainingVersion() throws Exception {
		Path ledger = temp.resolve("new-type.wire");
		generator("""
				currentVersion: v1
				baseTypesData:
				  A: {data: {value: int}}
				  B: {data: {value: long}}
				superTypesData:
				  Choice: [A, B]
				versions:
				  v1:
				""").verifyOrUpdateWireSchemaLedger(ledger, false, true);
		String original = Files.readString(ledger);
		SourcesGenerator evolvedGenerator = generator("""
				currentVersion: v2
				baseTypesData:
				  A: {data: {value: int}}
				  B: {data: {value: long}}
				superTypesData:
				  Choice: [A, B]
				projectionsData:
				  CValue:
				    sourceType: C
				    fields: {value: value}
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newType:
				          name: C
				          data: {value: String}
				      - newSubtype:
				          superType: Choice
				          subtype: C
				""");
		evolvedGenerator.verifyOrUpdateWireSchemaLedger(ledger, false, true);
		String evolved = Files.readString(ledger);

		assertEquals(versionLine(original, 0), versionLine(evolved, 0));
		assertFalse(versionLine(evolved, 1).equals(versionLine(evolved, 0)));

		Path output = temp.resolve("generated");
		evolvedGenerator.generateSources("example.generated", output, true, true, false);
		assertFalse(Files.exists(output.resolve("example/generated/v0/data/C.java")));
		assertTrue(Files.exists(output.resolve("example/generated/current/data/C.java")));
		String oldSerializer = Files.readString(output.resolve(
				"example/generated/v0/serializers/ChoiceSerializer.java"));
		String currentSerializer = Files.readString(output.resolve(
				"example/generated/current/serializers/ChoiceSerializer.java"));
		assertFalse(oldSerializer.contains("case 2"));
		assertTrue(currentSerializer.contains("case 2"));
	}

	@Test
	void rejectsFutureTypeReferencesAndDuplicateUnionAppend() throws Exception {
		IllegalArgumentException futureReference = assertThrows(IllegalArgumentException.class, () -> generator("""
				currentVersion: v2
				baseTypesData:
				  Root: {data: {future: Future}}
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newType:
				          name: Future
				          data: {}
				""").wireSchemaLedger(false));
		assertTrue(futureReference.getMessage().contains("before it is introduced"));

		IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () -> generator("""
				currentVersion: v2
				baseTypesData:
				  A: {data: {}}
				superTypesData:
				  Choice: [A]
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newSubtype:
				          superType: Choice
				          subtype: A
				""").wireSchemaLedger(false));
		assertTrue(duplicate.getMessage().contains("existing alternative"));
	}

	@Test
	void ledgerRejectsUnionInsertionOrReorder() throws Exception {
		Path ledger = temp.resolve("ordered.wire");
		generator("""
				currentVersion: v1
				baseTypesData:
				  A: {data: {}}
				  B: {data: {}}
				  C: {data: {}}
				superTypesData:
				  Choice: [A, B, C]
				versions:
				  v1:
				""").verifyOrUpdateWireSchemaLedger(ledger, false, true);

		SourcesGenerator inserted = generator("""
				currentVersion: v1
				baseTypesData:
				  A: {data: {}}
				  B: {data: {}}
				  C: {data: {}}
				superTypesData:
				  Choice: [A, C, B]
				versions:
				  v1:
				""");
		IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
				() -> inserted.verifyOrUpdateWireSchemaLedger(ledger, false, false));
		assertTrue(mismatch.getMessage().contains("existing numbered version changed"));
	}

	@Test
	void laterFieldDoesNotRetroactivelyCreateHistoricalDependentType() throws Exception {
		Path ledger = temp.resolve("dependency-baseline.wire");
		generator("""
				currentVersion: v2
				baseTypesData:
				  Leaf: {data: {x: int}}
				  Holder: {data: {id: long}}
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newData:
				          transformClass: Leaf
				          to: y
				          type: int
				          initializer: example.IntInitializer
				""").verifyOrUpdateWireSchemaLedger(ledger, false, true);

		SourcesGenerator evolved = generator("""
				currentVersion: v3
				baseTypesData:
				  Leaf: {data: {x: int}}
				  Holder: {data: {id: long}}
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newData:
				          transformClass: Leaf
				          to: y
				          type: int
				          initializer: example.IntInitializer
				  v3:
				    previousVersion: v2
				    transformations:
				      - newData:
				          transformClass: Holder
				          to: leaf
				          type: Leaf
				          initializer: example.LeafInitializer
				""");
		evolved.verifyOrUpdateWireSchemaLedger(ledger, false, true);
		Path output = temp.resolve("baseline-generated");
		evolved.generateSources("example.generated", output, true, true, false);

		assertFalse(Files.exists(output.resolve("example/generated/v0/data/Holder.java")));
		assertTrue(Files.exists(output.resolve("example/generated/v1/data/Holder.java")));
	}

	@Test
	void newTypeDefinitionPreservesFieldOrderInEquality() {
		var first = new NewTypeConfiguration();
		first.name = "Ordered";
		first.data = new LinkedHashMap<>();
		first.data.put("first", "int");
		first.data.put("second", "long");
		var reordered = new NewTypeConfiguration();
		reordered.name = "Ordered";
		reordered.data = new LinkedHashMap<>();
		reordered.data.put("second", "long");
		reordered.data.put("first", "int");

		assertFalse(first.equals(reordered));
	}

	private static String versionLine(String ledger, int version) {
		return ledger.lines()
				.filter(line -> line.startsWith("version=" + version + " "))
				.findFirst()
				.orElseThrow();
	}

	private static SourcesGenerator generator(String yaml) throws Exception {
		return SourcesGenerator.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
	}
}
