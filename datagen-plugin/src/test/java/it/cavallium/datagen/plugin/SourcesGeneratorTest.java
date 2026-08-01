package it.cavallium.datagen.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataInput;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.buffer.MemorySegmentBuf;
import it.cavallium.datagen.nativedata.BinaryString;
import it.cavallium.datagen.nativedata.Int52;
import it.cavallium.datagen.nativedata.NullableBinaryString;
import it.cavallium.datagen.nativedata.NullableBinaryStringSerializer;
import it.cavallium.datagen.nativedata.NullableInt52;
import it.cavallium.datagen.nativedata.NullableInt52Serializer;
import it.cavallium.datagen.nativedata.NullableString;
import it.cavallium.datagen.nativedata.NullableStringSerializer;
import it.cavallium.stream.SafeDataInput;
import java.io.ByteArrayInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

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
    void keepsFilesThatWereNotGenerated(@TempDir Path out) throws Exception {
        generate(userSchema(), out);
        var manualFile = out.resolve("org/example/current/data/Manual.java");
        Files.writeString(manualFile, "package org.example.current.data;\nclass Manual {}\n", StandardCharsets.UTF_8);

        generate(messageSchema(), out);

        assertTrue(Files.isRegularFile(manualFile));
        assertFalse(Files.exists(out.resolve(USER_CLASS)));
        assertTrue(Files.isRegularFile(out.resolve(MESSAGE_CLASS)));
    }

    @Test
    void followsMovedStringRepresenterField(@TempDir Path out) throws Exception {
        generate("""
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
                    transformations:
                      - moveData:
                          transformClass: User
                          from: name
                          to: handle
                """, out);

        var generatedUser = Files.readString(out.resolve(USER_CLASS), StandardCharsets.UTF_8);
        assertTrue(generatedUser.contains("return String.valueOf(handle())"));
        assertFalse(generatedUser.contains("return String.valueOf(name())"));
    }

    @Test
    void rejectsRemovedStringRepresenterField(@TempDir Path out) {
        assertThrows(IllegalArgumentException.class, () -> generate("""
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
                    transformations:
                      - removeData:
                          transformClass: User
                          from: name
                """, out));
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

    @Test
    void classConfigurationEqualityPreservesFieldOrder() {
        var first = new ClassConfiguration();
        first.data = new LinkedHashMap<>();
        first.data.put("id", "long");
        first.data.put("name", "String");

        var second = new ClassConfiguration();
        second.data = new LinkedHashMap<>();
        second.data.put("name", "String");
        second.data.put("id", "long");

        assertFalse(first.equals(second));
    }

	@Test
	void projectionConfigurationHashPreservesResultFieldOrder() {
		var first = new ProjectionConfiguration();
		first.sourceType = "Message";
		first.fields = new LinkedHashMap<>();
		first.fields.put("id", "messageId");
		first.fields.put("sender", "senderId");

		var second = new ProjectionConfiguration();
		second.sourceType = "Message";
		second.fields = new LinkedHashMap<>();
		second.fields.put("sender", "senderId");
		second.fields.put("id", "messageId");

		assertNotEquals(first, second);
		assertNotEquals(first.hashCode(), second.hashCode());
	}

	@Test
	void generatesAndRunsVersionAwareOwnedProjection(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				interfacesData:
				  Entity: {}
				superTypesData:
				  Entity:
				    - ImportedMessage
				baseTypesData:
				  Nested:
				    data:
				      chatId: long
				      ignoredText: String
				  ImportedMessage:
				    data:
				      ignoredValues: int[]
				      messageId: long
				      nested: -Nested
				      sender: long
				      ignoredTail: String
				projectionsData:
				  ImportedMessageSender:
				    sourceType: ImportedMessage
				    fields:
				      messageId: messageId
				      chatEntityId: nested.chatId
				      ownedText: nested.ignoredText
				      senderId: senderId
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - moveData:
				          transformClass: ImportedMessage
				          from: sender
				          to: senderId
				""", sources);

		Path projectionFile = sources.resolve("org/example/projections/ImportedMessageSenderProjection.java");
		String generated = Files.readString(projectionFile, StandardCharsets.UTF_8);
		assertTrue(generated.contains("record Result("));
		assertTrue(generated.contains("Nullablelong chatEntityId"));
		assertTrue(generated.contains("boolean chatEntityIdPresent"));
		assertTrue(generated.contains("public static final class Reader"));
		assertFalse(generated.contains("subList("));
		assertFalse(generated.contains("binaryInputStream("));

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> projection = loader.loadClass("org.example.projections.ImportedMessageSenderProjection");
			Class<?> resultType = loader.loadClass("org.example.projections.ImportedMessageSenderProjection$Result");
			Class<?> sinkType = loader.loadClass("org.example.projections.ImportedMessageSenderProjection$Sink");
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");

			for (int version = 0; version <= 1; version++) {
				Buf payload = serializedProjectionFixture(true);
				Object full = currentVersion
						.getMethod("upgradeDataToLatestVersion", int.class, baseType, SafeDataInput.class)
						.invoke(null, version, enumValue(baseType, "ImportedMessage"), BufDataInput.create(payload));
				Object expectedNested = full.getClass().getMethod("nested").invoke(full);
				Object expectedNestedValue = expectedNested.getClass().getMethod("getNullable").invoke(expectedNested);
				long expectedChatId = (long) expectedNestedValue.getClass().getMethod("chatId").invoke(expectedNestedValue);

				Object result = projection.getMethod("read", int.class, SafeDataInput.class)
						.invoke(null, version, BufDataInput.create(payload));
				assertEquals(full.getClass().getMethod("messageId").invoke(full), resultType.getMethod("messageId").invoke(result));
				assertEquals(full.getClass().getMethod("senderId").invoke(full), resultType.getMethod("senderId").invoke(result));
				Object projectedChatId = resultType.getMethod("chatEntityId").invoke(result);
				assertEquals(expectedChatId, projectedChatId.getClass().getMethod("get").invoke(projectedChatId));
				Object projectedText = resultType.getMethod("ownedText").invoke(result);
				assertEquals(expectedNestedValue.getClass().getMethod("ignoredText").invoke(expectedNestedValue),
						projectedText.getClass().getMethod("get").invoke(projectedText));
			}

			Object reader = projection.getMethod("newReader").invoke(null);
			Buf fixture = serializedProjectionFixture(true);
			int projectedPrefixLength = fixture.size() - Integer.BYTES
					- "not reached".getBytes(StandardCharsets.UTF_8).length;
			Object prefixResult = reader.getClass()
					.getMethod("read", int.class, Buf.class, int.class, int.class)
					.invoke(reader, 1, fixture, 0, projectedPrefixLength);
			assertEquals(99L, resultType.getMethod("senderId").invoke(prefixResult));
			assertReaderCursorUnbound(reader);
			byte[] padded = new byte[fixture.size() + 4];
			MemorySegment.copy(fixture.asMemorySegment(), 0, MemorySegment.ofArray(padded), 2, fixture.size());
			Object slicedResult = reader.getClass()
					.getMethod("read", int.class, Buf.class, int.class, int.class)
					.invoke(reader, 0, Buf.wrap(padded), 2, fixture.size());
			assertEquals(42L, resultType.getMethod("messageId").invoke(slicedResult));
			assertReaderCursorUnbound(reader);

			AtomicReference<List<Object>> sinkArguments = new AtomicReference<>();
			AtomicReference<Boolean> sinkObservedUnboundCursor = new AtomicReference<>(false);
			Object sink = Proxy.newProxyInstance(loader, new Class<?>[] {sinkType}, (proxy, method, args) -> {
				if (method.getName().equals("accept")) {
					assertReaderCursorUnbound(reader);
					sinkObservedUnboundCursor.set(true);
					sinkArguments.set(List.of(args));
				}
				return null;
			});
			projection.getMethod("readInto", int.class, SafeDataInput.class, sinkType)
					.invoke(null, 1, BufDataInput.create(fixture), sink);
			assertEquals(List.of(42L, true, 77L, true, "not selected", 99L), sinkArguments.get());
			reader.getClass().getMethod("readInto", int.class, Buf.class, int.class, int.class, sinkType)
					.invoke(reader, 1, fixture, 0, fixture.size(), sink);
			assertEquals(List.of(42L, true, 77L, true, "not selected", 99L), sinkArguments.get());
			assertTrue(sinkObservedUnboundCursor.get());

			InvocationTargetException truncated = assertThrows(InvocationTargetException.class,
					() -> reader.getClass().getMethod("read", int.class, Buf.class, int.class, int.class)
							.invoke(reader, 1, fixture, 0, 3));
			assertTrue(truncated.getCause() instanceof IndexOutOfBoundsException);
			assertReaderCursorUnbound(reader);
			assertThrows(InvocationTargetException.class,
					() -> reader.getClass().getMethod("read", int.class, Buf.class, int.class, int.class)
							.invoke(reader, 99, fixture, 0, fixture.size()));
			assertReaderCursorUnbound(reader);
			Object throwingSink = Proxy.newProxyInstance(loader, new Class<?>[] {sinkType}, (proxy, method, args) -> {
				assertReaderCursorUnbound(reader);
				throw new IllegalStateException("sink failure");
			});
			InvocationTargetException sinkFailure = assertThrows(InvocationTargetException.class,
					() -> reader.getClass().getMethod("readInto", int.class, Buf.class, int.class, int.class, sinkType)
							.invoke(reader, 1, fixture, 0, fixture.size(), throwingSink));
			assertTrue(sinkFailure.getCause() instanceof IllegalStateException);
			assertReaderCursorUnbound(reader);
			Object reused = reader.getClass().getMethod("read", int.class, Buf.class, int.class, int.class)
					.invoke(reader, 1, fixture, 0, fixture.size());
			assertEquals(99L, resultType.getMethod("senderId").invoke(reused));

			Object nativeResult;
			try (var arena = Arena.ofConfined()) {
				MemorySegment nativeSegment = arena.allocate(fixture.size(), 8);
				MemorySegment.copy(fixture.asMemorySegment(), 0, nativeSegment, 0, fixture.size());
				Buf nativeSource = new MemorySegmentBuf(nativeSegment) {
					@Override
					public byte[] asArray() {
						throw new AssertionError("Projection copied a native source wholesale to heap");
					}
				};
				nativeResult = reader.getClass().getMethod("read", int.class, Buf.class, int.class, int.class)
						.invoke(reader, 1, nativeSource, 0, fixture.size());
				assertEquals(42L, resultType.getMethod("messageId").invoke(nativeResult));
			}
			Object nativeOwnedText = resultType.getMethod("ownedText").invoke(nativeResult);
			assertEquals("not selected", nativeOwnedText.getClass().getMethod("get").invoke(nativeOwnedText));

			Buf emptyNested = serializedProjectionFixture(false);
			Object emptyResult = reader.getClass().getMethod("read", int.class, Buf.class, int.class, int.class)
					.invoke(reader, 1, emptyNested, 0, emptyNested.size());
			Object nullableChatId = resultType.getMethod("chatEntityId").invoke(emptyResult);
			assertEquals(true, nullableChatId.getClass().getMethod("isEmpty").invoke(nullableChatId));
		}
	}

	@Test
	void projectionUsesNativeNullableWireFormatsForReadsAndSkips(@TempDir Path temp) throws Exception {
		for (boolean binaryStrings : List.of(false, true)) {
			Path variant = temp.resolve(binaryStrings ? "binary" : "text");
			Path sources = variant.resolve("sources");
			generate("""
					currentVersion: v1
					interfacesData:
					  Entity: {}
					superTypesData:
					  Entity:
					    - Message
					baseTypesData:
					  Message:
					    data:
					      selectedText: -String
					      skippedText: -String
					      afterText: int
					      selectedInt52: -Int52
					      skippedInt52: -Int52
					      tail: long
					projectionsData:
					  NativeNullableProjection:
					    sourceType: Message
					    fields:
					      selectedText: selectedText
					      afterText: afterText
					      selectedInt52: selectedInt52
					      tail: tail
					versions:
					  v1:
					""", sources, binaryStrings);

			try (var loader = compileGeneratedSources(sources, variant.resolve("classes"))) {
				Class<?> projection = loader.loadClass("org.example.projections.NativeNullableProjection");
				Class<?> resultType = loader.loadClass("org.example.projections.NativeNullableProjection$Result");

				Object present = projection.getMethod("read", int.class, SafeDataInput.class)
						.invoke(null, 0, BufDataInput.create(serializedNativeNullableFixture(true, binaryStrings)));
				assertEquals(expectedNullableText(true, binaryStrings),
						resultType.getMethod("selectedText").invoke(present));
				assertEquals(123, resultType.getMethod("afterText").invoke(present));
				assertEquals(NullableInt52.of(Int52.fromLong(456)),
						resultType.getMethod("selectedInt52").invoke(present));
				assertEquals(999L, resultType.getMethod("tail").invoke(present));

				Object empty = projection.getMethod("read", int.class, SafeDataInput.class)
						.invoke(null, 0, BufDataInput.create(serializedNativeNullableFixture(false, binaryStrings)));
				assertEquals(expectedNullableText(false, binaryStrings),
						resultType.getMethod("selectedText").invoke(empty));
				assertEquals(123, resultType.getMethod("afterText").invoke(empty));
				assertEquals(NullableInt52.empty(), resultType.getMethod("selectedInt52").invoke(empty));
				assertEquals(999L, resultType.getMethod("tail").invoke(empty));
			}
		}
	}

	@Test
	void rejectsCrossedCustomTypeWithoutExplicitSkipper(@TempDir Path out) {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> generate("""
				currentVersion: v1
				customTypesData:
				  Opaque:
				    javaClass: java.lang.String
				    serializer: it.cavallium.datagen.nativedata.StringSerializer
				baseTypesData:
				  Message:
				    data:
				      opaque: Opaque
				      id: long
				projectionsData:
				  MessageId:
				    sourceType: Message
				    fields:
				      id: id
				versions:
				  v1:
				""", out));
		assertTrue(failure.getMessage().contains("requires customTypesData.Opaque.skipper"));
	}

	@Test
	void compilesInitializersUpgradersContextsAndCustomSkippers(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v3
				interfacesData:
				  Entity: {}
				superTypesData:
				  Entity:
				    - Message
				customTypesData:
				  Opaque:
				    javaClass: java.lang.String
				    serializer: it.cavallium.datagen.nativedata.StringSerializer
				    skipper: it.cavallium.datagen.plugin.TestStringSkipper
				baseTypesData:
				  Message:
				    data:
				      opaque: Opaque
				      messageId: long
				      senderId: int
				      nullableCode: int
				projectionsData:
				  MessageSummary:
				    sourceType: Message
				    fields:
				      messageId: messageId
				      senderId: senderId
				      derivedId: derivedId
				      nullableCode: nullableCode
				      nullableDerivedId: nullableDerivedId
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newData:
				          transformClass: Message
				          to: derivedId
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestContextLongInitializer
				          contextParameters:
				            - messageId
				      - newData:
				          transformClass: Message
				          to: nullableDerivedId
				          type: -long
				          initializer: it.cavallium.datagen.plugin.TestNullableLongInitializer
				          contextParameters:
				            - messageId
				  v3:
				    previousVersion: v2
				    transformations:
				      - upgradeData:
				          transformClass: Message
				          from: senderId
				          type: long
				          upgrader: it.cavallium.datagen.plugin.TestContextIntToLongUpgrader
				          contextParameters:
				            - messageId
				      - upgradeData:
				          transformClass: Message
				          from: nullableCode
				          type: -long
				          upgrader: it.cavallium.datagen.plugin.TestIntToNullableLongUpgrader
				          contextParameters:
				            - messageId
				""", sources);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> projection = loader.loadClass("org.example.projections.MessageSummaryProjection");
			Class<?> resultType = loader.loadClass("org.example.projections.MessageSummaryProjection$Result");
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");

			for (int version = 0; version <= 2; version++) {
				Buf payload = serializedTransformFixture(version);
				Object full = currentVersion
						.getMethod("upgradeDataToLatestVersion", int.class, baseType, SafeDataInput.class)
						.invoke(null, version, enumValue(baseType, "Message"), BufDataInput.create(payload));
				Object projected = projection.getMethod("read", int.class, SafeDataInput.class)
						.invoke(null, version, BufDataInput.create(payload));
				assertEquals(full.getClass().getMethod("messageId").invoke(full), resultType.getMethod("messageId").invoke(projected));
				assertEquals(full.getClass().getMethod("senderId").invoke(full), resultType.getMethod("senderId").invoke(projected));
				assertEquals(full.getClass().getMethod("derivedId").invoke(full), resultType.getMethod("derivedId").invoke(projected));
				assertEquals(full.getClass().getMethod("nullableCode").invoke(full), resultType.getMethod("nullableCode").invoke(projected));
				assertEquals(full.getClass().getMethod("nullableDerivedId").invoke(full), resultType.getMethod("nullableDerivedId").invoke(projected));
			}
		}
	}

	private static Buf serializedProjectionFixture(boolean nestedPresent) {
		BufDataOutput output = BufDataOutput.create();
		output.writeInt(3);
		output.writeInt(10);
		output.writeInt(20);
		output.writeInt(30);
		output.writeLong(42L);
		output.writeBoolean(nestedPresent);
		if (nestedPresent) {
			output.writeLong(77L);
			output.writeMediumText("not selected", StandardCharsets.UTF_8);
		}
		output.writeLong(99L);
		output.writeMediumText("not reached", StandardCharsets.UTF_8);
		return output.asList();
	}

	private static Buf serializedNativeNullableFixture(boolean present, boolean binaryStrings) {
		BufDataOutput output = BufDataOutput.create();
		if (binaryStrings) {
			NullableBinaryStringSerializer.INSTANCE.serialize(output, present
					? NullableBinaryString.of(new BinaryString("selected".getBytes(StandardCharsets.UTF_8)))
					: NullableBinaryString.empty());
			NullableBinaryStringSerializer.INSTANCE.serialize(output, present
					? NullableBinaryString.of(new BinaryString("skipped".getBytes(StandardCharsets.UTF_8)))
					: NullableBinaryString.empty());
		} else {
			NullableStringSerializer.INSTANCE.serialize(output,
					present ? NullableString.of("selected") : NullableString.empty());
			NullableStringSerializer.INSTANCE.serialize(output,
					present ? NullableString.of("skipped") : NullableString.empty());
		}
		output.writeInt(123);
		NullableInt52Serializer.INSTANCE.serialize(output,
				present ? NullableInt52.of(Int52.fromLong(456)) : NullableInt52.empty());
		NullableInt52Serializer.INSTANCE.serialize(output,
				present ? NullableInt52.of(Int52.fromLong(789)) : NullableInt52.empty());
		output.writeLong(999L);
		return output.asList();
	}

	private static Object expectedNullableText(boolean present, boolean binaryStrings) {
		if (binaryStrings) {
			return present
					? NullableBinaryString.of(new BinaryString("selected".getBytes(StandardCharsets.UTF_8)))
					: NullableBinaryString.empty();
		}
		return present ? NullableString.of("selected") : NullableString.empty();
	}

	private static Buf serializedTransformFixture(int version) {
		BufDataOutput output = BufDataOutput.create();
		output.writeMediumText("opaque payload", StandardCharsets.UTF_8);
		output.writeLong(40L);
		if (version < 2) {
			output.writeInt(2);
		} else {
			output.writeLong(42L);
		}
		if (version < 2) {
			output.writeInt(3);
		} else {
			output.writeBoolean(false);
		}
		if (version >= 1) {
			output.writeLong(45L);
			boolean nullableDerivedPresent = version == 1;
			output.writeBoolean(nullableDerivedPresent);
			if (nullableDerivedPresent) {
				output.writeLong(47L);
			}
		}
		return output.asList();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Object enumValue(Class<?> enumType, String name) {
		return Enum.valueOf((Class<? extends Enum>) enumType, name);
	}

	private static URLClassLoader compileGeneratedSources(Path sources, Path classes) throws Exception {
		Files.createDirectories(classes);
		var compiler = ToolProvider.getSystemJavaCompiler();
		var diagnostics = new DiagnosticCollector<JavaFileObject>();
		try (var fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			var files = new ArrayList<Path>();
			try (var paths = Files.walk(sources)) {
				paths.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
			}
			var units = fileManager.getJavaFileObjectsFromPaths(files);
			String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
			boolean success = compiler.getTask(null, fileManager, diagnostics,
					List.of("--release", "25", "-classpath", classPath, "-d", classes.toString()), null, units).call();
			assertTrue(success, () -> diagnostics.getDiagnostics().toString());
		}
		return new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()}, SourcesGeneratorTest.class.getClassLoader());
	}

	private static void assertReaderCursorUnbound(Object reader) throws ReflectiveOperationException {
		var cursorField = reader.getClass().getDeclaredField("cursor");
		cursorField.setAccessible(true);
		Object cursor = cursorField.get(reader);
		var sourceField = cursor.getClass().getDeclaredField("source");
		sourceField.setAccessible(true);
		assertEquals(null, sourceField.get(cursor));
	}

    private static void generate(String yaml, Path out) throws Exception {
		generate(yaml, out, false);
	}

	private static void generate(String yaml, Path out, boolean binaryStrings) throws Exception {
        SourcesGenerator
                .load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
				.generateSources(BASE_PACKAGE, out, false, false, true, false, binaryStrings);
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
