package it.cavallium.datagen.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.BufDataInput;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.buffer.MemorySegmentBuf;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeDataInputStream;
import java.io.ByteArrayInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
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
	private static final DecodeLimits LIMITS = DecodeLimits.unlimited();
    private static final Path USER_CLASS = Path.of("org/example/current/data/User.java");
    private static final Path MESSAGE_CLASS = Path.of("org/example/current/data/Message.java");
	private static final List<String> FUSED_FORMAT_1_GOLDENS = List.of(
			"0000000e72656d6f766564206f70617175650000000001e24000000007000000066e657374656401"
					+ "00000008000000086e756c6c61626c6500000002000000090000000761727261792d610000000a"
					+ "0000000761727261792d62000000000b0000000663686f696365",
			"00000007000000066e65737465640100000008000000086e756c6c61626c650000000200000009"
					+ "0000000761727261792d610000000a0000000761727261792d62000000000b0000000663686f696365"
					+ "000000000000007b",
			"00000000000003ef000000066e65737465640100000000000003f0000000086e756c6c61626c650000000200000000000003f10000000761727261792d6100000000000003f20000000761727261792d620000000000000003f30000000663686f696365000000000000007b");
	private static final String NATIVE_FORMAT_1_GOLDEN =
			"017f123403a91020304001020304050607083fc00000c0020000000000000000000477697265000102030405"
					+ "0601fffffff90100056d617962650000000201000000000201fe000000020003fffc00000002004103a90000"
					+ "000200000005fffffffa000000020000000000000007fffffffffffffff8000000023fa00000c06000000000"
					+ "00024004000000000000c013000000000000000000020000000000000100010203040506";
	private static final List<String> NULLABLE_STRING_INT52_GOLDENS = List.of(
			"0000000000808080808011223344",
			"010002726d01000373656c0100036d61700100047669657701000170808080808011223344",
			"00000000000102030405060702030405060708030405060708090405060708090a05060708090a0b11223344",
			"010002726d01000373656c0100036d617001000476696577010001700102030405060702030405060708"
					+ "030405060708090405060708090a05060708090a0b11223344");

    @Test
    void supportsNoOpVersionWithoutTransformations(@TempDir Path out) throws Exception {
        generate(userSchema(), out);

        assertTrue(Files.isRegularFile(out.resolve(USER_CLASS)));
    }

	@Test
	void shardsVersionedBoundReaderFactoryByBaseType(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate(boundReaderFactorySchema(3, 4), sources);

		String currentVersionSource = Files.readString(
				sources.resolve("org/example/current/CurrentVersion.java"));
		assertTrue(currentVersionSource.contains(
				"case T0 -> newT0BoundReader(version, limits);"), currentVersionSource);
		assertTrue(currentVersionSource.contains(
				"private static BoundReader<T0> newT0BoundReader(int version, DecodeLimits limits)"),
				currentVersionSource);
		assertTrue(currentVersionSource.contains("case 0 -> new T0V0Reader(limits);"), currentVersionSource);
		assertTrue(currentVersionSource.contains("case 3 -> new T0V3Reader(limits);"), currentVersionSource);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Object t0 = enumValue(baseType, "T0");
			for (int version = 0; version < 4; version++) {
				Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
						.invoke(null, version, t0, LIMITS);
				assertTrue(reader.getClass().getSimpleName().equals("T0V" + version + "Reader"));
			}
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void generatesFlattenedOwnedValueModelForCurrentAndHistoricalVersions(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				baseTypesData:
				  Empty:
				    data: {}
				  Child:
				    data:
				      value: int
				  Value:
				    data:
				      nullableCount: -long
				      nullableChild: -Child
				      ints: int[]
				      children: Child[]
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newData:
				          transformClass: Child
				          to: tail
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				""", sources);

		String valueReadPlan = Files.readString(
				sources.resolve("org/example/current/readers/ValueReadPlan.java"));
		assertTrue(valueReadPlan.contains("final boolean raw0Present = input.readBoolean()"), valueReadPlan);
		assertTrue(valueReadPlan.contains("final long raw0Value = raw0Present ? (long) input.readLong() : 0"),
				valueReadPlan);
		assertTrue(valueReadPlan.contains("final boolean raw1Present = input.readBoolean()"), valueReadPlan);
		assertFalse(valueReadPlan.contains("final Nullablelong raw0 ="), valueReadPlan);
		assertFalse(valueReadPlan.contains("final NullableChild raw1 ="), valueReadPlan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> valueType = loader.loadClass("org.example.current.data.Value");
			Class<?> childType = loader.loadClass("org.example.current.data.Child");
			Class<?> emptyType = loader.loadClass("org.example.current.data.Empty");
			Class<?> nullableChildType = loader.loadClass("org.example.current.data.nullables.NullableChild");
			Class<?> childrenArrayType = java.lang.reflect.Array.newInstance(childType, 0).getClass();

			assertFalse(valueType.isRecord());
			assertFalse(nullableChildType.isRecord());
			assertTrue(java.lang.reflect.Modifier.isFinal(valueType.getModifiers()));
			assertTrue(java.lang.reflect.Modifier.isFinal(nullableChildType.getModifiers()));
			assertFalse(java.io.Serializable.class.isAssignableFrom(valueType));
			assertEquals(boolean.class, valueType.getDeclaredField("$datagen$present$nullableCount").getType());
			assertEquals(long.class, valueType.getDeclaredField("nullableCount").getType());
			assertEquals(childType, valueType.getDeclaredField("nullableChild").getType());
			assertEquals(int[].class, valueType.getDeclaredField("ints").getType());
			assertEquals(childrenArrayType, valueType.getDeclaredField("children").getType());
			for (var field : valueType.getDeclaredFields()) {
				assertFalse(List.class.isAssignableFrom(field.getType()), field::toString);
				assertFalse(it.cavallium.datagen.TypedNullable.class.isAssignableFrom(field.getType()), field::toString);
			}

			Object childA = childType.getMethod("of", int.class, long.class).invoke(null, 10, 123L);
			Object childB = childType.getMethod("of", int.class, long.class).invoke(null, 20, 123L);
			var valueOf = valueType.getMethod("of", boolean.class, long.class, childType,
					int[].class, childrenArrayType);
			var valueUnsafeOfOwned = valueType.getMethod("unsafeOfOwned", boolean.class, long.class, childType,
					int[].class, childrenArrayType);

			int[] callerInts = {1, 2};
			Object callerChildren = java.lang.reflect.Array.newInstance(childType, 2);
			java.lang.reflect.Array.set(callerChildren, 0, childA);
			java.lang.reflect.Array.set(callerChildren, 1, childB);
			Object value = valueOf.invoke(null, true, 43L, childA, callerInts, callerChildren);
			assertNotSame(callerInts, valueType.getMethod("intsUnsafeArray").invoke(value));
			assertNotSame(callerChildren, valueType.getMethod("childrenUnsafeArray").invoke(value));
			callerInts[0] = 999;
			java.lang.reflect.Array.set(callerChildren, 0, childB);
			assertEquals(1, valueType.getMethod("ints", int.class).invoke(value, 0));
			assertSame(childA, valueType.getMethod("children", int.class).invoke(value, 0));
			assertArrayEquals(new int[] {1, 2}, (int[]) valueType.getMethod("intsCopy").invoke(value));
			assertNotSame(valueType.getMethod("intsUnsafeArray").invoke(value),
					valueType.getMethod("intsCopy").invoke(value));
			assertEquals(2, valueType.getMethod("childrenSize").invoke(value));
			assertEquals(true, valueType.getMethod("hasNullableCount").invoke(value));
			assertEquals(43L, valueType.getMethod("nullableCount").invoke(value));
			assertEquals(true, valueType.getMethod("hasNullableChild").invoke(value));
			assertSame(childA, valueType.getMethod("nullableChild").invoke(value));
			assertSame(childA, valueType.getMethod("nullableChildOrNull").invoke(value));

			Object equalChildren = java.lang.reflect.Array.newInstance(childType, 2);
			java.lang.reflect.Array.set(equalChildren, 0, childA);
			java.lang.reflect.Array.set(equalChildren, 1, childB);
			Object equalValue = valueOf.invoke(null, true, 43L, childA, new int[] {1, 2}, equalChildren);
			assertEquals(value, equalValue);
			assertEquals(value.hashCode(), equalValue.hashCode());
			assertTrue(value.toString().contains("ints=[1, 2]"), value::toString);

			int[] ownedInts = {7, 8};
			Object ownedChildren = java.lang.reflect.Array.newInstance(childType, 1);
			java.lang.reflect.Array.set(ownedChildren, 0, childB);
			Object owned = valueUnsafeOfOwned.invoke(null, true, 44L, childB, ownedInts, ownedChildren);
			assertSame(ownedInts, valueType.getMethod("intsUnsafeArray").invoke(owned));
			assertSame(ownedChildren, valueType.getMethod("childrenUnsafeArray").invoke(owned));

			Object absentA = valueUnsafeOfOwned.invoke(null, false, 999L, null, new int[0],
					java.lang.reflect.Array.newInstance(childType, 0));
			Object absentB = valueUnsafeOfOwned.invoke(null, false, -1L, null, new int[0],
					java.lang.reflect.Array.newInstance(childType, 0));
			assertEquals(false, valueType.getMethod("hasNullableCount").invoke(absentA));
			assertEquals(false, valueType.getMethod("hasNullableChild").invoke(absentA));
			assertEquals(null, valueType.getMethod("nullableChildOrNull").invoke(absentA));
			InvocationTargetException absentPrimitive = assertThrows(InvocationTargetException.class,
					() -> valueType.getMethod("nullableCount").invoke(absentA));
			assertTrue(absentPrimitive.getCause() instanceof java.util.NoSuchElementException);
			InvocationTargetException absentReference = assertThrows(InvocationTargetException.class,
					() -> valueType.getMethod("nullableChild").invoke(absentA));
			assertTrue(absentReference.getCause() instanceof java.util.NoSuchElementException);
			var nullableCountField = valueType.getDeclaredField("nullableCount");
			nullableCountField.setAccessible(true);
			assertEquals(0L, nullableCountField.getLong(absentA));
			assertSame(valueType.getMethod("intsUnsafeArray").invoke(absentA),
					valueType.getMethod("intsUnsafeArray").invoke(absentB));
			assertSame(valueType.getMethod("childrenUnsafeArray").invoke(absentA),
					valueType.getMethod("childrenUnsafeArray").invoke(absentB));
			assertSame(valueType.getMethod("intsUnsafeArray").invoke(absentA),
					valueType.getMethod("intsCopy").invoke(absentA));

			Object builder = valueType.getMethod("builder").invoke(value);
			int[] builderInts = {31, 32};
			Object builderChildren = java.lang.reflect.Array.newInstance(childType, 1);
			java.lang.reflect.Array.set(builderChildren, 0, childB);
			builder.getClass().getMethod("setInts", int[].class).invoke(builder, builderInts);
			builder.getClass().getMethod("setChildren", childrenArrayType).invoke(builder, builderChildren);
			builderInts[0] = -1;
			java.lang.reflect.Array.set(builderChildren, 0, childA);
			Object built = builder.getClass().getMethod("build").invoke(builder);
			assertEquals(31, valueType.getMethod("ints", int.class).invoke(built, 0));
			assertSame(childB, valueType.getMethod("children", int.class).invoke(built, 0));

			Object nullChildren = java.lang.reflect.Array.newInstance(childType, 1);
			InvocationTargetException nullElement = assertThrows(InvocationTargetException.class,
					() -> valueOf.invoke(null, false, 0L, null, new int[0], nullChildren));
			assertTrue(nullElement.getCause() instanceof NullPointerException);

			Object empty1 = emptyType.getMethod("of").invoke(null);
			Object empty2 = emptyType.getMethod("unsafeOfOwned").invoke(null);
			assertSame(empty1, empty2);
			Object nullableEmpty1 = nullableChildType.getMethod("empty").invoke(null);
			Object nullableEmpty2 = nullableChildType.getMethod("ofNullable", childType).invoke(null, new Object[] {null});
			assertSame(nullableEmpty1, nullableEmpty2);
			Object nullablePresent = nullableChildType.getMethod("of", childType).invoke(null, childA);
			assertSame(childA, nullableChildType.getMethod("getNullable").invoke(nullablePresent));

			Object codecObject = loader.loadClass("org.example.current.Version")
					.getField("ValueSerializerInstance").get(null);
			DataCodec<Object> codec = (DataCodec<Object>) codecObject;
			BufDataOutput output = BufDataOutput.create();
			codec.serialize(output, value);
			Object decoded = codec.read(BufDataInput.create(output.asList(), LIMITS));
			assertEquals(value, decoded);

			Class<?> oldValueType = loader.loadClass("org.example.v0.data.Value");
			Class<?> oldChildType = loader.loadClass("org.example.v0.data.Child");
			Class<?> oldNullableChildType = loader.loadClass("org.example.v0.data.nullables.NullableChild");
			Class<?> oldChildrenArrayType = java.lang.reflect.Array.newInstance(oldChildType, 0).getClass();
			assertFalse(oldValueType.isRecord());
			assertFalse(oldNullableChildType.isRecord());
			assertTrue(java.lang.reflect.Modifier.isFinal(oldValueType.getModifiers()));
			assertEquals(long.class, oldValueType.getDeclaredField("nullableCount").getType());
			Object oldChild = oldChildType.getMethod("of", int.class).invoke(null, 10);
			Object oldChildren = java.lang.reflect.Array.newInstance(oldChildType, 2);
			java.lang.reflect.Array.set(oldChildren, 0, oldChild);
			java.lang.reflect.Array.set(oldChildren, 1, oldChildType.getMethod("of", int.class).invoke(null, 20));
			Object oldValue = oldValueType.getMethod("unsafeOfOwned", boolean.class, long.class, oldChildType,
					int[].class, oldChildrenArrayType).invoke(null, true, 43L, oldChild, new int[] {1, 2}, oldChildren);
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Object upgraded = currentVersion.getMethod("upgradeDataToLatestVersion", int.class, Object.class)
					.invoke(null, 0, oldValue);
			assertEquals(value, upgraded);
		}
	}

	@Test
	void coalescesAdjacentIdenticalNormalReadPlans(@TempDir Path out) throws Exception {
		generate("""
				currentVersion: v3
				baseTypesData:
				  User:
				    data:
				      id: long
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - moveData:
				          transformClass: User
				          from: id
				          to: renamed
				  v3:
				    previousVersion: v2
				    transformations:
				      - newData:
				          transformClass: User
				          to: initialized
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				""", out);

		String plan = Files.readString(out.resolve("org/example/current/readers/UserReadPlan.java"));
		assertTrue(plan.contains("case 0 -> readPlan0(input, state)"));
		assertTrue(plan.contains("case 1 -> readPlan0(input, state)"));
		assertTrue(plan.contains("case 2 -> readPlan2(input, state)"));
		assertFalse(plan.contains("private static User readPlan1("), plan);
	}

	@Test
	void coalescesFixedWidthDiscardSpans(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				baseTypesData:
				  Packet:
				    data:
				      discardedInt: int
				      keptInt: int
				      discardedLong: long
				      keptLong: long
				      discardedValues: int[]
				      kept: long
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - removeData:
				          transformClass: Packet
				          from: discardedInt
				      - removeData:
				          transformClass: Packet
				          from: discardedLong
				      - removeData:
				          transformClass: Packet
				          from: discardedValues
				""", sources);

		String plan = Files.readString(sources.resolve("org/example/current/readers/PacketReadPlan.java"));
		assertTrue(plan.contains("fixedInput0.reserve(24)"), plan);
		assertTrue(plan.contains("fixedInput0.getIntAt(fixedRun0 + 4)"), plan);
		assertTrue(plan.contains("fixedInput0.getLongAt(fixedRun0 + 16)"), plan);
		assertTrue(plan.contains("ProjectionReadSupport.checkedArrayBytes(size, 4)"), plan);
		assertFalse(plan.contains("for (int i = 0; i < size; i++)"), plan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object packetType = enumValue(baseType, "Packet");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(7);
			output.writeInt(71);
			output.writeLong(8L);
			output.writeLong(81L);
			output.writeInt(3);
			output.writeInt(10);
			output.writeInt(11);
			output.writeInt(12);
			output.writeLong(99L);
			Buf payload = output.asList();

			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, packetType, LIMITS);
			Object packet = invokeBoundReader(reader, payload, 0, payload.size());
			assertEquals(71, packet.getClass().getMethod("keptInt").invoke(packet));
			assertEquals(81L, packet.getClass().getMethod("keptLong").invoke(packet));
			assertEquals(99L, packet.getClass().getMethod("kept").invoke(packet));
			assertReaderCursorUnbound(reader);

			SafeDataInput streamInput = new SafeDataInputStream(new SafeByteArrayInputStream(payload.asArray()), LIMITS);
			Object streamPacket = currentVersion.getMethod("read", int.class, baseType, SafeDataInput.class)
					.invoke(null, 0, packetType, streamInput);
			assertEquals(packet, streamPacket);
			InvocationTargetException truncated = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size() - 1));
			assertTrue(truncated.getCause() instanceof MalformedDataException);
			assertReaderCursorUnbound(reader);
		}
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
	void digestManifestCacheDetectsEveryInputAndOutputChange(@TempDir Path temp) throws Exception {
		String schema = userSchema();
		Path out = temp.resolve("sources");
		Path manifest = out.resolve("org/example/.datagen-manifest-v1");
		Path user = out.resolve(USER_CLASS);
		SourcesGenerator generator = SourcesGenerator.load(
				new ByteArrayInputStream(schema.getBytes(StandardCharsets.UTF_8)));

		generator.generateSources(BASE_PACKAGE, out, false, false, false, false);
		String initialManifest = Files.readString(manifest);
		var initialSources = sourceSnapshot(out);
		assertTrue(initialManifest.startsWith("data-generator-manifest-v1\nfingerprint="));
		assertFalse(Files.exists(out.resolve("org/example/.hash")));

		FileTime sentinel = FileTime.fromMillis(1_234_000L);
		Files.setLastModifiedTime(user, sentinel);
		generator.generateSources(BASE_PACKAGE, out, false, false, false, false);
		assertEquals(sentinel, Files.getLastModifiedTime(user), "identical inputs must be a cache hit");
		assertEquals(initialManifest, Files.readString(manifest));

		Files.delete(user);
		generator.generateSources(BASE_PACKAGE, out, false, false, false, false);
		assertTrue(Files.isRegularFile(user));
		assertEquals(initialSources, sourceSnapshot(out));

		Files.writeString(user, "\n// edited generated source\n", StandardCharsets.UTF_8,
				java.nio.file.StandardOpenOption.APPEND);
		generator.generateSources(BASE_PACKAGE, out, false, false, false, false);
		assertEquals(initialSources, sourceSnapshot(out));

		generator.generateSources(BASE_PACKAGE, out, false, true, false, false);
		String oldSerializerManifest = Files.readString(manifest);
		assertNotEquals(initialManifest, oldSerializerManifest);
		generator.generateSources(BASE_PACKAGE, out, false, false, true, false);
		String binaryManifest = Files.readString(manifest);
		assertNotEquals(oldSerializerManifest, binaryManifest);
		generator.generateSources(BASE_PACKAGE, out, false, false, false, true);
		String vectorManifest = Files.readString(manifest);
		assertNotEquals(binaryManifest, vectorManifest);

		generator.generateSources(BASE_PACKAGE, out, false, false, false, false);
		assertEquals(initialManifest, Files.readString(manifest));
		assertEquals(initialSources, sourceSnapshot(out));

		Path second = temp.resolve("second");
		generator.generateSources(BASE_PACKAGE, second, false, false, false, false);
		assertEquals(initialManifest, Files.readString(second.resolve("org/example/.datagen-manifest-v1")));
		assertEquals(initialSources, sourceSnapshot(second));
	}

	@Test
	void unionOrderParticipatesInTheFingerprintAndWireDiscriminator(@TempDir Path temp) throws Exception {
		Path out = temp.resolve("sources");
		String first = unionSchema(List.of("A", "B"));
		String reordered = unionSchema(List.of("B", "A"));
		generate(first, out);
		String firstManifest = Files.readString(out.resolve("org/example/.datagen-manifest-v1"));
		String firstSerializer = Files.readString(out.resolve("org/example/current/serializers/ChoiceSerializer.java"));

		generate(reordered, out);
		String reorderedManifest = Files.readString(out.resolve("org/example/.datagen-manifest-v1"));
		String reorderedSerializer = Files.readString(out.resolve("org/example/current/serializers/ChoiceSerializer.java"));
		assertNotEquals(firstManifest, reorderedManifest);
		assertNotEquals(firstSerializer, reorderedSerializer);
		assertTrue(firstSerializer.contains("case 0 -> (A)"), firstSerializer);
		assertTrue(reorderedSerializer.contains("case 0 -> (B)"), reorderedSerializer);
	}

	@Test
	void validatesUnionAlternativesBeforeCreatingOutputAndSupportsUnsignedId255(@TempDir Path temp)
			throws Exception {
		for (String invalidUnion : List.of(
				"  Choice: [A, A]\n",
				"  Choice: [A, Missing]\n",
				"  Choice: []\n",
				"  Choice: [A, '']\n",
				"  Choice:\n    - A\n    -\n")) {
			Path out = temp.resolve("invalid-" + Math.abs(invalidUnion.hashCode()));
			String schema = """
					currentVersion: v1
					baseTypesData:
					  A: { data: {} }
					superTypesData:
					""" + invalidUnion + "versions:\n  v1:\n";
			assertThrows(IllegalArgumentException.class, () -> generate(schema, out));
			assertFalse(Files.exists(out), "invalid unions must fail before output creation");
		}

		String tooMany = wideUnionSchema(257);
		Path rejected = temp.resolve("too-many");
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> generate(tooMany, rejected));
		assertTrue(failure.getMessage().contains("at most 256"));
		assertFalse(Files.exists(rejected));

		Path sources = temp.resolve("max-union");
		generate(wideUnionSchema(256), sources);
		try (var loader = compileGeneratedSources(sources, temp.resolve("max-union-classes"))) {
			Class<?> version = loader.loadClass("org.example.current.Version");
			Object codec = version.getField("ChoiceSerializerInstance").get(null);
			Object subtype255 = loader.loadClass("org.example.current.data.T255").getMethod("of").invoke(null);
			BufDataOutput output = BufDataOutput.create();
			java.lang.reflect.Method serialize = java.util.Arrays.stream(codec.getClass().getMethods())
					.filter(method -> method.getName().equals("serialize") && method.getParameterCount() == 2)
					.findFirst().orElseThrow();
			serialize.invoke(codec, output, subtype255);
			assertArrayEquals(new byte[] {(byte) 0xff}, output.asList().asArray());
			Object decoded = codec.getClass().getMethod("read", SafeDataInput.class)
					.invoke(codec, BufDataInput.create(output.asList(), LIMITS));
			assertSame(subtype255, decoded);
		}
	}

	@Test
	void validatesPublicSurfaceAcrossHistoryAndKeepsPrivateNamesHygienic(@TempDir Path temp)
			throws Exception {
		for (String fields : List.of(
				"      items: int[]\n      itemsSize: int\n",
				"      maybe: -String\n      hasMaybe: boolean\n",
				"      x: int\n      X: int\n",
				"      toString: int\n",
				"      hashCode: int\n",
				"      getClass: int\n",
				"      getBaseType$: int\n",
				"      builder: int\n")) {
			Path out = temp.resolve("collision-" + Math.abs(fields.hashCode()));
			IllegalArgumentException collision = assertThrows(IllegalArgumentException.class,
					() -> generate(recordSchema(fields), out));
			assertTrue(collision.getMessage().contains("originate"), collision::getMessage);
			assertTrue(collision.getMessage().contains("version"), collision::getMessage);
			assertFalse(Files.exists(out));
		}

		IllegalArgumentException unionMutatorCollision = assertThrows(IllegalArgumentException.class,
				() -> generate("""
						currentVersion: v1
						interfacesData:
						  Choice:
						    commonData:
						      maybe: -String
						      clearMaybe: boolean
						superTypesData:
						  Choice: [A]
						baseTypesData:
						  A: { data: {} }
						  NullableTypeSeed:
						    data:
						      seed: -String
						versions:
						  v1:
						""", temp.resolve("union-mutator-collision")));
		assertTrue(unionMutatorCollision.getMessage().contains("union interface Choice"));
		assertTrue(unionMutatorCollision.getMessage().contains("both"));
		assertTrue(unionMutatorCollision.getMessage().contains("version 0"));

		IllegalArgumentException inheritedMetadataCollision = assertThrows(IllegalArgumentException.class,
				() -> generate("""
						currentVersion: v1
						interfacesData:
						  Parent: {}
						  Child: {}
						superTypesData:
						  Parent: [Child]
						  Child: [A]
						baseTypesData:
						  A:
						    data:
						      getMetaId$Parent: int
						versions:
						  v1:
						""", temp.resolve("inherited-metadata-collision")));
		assertTrue(inheritedMetadataCollision.getMessage().contains("field A.getMetaId$Parent"));
		assertTrue(inheritedMetadataCollision.getMessage().contains("union metadata method for Parent"));
		assertTrue(inheritedMetadataCollision.getMessage().contains("version 0"));

		String historicalOnly = """
				currentVersion: v2
				baseTypesData:
				  Value:
				    data:
				      items: int[]
				      itemsSize: int
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - removeData: { transformClass: Value, from: itemsSize }
				""";
		IllegalArgumentException historicalCollision = assertThrows(IllegalArgumentException.class,
				() -> generate(historicalOnly, temp.resolve("historical-collision")));
		assertTrue(historicalCollision.getMessage().contains("version 0"));

		Path legalSources = temp.resolve("legal");
		generate(recordSchema("""
			      x: int
			      X: String
			      maybe: -int
			      maybePresent: int
			      maybeFirst: int
			      in: int
			      codecState: int
			      data: int
			      result: int
			      size: int
			      index: int
			      source: int
			      original: int
			"""), legalSources);
		try (var ignored = compileGeneratedSources(legalSources, temp.resolve("legal-classes"))) {
			String value = Files.readString(legalSources.resolve("org/example/current/data/Value.java"));
			assertTrue(value.contains("$datagen$present$maybe"));
			assertTrue(value.contains("$datagen$parameter$in"));
			assertTrue(value.contains("$datagen$builder$original"));
			String serializer = Files.readString(
					legalSources.resolve("org/example/current/serializers/ValueSerializer.java"));
			assertTrue(serializer.contains("$datagen$value$in"));
		}
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
        assertTrue(generatedUser.contains("return String.valueOf(handle)"));
        assertFalse(generatedUser.contains("return String.valueOf(name)"));
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
		assertTrue(generated.contains("boolean $datagen$sinkPresent$"));
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
							.getMethod("read", int.class, baseType, SafeDataInput.class)
						.invoke(null, version, enumValue(baseType, "ImportedMessage"), BufDataInput.create(payload, LIMITS));
				assertEquals(true, full.getClass().getMethod("hasNested").invoke(full));
				Object expectedNestedValue = full.getClass().getMethod("nested").invoke(full);
				long expectedChatId = (long) expectedNestedValue.getClass().getMethod("chatId").invoke(expectedNestedValue);

				Object result = projection.getMethod("read", int.class, SafeDataInput.class)
						.invoke(null, version, BufDataInput.create(payload, LIMITS));
				assertEquals(full.getClass().getMethod("messageId").invoke(full), resultType.getMethod("messageId").invoke(result));
				assertEquals(full.getClass().getMethod("senderId").invoke(full), resultType.getMethod("senderId").invoke(result));
				Object projectedChatId = resultType.getMethod("chatEntityId").invoke(result);
				assertEquals(expectedChatId, projectedChatId.getClass().getMethod("get").invoke(projectedChatId));
				Object projectedText = resultType.getMethod("ownedText").invoke(result);
				assertEquals(expectedNestedValue.getClass().getMethod("ignoredText").invoke(expectedNestedValue),
						projectedText.getClass().getMethod("get").invoke(projectedText));
			}

			Object reader = projection.getMethod("newReader", DecodeLimits.class).invoke(null, LIMITS);
			Buf fixture = serializedProjectionFixture(true);
			int projectedPrefixLength = fixture.size() - Integer.BYTES
					- "not reached".getBytes(StandardCharsets.UTF_8).length;
			InvocationTargetException incompleteRoot = assertThrows(InvocationTargetException.class,
					() -> reader.getClass().getMethod("read", int.class, Buf.class, int.class, int.class)
							.invoke(reader, 1, fixture, 0, projectedPrefixLength));
			assertTrue(incompleteRoot.getCause() instanceof MalformedDataException);
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
					.invoke(null, 1, BufDataInput.create(fixture, LIMITS), sink);
			assertEquals(List.of(42L, true, 77L, true, "not selected", 99L), sinkArguments.get());
			reader.getClass().getMethod("readInto", int.class, Buf.class, int.class, int.class, sinkType)
					.invoke(reader, 1, fixture, 0, fixture.size(), sink);
			assertEquals(List.of(42L, true, 77L, true, "not selected", 99L), sinkArguments.get());
			assertTrue(sinkObservedUnboundCursor.get());

			InvocationTargetException truncated = assertThrows(InvocationTargetException.class,
					() -> reader.getClass().getMethod("read", int.class, Buf.class, int.class, int.class)
							.invoke(reader, 1, fixture, 0, 3));
			assertTrue(truncated.getCause() instanceof MalformedDataException);
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
	void customCodecSkipsUnselectedProjectionField(@TempDir Path out) throws Exception {
		generate("""
				currentVersion: v1
				customTypesData:
				  Opaque:
				    javaClass: java.lang.String
				    codec: it.cavallium.datagen.nativedata.StringSerializer
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
				""", out);
	}

	@Test
	void customCodecSkipsRemovedNormalReadField(@TempDir Path out) throws Exception {
		generate("""
				currentVersion: v2
				customTypesData:
				  Opaque:
				    javaClass: java.lang.String
				    codec: it.cavallium.datagen.nativedata.StringSerializer
				baseTypesData:
				  Message:
				    data:
				      opaque: Opaque
				      id: long
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - removeData:
				          transformClass: Message
				          from: opaque
				""", out);
	}

	@Test
	void fixedCustomCodecFieldsJoinCheckedSkipSpans(@TempDir Path out) throws Exception {
		generate("""
				currentVersion: v2
				customTypesData:
				  FixedInt:
				    javaClass: java.lang.Integer
				    codec: it.cavallium.datagen.plugin.TestFixedIntCodec
				    fixedSize: 4
				baseTypesData:
				  Root:
				    data:
				      first: FixedInt
				      second: FixedInt
				      retained: long
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - removeData:
				          transformClass: Root
				          from: first
				      - removeData:
				          transformClass: Root
				          from: second
				""", out);

		String plan = Files.readString(out.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(plan.contains("ProjectionReadSupport.skipBytes(input, 8)"));
		assertFalse(plan.contains("TestFixedIntCodec.read"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void nullableFixedCustomsReserveOnlyInsidePresentBranches(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				customTypesData:
				  FixedInt:
				    javaClass: java.lang.Integer
				    codec: it.cavallium.datagen.plugin.TestFixedIntCodec
				    fixedSize: 4
				baseTypesData:
				  Root:
				    data:
				      maybeFixed: -FixedInt
				      trailingOld: int
				projectionsData:
				  FixedSelection:
				    sourceType: Root
				    fields:
				      maybeFixed: maybeFixed
				      trailing: trailing
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - moveData: { transformClass: Root, from: trailingOld, to: trailing }
				""", sources);

		String exactSource = Files.readString(
				sources.resolve("org/example/current/serializers/RootSerializer.java"));
		String planSource = Files.readString(
				sources.resolve("org/example/current/readers/RootReadPlan.java"));
		String projectionSource = Files.readString(
				sources.resolve("org/example/projections/FixedSelectionProjection.java"));
		for (String generated : List.of(exactSource, planSource, projectionSource)) {
			int presence = generated.indexOf("readBoolean()");
			int reserve = generated.indexOf("reserve(4)");
			assertTrue(presence >= 0, generated);
			assertTrue(reserve > presence, generated);
			assertTrue(generated.indexOf("readReserved", reserve) > reserve, generated);
		}

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> version = loader.loadClass("org.example.current.Version");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Class<?> projection = loader.loadClass("org.example.projections.FixedSelectionProjection");
			DataCodec<Object> exact = (DataCodec<Object>) version.getField("RootSerializerInstance").get(null);
			Object rootType = enumValue(baseType, "Root");
			Object reusableProjection = projection.getMethod("newReader", DecodeLimits.class).invoke(null, LIMITS);

			for (boolean present : List.of(false, true)) {
				BufDataOutput output = BufDataOutput.create();
				output.writeBoolean(present);
				if (present) output.writeInt(41);
				output.writeInt(73);
				Buf payload = output.asList();

				Object exactValue = exact.newReader(LIMITS).read(payload);
				assertEquals(present, exactValue.getClass().getMethod("hasMaybeFixed").invoke(exactValue));
				assertEquals(73, exactValue.getClass().getMethod("trailing").invoke(exactValue));
				if (present) assertEquals(41, exactValue.getClass().getMethod("maybeFixed").invoke(exactValue));

				for (int inputVersion = 0; inputVersion <= 1; inputVersion++) {
					Object fused = currentVersion.getMethod("read", int.class, baseType, SafeDataInput.class)
							.invoke(null, inputVersion, rootType, BufDataInput.create(payload, LIMITS));
					assertEquals(present, fused.getClass().getMethod("hasMaybeFixed").invoke(fused));
					assertEquals(73, fused.getClass().getMethod("trailing").invoke(fused));
					Object projected = projection.getMethod("read", int.class, SafeDataInput.class)
							.invoke(null, inputVersion, BufDataInput.create(payload, LIMITS));
					assertEquals(73, projected.getClass().getMethod("trailing").invoke(projected));
					Object reused = reusableProjection.getClass()
							.getMethod("read", int.class, Buf.class, int.class, int.class)
							.invoke(reusableProjection, inputVersion, payload, 0, payload.size());
					assertEquals(73, reused.getClass().getMethod("trailing").invoke(reused));
				}
			}
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void generatedCustomNestedAndZeroWidthArraysValidateBeforeAllocation(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v1
				customTypesData:
				  FixedInt:
				    javaClass: java.lang.Integer
				    codec: it.cavallium.datagen.plugin.TestFixedIntCodec
				    fixedSize: 4
				  VariableText:
				    javaClass: java.lang.String
				    codec: it.cavallium.datagen.nativedata.StringSerializer
				baseTypesData:
				  Empty: { data: {} }
				  Nested:
				    data:
				      value: int
				  Holder:
				    data:
				      fixed: FixedInt[]
				      variable: VariableText[]
				      nested: Nested[]
				      empty: Empty[]
				versions:
				  v1:
				""", sources);

		String fixedSource = Files.readString(
				sources.resolve("org/example/current/serializers/ArrayFixedIntSerializer.java"));
		assertEquals(1, countOccurrences(fixedSource, "randomInput.reserve(bodyBytes)"), fixedSource);
		assertTrue(fixedSource.indexOf("randomInput.reserve(bodyBytes)")
				< fixedSource.indexOf("new Integer[sz]"), fixedSource);
		assertTrue(fixedSource.indexOf("claimArrayElements(sz)")
				< fixedSource.indexOf("new Integer[sz]"), fixedSource);
		assertTrue(fixedSource.contains("bodyStart + i * 4, 4"), fixedSource);
		assertFalse(fixedSource.contains("new BufDataCursor"), fixedSource);
		assertFalse(fixedSource.contains("binaryInputStream"), fixedSource);
		assertFalse(fixedSource.contains(".subList("), fixedSource);
		assertFalse(fixedSource.contains(" -> "), fixedSource);

		String variableSource = Files.readString(
				sources.resolve("org/example/current/serializers/ArrayVariableTextSerializer.java"));
		assertTrue(allocationFollows(variableSource, "prepareArrayAllocation(in, sz, 0)", "new String[sz]"),
				variableSource);
		String nestedSource = Files.readString(
				sources.resolve("org/example/current/serializers/ArrayNestedSerializer.java"));
		assertTrue(allocationFollows(nestedSource, "prepareArrayAllocation(in, sz, 4)", "new Nested[sz]"),
				nestedSource);
		String emptySource = Files.readString(
				sources.resolve("org/example/current/serializers/ArrayEmptySerializer.java"));
		assertTrue(allocationFollows(emptySource, "prepareArrayAllocation(in, sz, 0)", "new Empty[sz]"),
				emptySource);

		Path classes = temp.resolve("classes");
		try (var loader = compileGeneratedSources(sources, classes)) {
			assertFixedCustomArrayBytecode(classes.resolve(
					"org/example/current/serializers/ArrayFixedIntSerializer.class"));
			Class<?> version = loader.loadClass("org.example.current.Version");
			DataCodec<Object> fixed = (DataCodec<Object>) version.getField("ArrayFixedIntSerializerInstance").get(null);
			DataCodec<Object> variable = (DataCodec<Object>) version.getField("ArrayVariableTextSerializerInstance").get(null);
			DataCodec<Object> nested = (DataCodec<Object>) version.getField("ArrayNestedSerializerInstance").get(null);
			DataCodec<Object> empty = (DataCodec<Object>) version.getField("ArrayEmptySerializerInstance").get(null);

			BufDataOutput fixedOutput = BufDataOutput.create();
			fixedOutput.writeInt(2);
			fixedOutput.writeInt(11);
			fixedOutput.writeInt(22);
			BufDataOutput variableOutput = BufDataOutput.create();
			variableOutput.writeInt(2);
			variableOutput.writeMediumText("a", StandardCharsets.UTF_8);
			variableOutput.writeMediumText("bc", StandardCharsets.UTF_8);
			BufDataOutput nestedOutput = BufDataOutput.create();
			nestedOutput.writeInt(2);
			nestedOutput.writeInt(33);
			nestedOutput.writeInt(44);
			BufDataOutput emptyOutput = BufDataOutput.create();
			emptyOutput.writeInt(2);

			DecodeLimits exact = new DecodeLimits(2, 2, 2, 3, 2);
			for (var fixture : List.of(
					new Object[] {fixed, fixedOutput.asList()},
					new Object[] {variable, variableOutput.asList()},
					new Object[] {nested, nestedOutput.asList()},
					new Object[] {empty, emptyOutput.asList()})) {
				DataCodec<Object> codec = (DataCodec<Object>) fixture[0];
				Buf payload = (Buf) fixture[1];
				Object array = codec.newReader(exact).read(payload);
				assertEquals(2, java.lang.reflect.Array.getLength(array));
				assertThrows(it.cavallium.datagen.DecodeLimitExceededException.class,
						() -> codec.newReader(new DecodeLimits(1, 2, 2, 3, 2)).read(payload));
				assertThrows(it.cavallium.datagen.DecodeLimitExceededException.class,
						() -> codec.newReader(new DecodeLimits(2, 2, 1, 3, 2)).read(payload));
			}

			assertThrows(it.cavallium.datagen.DecodeLimitExceededException.class,
					() -> nested.newReader(new DecodeLimits(2, 2, 2, 3, 1)).read(nestedOutput.asList()));
			assertThrows(it.cavallium.datagen.DecodeLimitExceededException.class,
					() -> empty.newReader(new DecodeLimits(2, 2, 2, 3, 1)).read(emptyOutput.asList()));

			BufDataOutput fixedTruncated = BufDataOutput.create();
			fixedTruncated.writeInt(2);
			fixedTruncated.writeInt(11);
			BufDataInput fixedInput = BufDataInput.create(fixedTruncated.asList(), exact);
			assertThrows(MalformedDataException.class, () -> fixed.read(fixedInput));
			assertEquals(Integer.BYTES, fixedInput.position());

			BufDataOutput nestedTruncated = BufDataOutput.create();
			nestedTruncated.writeInt(2);
			nestedTruncated.writeInt(33);
			BufDataInput nestedInput = BufDataInput.create(nestedTruncated.asList(), exact);
			assertThrows(MalformedDataException.class, () -> nested.read(nestedInput));
			assertEquals(Integer.BYTES, nestedInput.position());

			BufDataOutput hostileZeroWidth = BufDataOutput.create();
			hostileZeroWidth.writeInt(Integer.MAX_VALUE);
			assertThrows(it.cavallium.datagen.DecodeLimitExceededException.class,
					() -> empty.newReader(exact).read(hostileZeroWidth.asList()));
			assertEquals(2, java.lang.reflect.Array.getLength(empty.newReader(exact).read(emptyOutput.asList())));
		}
	}

	@Test
	void validatesCustomCodecContractConfiguration(@TempDir Path out) {
		IllegalArgumentException missingCodec = assertThrows(IllegalArgumentException.class, () -> generate("""
				currentVersion: v1
				customTypesData:
				  Broken:
				    javaClass: java.lang.Integer
				baseTypesData:
				  Root:
				    data:
				      value: Broken
				versions:
				  v1:
				""", out.resolve("missing")));
		assertTrue(missingCodec.getMessage().contains("customTypesData.Broken.codec is required"));

		IllegalArgumentException negativeFixedSize = assertThrows(IllegalArgumentException.class, () -> generate("""
				currentVersion: v1
				customTypesData:
				  Broken:
				    javaClass: java.lang.Integer
				    codec: it.cavallium.datagen.plugin.TestFixedIntCodec
				    fixedSize: -1
				baseTypesData:
				  Root:
				    data:
				      value: Broken
				versions:
				  v1:
				""", out.resolve("negative")));
		assertTrue(negativeFixedSize.getMessage().contains("fixedSize must be non-negative"));
	}

	@Test
	void compilesInitializersUpgradersContextsAndCustomCodecs(@TempDir Path temp) throws Exception {
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
				    codec: it.cavallium.datagen.nativedata.StringSerializer
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
							.getMethod("read", int.class, baseType, SafeDataInput.class)
						.invoke(null, version, enumValue(baseType, "Message"), BufDataInput.create(payload, LIMITS));
				Object projected = projection.getMethod("read", int.class, SafeDataInput.class)
						.invoke(null, version, BufDataInput.create(payload, LIMITS));
				assertEquals(full.getClass().getMethod("messageId").invoke(full), resultType.getMethod("messageId").invoke(projected));
				assertEquals(full.getClass().getMethod("senderId").invoke(full), resultType.getMethod("senderId").invoke(projected));
				assertEquals(full.getClass().getMethod("derivedId").invoke(full), resultType.getMethod("derivedId").invoke(projected));
				Object projectedNullableCode = resultType.getMethod("nullableCode").invoke(projected);
				Object projectedNullableDerivedId = resultType.getMethod("nullableDerivedId").invoke(projected);
				assertEquals(full.getClass().getMethod("hasNullableCode").invoke(full),
						projectedNullableCode.getClass().getMethod("getNullable").invoke(projectedNullableCode) != null);
				assertEquals(full.getClass().getMethod("hasNullableDerivedId").invoke(full),
						projectedNullableDerivedId.getClass().getMethod("getNullable").invoke(projectedNullableDerivedId) != null);
				if ((boolean) full.getClass().getMethod("hasNullableCode").invoke(full)) {
					assertEquals(full.getClass().getMethod("nullableCode").invoke(full),
							projectedNullableCode.getClass().getMethod("get").invoke(projectedNullableCode));
				}
				if ((boolean) full.getClass().getMethod("hasNullableDerivedId").invoke(full)) {
					assertEquals(full.getClass().getMethod("nullableDerivedId").invoke(full),
							projectedNullableDerivedId.getClass().getMethod("get").invoke(projectedNullableDerivedId));
				}
			}
		}
	}

	@Test
	void fusedNormalReadersMatchHistoricalUpgradeAndRemainReusable(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v3
				customTypesData:
				  Opaque:
				    javaClass: java.lang.String
				    codec: it.cavallium.datagen.nativedata.StringSerializer
				superTypesData:
				  Choice:
				    - Leaf
				    - Other
				baseTypesData:
				  Leaf:
				    data:
				      value: int
				      text: String
				  Other:
				    data:
				      code: long
				  Root:
				    data:
				      opaque: Opaque
				      packed: -Int52
				      nested: Leaf
				      nullableNested: -Leaf
				      leaves: Leaf[]
				      choice: Choice
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - moveData:
				          transformClass: Leaf
				          from: value
				          to: renamed
				      - removeData:
				          transformClass: Root
				          from: opaque
				      - removeData:
				          transformClass: Root
				          from: packed
				      - newData:
				          transformClass: Root
				          to: initialized
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				  v3:
				    previousVersion: v2
				    transformations:
				      - upgradeData:
				          transformClass: Leaf
				          from: renamed
				          type: long
				          upgrader: it.cavallium.datagen.plugin.TestSimpleIntToLongUpgrader
				""", sources);

		String plan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertFalse(plan.contains("LeafUpgraderInstance.upgrade"));
		assertTrue(plan.contains("Leaf[] values = new Leaf[size]"));
		assertFalse(plan.contains("ImmutableWrappedArrayList"));
		assertTrue(plan.contains("codecReadState().session(\"Opaque\", Version.OpaqueSerializerInstance)"));
		assertTrue(plan.contains(".skip(input)"));
		assertTrue(plan.matches("(?s).*readPlan\\d+Heap\\(HeapBufDataCursor input, State state\\).*"));
		assertTrue(plan.matches("(?s).*readPlan\\d+MemorySegment\\(MemorySegmentBufDataCursor input, State state\\).*"));
		assertTrue(plan.matches("(?s).*readPlan\\d+Fallback\\(FallbackBufDataCursor input, State state\\).*"));
		String currentVersionSource = Files.readString(sources.resolve("org/example/current/CurrentVersion.java"));
		assertTrue(currentVersionSource.contains("BufDataCursor.bindSpecialized"));
		assertTrue(currentVersionSource.contains("protected final Root readHeapValue(HeapBufDataCursor input)"));
		assertTrue(currentVersionSource.contains("RootReadPlan.readV0(input, state)"));
		assertFalse(currentVersionSource.contains("java.util.function.Function"));
		assertFalse(currentVersionSource.contains("::read"));

		Path classes = temp.resolve("classes");
		try (var loader = compileGeneratedSources(sources, classes)) {
			assertBoundReaderKernelBytecode(classes, "Root", 0);
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");

			for (int version = 0; version <= 2; version++) {
				Buf payload = serializedFusedFixture(version);
				assertEquals(FUSED_FORMAT_1_GOLDENS.get(version), HexFormat.of().formatHex(payload.asArray()));
				assertHistoricalCodecRoundTrip(loader, baseType, rootType, version, 2, payload);
				Object expected = historicalReadAndUpgrade(loader, currentVersion, baseType, rootType,
						version, 2, payload);
				BufDataInput input = BufDataInput.create(payload, LIMITS);
				Object actual = currentVersion.getMethod("read", int.class, baseType, SafeDataInput.class)
						.invoke(null, version, rootType, input);
				assertEquals(expected, actual);
				assertEquals(0, input.available());
			}

			Object reader = currentVersion.getMethod("newReader", baseType, DecodeLimits.class).invoke(null, rootType, LIMITS);
			Object boundReader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			Buf payload = serializedFusedFixture(0);
			Object expected = historicalReadAndUpgrade(loader, currentVersion, baseType, rootType, 0, 2, payload);
			assertEquals(expected, invokeReader(reader, 0, payload, 0, payload.size()));
			assertReaderCursorUnbound(reader);
			for (int i = 0; i < 3; i++) {
				assertEquals(expected, invokeBoundReader(boundReader, payload, 0, payload.size()));
				assertReaderCursorUnbound(boundReader);
			}

			byte[] padded = new byte[payload.size() + 4];
			MemorySegment.copy(payload.asMemorySegment(), 0, MemorySegment.ofArray(padded), 2, payload.size());
			assertEquals(expected, invokeReader(reader, 0, Buf.wrap(padded), 2, payload.size()));
			assertReaderCursorUnbound(reader);
			assertEquals(expected, invokeBoundReader(boundReader, Buf.wrap(padded), 2, payload.size()));
			assertReaderCursorUnbound(boundReader);
			Buf heapSlice = Buf.wrap(padded).subList(2, 2 + payload.size());
			assertEquals(expected, invokeReader(reader, 0, heapSlice, 0, heapSlice.size()));
			assertReaderCursorUnbound(reader);
			assertEquals(expected, invokeBoundReader(boundReader, heapSlice, 0, heapSlice.size()));
			assertReaderCursorUnbound(boundReader);
			Buf fallbackSource = forcedFallbackBuf(Buf.wrap(padded));
			assertEquals(expected, invokeReader(reader, 0, fallbackSource, 2, payload.size()));
			assertReaderCursorUnbound(reader);
			assertEquals(expected, invokeBoundReader(boundReader, fallbackSource, 2, payload.size()));
			assertReaderCursorUnbound(boundReader);
			InvocationTargetException fallbackTruncated = assertThrows(InvocationTargetException.class,
					() -> invokeReader(reader, 0, fallbackSource, 2, payload.size() - 1));
			assertTrue(fallbackTruncated.getCause() instanceof MalformedDataException);
			assertReaderCursorUnbound(reader);
			InvocationTargetException fallbackTrailing = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(boundReader, fallbackSource, 2, payload.size() + 1));
			assertTrue(fallbackTrailing.getCause() instanceof IllegalArgumentException);
			assertReaderCursorUnbound(boundReader);
			assertEquals(expected, invokeReader(reader, 0, fallbackSource, 2, payload.size()));
			assertEquals(expected, invokeBoundReader(boundReader, fallbackSource, 2, payload.size()));

			try (var arena = Arena.ofConfined()) {
				MemorySegment nativeSegment = arena.allocate(payload.size() + 4, 8);
				MemorySegment.copy(payload.asMemorySegment(), 0, nativeSegment, 2, payload.size());
				Buf nativeSource = new MemorySegmentBuf(nativeSegment) {
					@Override
					public byte[] asArray() {
						throw new AssertionError("Normal reader copied native storage to heap");
					}

					@Override
					public it.cavallium.stream.SafeByteArrayInputStream binaryInputStream() {
						throw new AssertionError("Normal reader created a native payload stream");
					}
				};
				assertEquals(expected, invokeReader(reader, 0, nativeSource, 2, payload.size()));
				assertEquals(expected, invokeBoundReader(boundReader, nativeSource, 2, payload.size()));
				InvocationTargetException nativeTruncated = assertThrows(InvocationTargetException.class,
						() -> invokeReader(reader, 0, nativeSource, 2, payload.size() - 1));
				assertTrue(nativeTruncated.getCause() instanceof MalformedDataException);
				assertReaderCursorUnbound(reader);
				InvocationTargetException nativeTrailing = assertThrows(InvocationTargetException.class,
						() -> invokeBoundReader(boundReader, nativeSource, 2, payload.size() + 1));
				assertTrue(nativeTrailing.getCause() instanceof IllegalArgumentException);
				assertReaderCursorUnbound(boundReader);
				assertEquals(expected, invokeReader(reader, 0, nativeSource, 2, payload.size()));
				assertEquals(expected, invokeBoundReader(boundReader, nativeSource, 2, payload.size()));
				Buf nativeSlice = nativeSource.subList(2, 2 + payload.size());
				assertEquals(expected, invokeReader(reader, 0, nativeSlice, 0, nativeSlice.size()));
				assertEquals(expected, invokeBoundReader(boundReader, nativeSlice, 0, nativeSlice.size()));
			}
			assertReaderCursorUnbound(reader);
			assertReaderCursorUnbound(boundReader);

			InvocationTargetException truncated = assertThrows(InvocationTargetException.class,
					() -> invokeReader(reader, 0, payload, 0, payload.size() - 1));
			assertTrue(truncated.getCause() instanceof MalformedDataException);
			assertReaderCursorUnbound(reader);
			InvocationTargetException trailing = assertThrows(InvocationTargetException.class,
					() -> invokeReader(reader, 0, Buf.wrap(padded), 2, payload.size() + 1));
			assertTrue(trailing.getCause() instanceof IllegalArgumentException);
			assertReaderCursorUnbound(reader);
			InvocationTargetException unsupported = assertThrows(InvocationTargetException.class,
					() -> invokeReader(reader, 99, payload, 0, payload.size()));
			assertTrue(unsupported.getCause() instanceof IllegalArgumentException);
			assertReaderCursorUnbound(reader);
			assertEquals(expected, invokeReader(reader, 0, payload, 0, payload.size()));
			assertReaderCursorUnbound(reader);

			InvocationTargetException boundTruncated = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(boundReader, payload, 0, payload.size() - 1));
			assertTrue(boundTruncated.getCause() instanceof MalformedDataException);
			assertReaderCursorUnbound(boundReader);
			InvocationTargetException boundTrailing = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(boundReader, Buf.wrap(padded), 2, payload.size() + 1));
			assertTrue(boundTrailing.getCause() instanceof IllegalArgumentException);
			assertReaderCursorUnbound(boundReader);
			assertEquals(expected, invokeBoundReader(boundReader, payload, 0, payload.size()));
			assertReaderCursorUnbound(boundReader);
			InvocationTargetException unsupportedFactory = assertThrows(InvocationTargetException.class,
					() -> currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
							.invoke(null, 99, rootType, LIMITS));
			assertTrue(unsupportedFactory.getCause() instanceof IllegalArgumentException);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void nativeWireCorpusMatchesFormatOneGolden(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				baseTypesData:
				  NativeCorpus:
				    data:
				      flag: boolean
				      tiny: byte
				      small: short
				      letter: char
				      number: int
				      wide: long
				      ratio: float
				      precise: double
				      text: String
				      compact: Int52
				      maybeNumber: -int
				      maybeText: -String
				      flags: boolean[]
				      bytes: byte[]
				      shorts: short[]
				      chars: char[]
				      ints: int[]
				      longs: long[]
				      floats: float[]
				      doubles: double[]
				      int52s: Int52[]
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newData:
				          transformClass: NativeCorpus
				          to: currentTail
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				""", sources, true);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> oldType = loader.loadClass("org.example.v0.data.NativeCorpus");
			Class<?> int52Type = it.cavallium.datagen.nativedata.Int52.class;
			Object value = oldType.getMethod("unsafeOfOwned", boolean.class, byte.class, short.class, char.class,
					int.class, long.class, float.class, double.class, String.class, int52Type,
					boolean.class, int.class, String.class, boolean[].class, byte[].class, short[].class,
					char[].class, int[].class, long[].class, float[].class, double[].class,
					it.cavallium.datagen.nativedata.Int52[].class).invoke(null,
					true, (byte) 0x7f, (short) 0x1234, '\u03a9', 0x10203040, 0x0102030405060708L,
					1.5f, -2.25d, "wire", it.cavallium.datagen.nativedata.Int52.fromLong(0x010203040506L),
					true, -7, "maybe", new boolean[] {true, false}, new byte[] {1, -2},
					new short[] {3, -4}, new char[] {'A', '\u03a9'}, new int[] {5, -6},
					new long[] {7L, -8L}, new float[] {1.25f, -3.5f}, new double[] {2.5d, -4.75d},
					new it.cavallium.datagen.nativedata.Int52[] {
							it.cavallium.datagen.nativedata.Int52.ONE,
							it.cavallium.datagen.nativedata.Int52.fromLong(0x010203040506L)});
			Class<?> versionClass = loader.loadClass("org.example.v0.Version");
			Object codecObject = versionClass.getField("NativeCorpusSerializerInstance").get(null);
			DataCodec<Object> codec = (DataCodec<Object>) codecObject;
			BufDataOutput output = BufDataOutput.create();
			codec.serialize(output, value);
			Buf payload = output.asList();
			assertEquals(NATIVE_FORMAT_1_GOLDEN, HexFormat.of().formatHex(payload.asArray()));
			assertEquals(value, codec.read(BufDataInput.create(payload, LIMITS)));

			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object corpusType = enumValue(baseType, "NativeCorpus");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Object expected = historicalReadAndUpgrade(loader, currentVersion, baseType, corpusType, 0, 1, payload);
			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, corpusType, LIMITS);
			assertEquals(expected, invokeBoundReader(reader, payload, 0, payload.size()));
			assertReaderCursorUnbound(reader);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void nullableStringAndInt52LiteralGoldensCoverEveryReadPathAndConfiguration(@TempDir Path temp)
			throws Exception {
		String schema = """
				currentVersion: v2
				baseTypesData:
				  Root:
				    data:
				      removedText: -String
				      selectedText: -String
				      mappedText: -String
				      capturedText: -String
				      presenceText: -String
				      removedPacked: -Int52
				      selectedPacked: -Int52
				      mappedPacked: -Int52
				      capturedPacked: -Int52
				      presencePacked: -Int52
				      trailing: int
				projectionsData:
				  NullableSelection:
				    sourceType: Root
				    fields:
				      selectedText: selectedText
				      selectedPacked: selectedPacked
				      trailing: trailing
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - removeData: { transformClass: Root, from: removedText }
				      - upgradeData:
				          transformClass: Root
				          from: mappedText
				          type: -String
				          upgrader: org.example.IdentityObjectUpgrader
				          readTransform:
				            mapNullable:
				              source: { identity: { source: value } }
				              transform: { identity: { source: value } }
				      - upgradeData:
				          transformClass: Root
				          from: capturedText
				          type: long
				          upgrader: org.example.TextObjectUpgrader
				          readTransform: { custom: { className: org.example.TextWireUpgrader } }
				      - upgradeData:
				          transformClass: Root
				          from: presenceText
				          type: boolean
				          upgrader: org.example.PresenceObjectUpgrader
				          readTransform: { custom: { className: org.example.TextPresenceWireUpgrader } }
				      - removeData: { transformClass: Root, from: removedPacked }
				      - upgradeData:
				          transformClass: Root
				          from: mappedPacked
				          type: -Int52
				          upgrader: org.example.IdentityObjectUpgrader
				          readTransform:
				            mapNullable:
				              source: { identity: { source: value } }
				              transform: { identity: { source: value } }
				      - upgradeData:
				          transformClass: Root
				          from: capturedPacked
				          type: long
				          upgrader: org.example.PackedObjectUpgrader
				          readTransform: { custom: { className: org.example.PackedWireUpgrader } }
				      - upgradeData:
				          transformClass: Root
				          from: presencePacked
				          type: boolean
				          upgrader: org.example.PresenceObjectUpgrader
				          readTransform: { custom: { className: org.example.PackedPresenceWireUpgrader } }
				""";

		for (boolean binaryStrings : List.of(false, true)) {
			for (boolean oldSerializers : List.of(false, true)) {
				Path sources = temp.resolve("sources-b" + binaryStrings + "-o" + oldSerializers);
				generate(schema, sources, oldSerializers, binaryStrings);
				writeNullableCorpusUpgraders(sources);
				String readPlan = Files.readString(
						sources.resolve("org/example/current/readers/RootReadPlan.java"));
				assertTrue(readPlan.contains("readUnsignedShort()"), readPlan);
				assertTrue(readPlan.contains("Int52Serializer.readValue("), readPlan);
				assertTrue(readPlan.contains("skipPayload("), readPlan);
				assertTrue(readPlan.contains("wireView"), readPlan);
				String exactCodecSource = Files.readString(
						sources.resolve("org/example/v0/serializers/RootSerializer.java"));
				assertTrue(exactCodecSource.contains("private static final class Session"), exactCodecSource);
				assertTrue(exactCodecSource.contains("readValue(SafeDataInput in, CodecReadState codecState)"),
						exactCodecSource);

				try (var loader = compileGeneratedSources(sources,
						temp.resolve("classes-b" + binaryStrings + "-o" + oldSerializers))) {
					Class<?> baseType = loader.loadClass("org.example.BaseType");
					Object rootType = enumValue(baseType, "Root");
					Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
					Class<?> projection = loader.loadClass(
							"org.example.projections.NullableSelectionProjection");
					DataCodec<Object> exactCodec = (DataCodec<Object>) loader.loadClass("org.example.v0.Version")
							.getField("RootSerializerInstance").get(null);
					assertNotSame(exactCodec.newReadSession(), exactCodec.newReadSession());
					DataCodec.Reader<Object> exactReader = exactCodec.newReader(LIMITS);
					Object currentReader = currentVersion
							.getMethod("newReader", int.class, baseType, DecodeLimits.class)
							.invoke(null, 0, rootType, LIMITS);
					Object projectionReader = projection.getMethod("newReader", DecodeLimits.class)
							.invoke(null, LIMITS);

					for (boolean textPresent : List.of(false, true)) {
						for (boolean packedPresent : List.of(false, true)) {
							int goldenIndex = (textPresent ? 1 : 0) | (packedPresent ? 2 : 0);
							byte[] bytes = HexFormat.of().parseHex(NULLABLE_STRING_INT52_GOLDENS.get(goldenIndex));
							assertEquals(NULLABLE_STRING_INT52_GOLDENS.get(goldenIndex),
									HexFormat.of().formatHex(bytes));
							Buf payload = Buf.wrap(bytes);
							Object exact = exactCodec.read(BufDataInput.create(payload, LIMITS));
							assertNullableHistoricalRoot(exact, textPresent, packedPresent);
							if (oldSerializers) {
								BufDataOutput encoded = BufDataOutput.create();
								exactCodec.serialize(encoded, exact);
								assertArrayEquals(bytes, encoded.asList().asArray());
							}

							Object expectedCurrent = invokeBoundReader(currentReader, payload, 0, payload.size());
							assertNullableCurrentRoot(expectedCurrent, textPresent, packedPresent);
							Object expectedProjection = invokeReader(projectionReader, 0, payload, 0,
									payload.size());
							assertNullableProjection(expectedProjection, textPresent, packedPresent);

							byte[] padded = new byte[bytes.length + 6];
							System.arraycopy(bytes, 0, padded, 3, bytes.length);
							Buf slicedHeap = Buf.wrap(padded).subList(3, 3 + bytes.length);
							try (Arena arena = Arena.ofConfined()) {
								MemorySegment alignedSegment = arena.allocate(bytes.length);
								MemorySegment.copy(MemorySegment.ofArray(bytes), 0, alignedSegment, 0, bytes.length);
								MemorySegment unalignedSegment = arena.allocate(bytes.length + 1L);
								MemorySegment.copy(MemorySegment.ofArray(bytes), 0, unalignedSegment, 1, bytes.length);
								List<Buf> storages = List.of(payload, slicedHeap,
										new MemorySegmentBuf(alignedSegment),
										new MemorySegmentBuf(unalignedSegment).subList(1, bytes.length + 1),
										forcedFallbackBuf(payload));
								for (Buf storage : storages) {
									Object exactFromStorage = exactReader.read(storage);
									assertEquals(exact, exactFromStorage);
									Object current = invokeBoundReader(currentReader, storage, 0, storage.size());
									assertEquals(expectedCurrent, current);
									Object projected = invokeReader(projectionReader, 0, storage, 0,
											storage.size());
									assertEquals(expectedProjection, projected);
								}
							}

							Object exactStream = exactCodec.read(new SafeDataInputStream(
									new SafeByteArrayInputStream(bytes), LIMITS));
							assertEquals(exact, exactStream);
							Object currentStream = currentVersion
									.getMethod("read", int.class, baseType, SafeDataInput.class)
									.invoke(null, 0, rootType, new SafeDataInputStream(
											new SafeByteArrayInputStream(bytes), LIMITS));
							assertEquals(expectedCurrent, currentStream);
							Object projectionStream = projection.getMethod("read", int.class, SafeDataInput.class)
									.invoke(null, 0, new SafeDataInputStream(
											new SafeByteArrayInputStream(bytes), LIMITS));
							assertEquals(expectedProjection, projectionStream);

							for (int cut = 0; cut < bytes.length; cut++) {
								int truncatedLength = cut;
								assertThrows(MalformedDataException.class,
										() -> exactReader.read(payload, 0, truncatedLength));
								InvocationTargetException currentFailure = assertThrows(
										InvocationTargetException.class,
										() -> invokeBoundReader(currentReader, payload, 0, truncatedLength));
								assertTrue(currentFailure.getCause() instanceof MalformedDataException,
										() -> "cut " + truncatedLength + ": " + currentFailure.getCause());
								InvocationTargetException projectionFailure = assertThrows(
										InvocationTargetException.class,
										() -> invokeReader(projectionReader, 0, payload, 0,
												truncatedLength));
								assertTrue(projectionFailure.getCause() instanceof MalformedDataException,
										() -> "projection cut " + truncatedLength + ": "
												+ projectionFailure.getCause());
							}
							assertEquals(exact, exactReader.read(payload));
							assertEquals(expectedCurrent, invokeBoundReader(currentReader, payload, 0, payload.size()));
							assertEquals(expectedProjection,
									invokeReader(projectionReader, 0, payload, 0, payload.size()));
							assertReaderCursorUnbound(currentReader);

							byte[] withTrailing = java.util.Arrays.copyOf(bytes, bytes.length + 1);
							assertThrows(MalformedDataException.class,
									() -> exactReader.read(Buf.wrap(withTrailing)));
							InvocationTargetException trailingFailure = assertThrows(
									InvocationTargetException.class,
									() -> invokeBoundReader(currentReader, Buf.wrap(withTrailing), 0,
											withTrailing.length));
							assertTrue(trailingFailure.getCause() instanceof MalformedDataException);
							InvocationTargetException projectionTrailingFailure = assertThrows(
									InvocationTargetException.class,
									() -> invokeReader(projectionReader, 0, Buf.wrap(withTrailing), 0,
											withTrailing.length));
							assertTrue(projectionTrailingFailure.getCause() instanceof MalformedDataException);
							assertEquals(expectedCurrent, invokeBoundReader(currentReader, payload, 0, payload.size()));
							assertEquals(expectedProjection,
									invokeReader(projectionReader, 0, payload, 0, payload.size()));
						}
					}
				}
			}
		}
	}

	private static void assertNullableHistoricalRoot(Object root, boolean textPresent, boolean packedPresent)
			throws ReflectiveOperationException {
		for (var field : List.of(
				new String[] {"RemovedText", "rm"},
				new String[] {"SelectedText", "sel"},
				new String[] {"MappedText", "map"},
				new String[] {"CapturedText", "view"},
				new String[] {"PresenceText", "p"})) {
			assertEquals(textPresent, root.getClass().getMethod("has" + field[0]).invoke(root));
			if (textPresent) {
				String accessor = Character.toLowerCase(field[0].charAt(0)) + field[0].substring(1);
				assertEquals(field[1], root.getClass().getMethod(accessor).invoke(root).toString());
			}
		}
		long[] packedValues = {0x01020304050607L, 0x02030405060708L, 0x03040506070809L,
				0x0405060708090aL, 0x05060708090a0bL};
		String[] packedFields = {"RemovedPacked", "SelectedPacked", "MappedPacked", "CapturedPacked",
				"PresencePacked"};
		for (int index = 0; index < packedFields.length; index++) {
			String field = packedFields[index];
			assertEquals(packedPresent, root.getClass().getMethod("has" + field).invoke(root));
			if (packedPresent) {
				String accessor = Character.toLowerCase(field.charAt(0)) + field.substring(1);
				assertEquals(packedValues[index],
						((Number) root.getClass().getMethod(accessor).invoke(root)).longValue());
			}
		}
		assertEquals(0x11223344, root.getClass().getMethod("trailing").invoke(root));
	}

	private static void assertNullableCurrentRoot(Object root, boolean textPresent, boolean packedPresent)
			throws ReflectiveOperationException {
		assertEquals(textPresent, root.getClass().getMethod("hasSelectedText").invoke(root));
		assertEquals(textPresent, root.getClass().getMethod("hasMappedText").invoke(root));
		if (textPresent) {
			assertEquals("sel", root.getClass().getMethod("selectedText").invoke(root).toString());
			assertEquals("map", root.getClass().getMethod("mappedText").invoke(root).toString());
		}
		assertEquals(textPresent ? 4L : -1L, root.getClass().getMethod("capturedText").invoke(root));
		assertEquals(textPresent, root.getClass().getMethod("presenceText").invoke(root));
		assertEquals(packedPresent, root.getClass().getMethod("hasSelectedPacked").invoke(root));
		assertEquals(packedPresent, root.getClass().getMethod("hasMappedPacked").invoke(root));
		if (packedPresent) {
			assertEquals(0x02030405060708L,
					((Number) root.getClass().getMethod("selectedPacked").invoke(root)).longValue());
			assertEquals(0x03040506070809L,
					((Number) root.getClass().getMethod("mappedPacked").invoke(root)).longValue());
		}
		assertEquals(packedPresent ? 0x0405060708090aL : -1L,
				root.getClass().getMethod("capturedPacked").invoke(root));
		assertEquals(packedPresent, root.getClass().getMethod("presencePacked").invoke(root));
		assertEquals(0x11223344, root.getClass().getMethod("trailing").invoke(root));
		assertThrows(NoSuchMethodException.class, () -> root.getClass().getMethod("removedText"));
		assertThrows(NoSuchMethodException.class, () -> root.getClass().getMethod("removedPacked"));
	}

	private static void assertNullableProjection(Object projection, boolean textPresent, boolean packedPresent)
			throws ReflectiveOperationException {
		Object text = projection.getClass().getMethod("selectedText").invoke(projection);
		Object textValue = text.getClass().getMethod("getNullable").invoke(text);
		assertEquals(textPresent ? "sel" : null, textValue == null ? null : textValue.toString());
		Object packed = projection.getClass().getMethod("selectedPacked").invoke(projection);
		Object packedValue = packed.getClass().getMethod("getNullable").invoke(packed);
		assertEquals(packedPresent ? 0x02030405060708L : null,
				packedValue == null ? null : ((Number) packedValue).longValue());
		assertEquals(0x11223344, projection.getClass().getMethod("trailing").invoke(projection));
	}

	private static void writeNullableCorpusUpgraders(Path sources) throws Exception {
		writeGeneratedTestSource(sources, "IdentityObjectUpgrader", """
				@SuppressWarnings({"rawtypes", "unchecked"})
				public final class IdentityObjectUpgrader implements it.cavallium.datagen.DataUpgrader {
				  @Override public Object upgrade(it.cavallium.datagen.DataContext context, Object value) {
				    return value;
				  }
				}
				""");
		writeGeneratedTestSource(sources, "TextObjectUpgrader", """
				@SuppressWarnings({"rawtypes", "unchecked"})
				public final class TextObjectUpgrader implements it.cavallium.datagen.DataUpgrader {
				  @Override public Object upgrade(it.cavallium.datagen.DataContext context, Object value) {
				    Object raw = ((it.cavallium.datagen.TypedNullable<?>) value).getNullable();
				    return raw == null ? -1L : (long) raw.toString().length();
				  }
				}
				""");
		writeGeneratedTestSource(sources, "PackedObjectUpgrader", """
				@SuppressWarnings({"rawtypes", "unchecked"})
				public final class PackedObjectUpgrader implements it.cavallium.datagen.DataUpgrader {
				  @Override public Object upgrade(it.cavallium.datagen.DataContext context, Object value) {
				    Object raw = ((it.cavallium.datagen.TypedNullable<?>) value).getNullable();
				    return raw == null ? -1L : ((Number) raw).longValue();
				  }
				}
				""");
		writeGeneratedTestSource(sources, "PresenceObjectUpgrader", """
				@SuppressWarnings({"rawtypes", "unchecked"})
				public final class PresenceObjectUpgrader implements it.cavallium.datagen.DataUpgrader {
				  @Override public Object upgrade(it.cavallium.datagen.DataContext context, Object value) {
				    return ((it.cavallium.datagen.TypedNullable<?>) value).isPresent();
				  }
				}
				""");
		writeGeneratedTestSource(sources, "TextWireUpgrader", """
				public final class TextWireUpgrader
				    implements org.example.v0.upgraders.RootUpgrader.ReadUpgraderCapturedText {
				  @Override public long upgrade(
				      org.example.v0.upgraders.RootUpgrader.ReadInputCapturedText input) {
				    var view = input.valueView();
				    return view.isPresent() ? view.value().toString().length() : -1L;
				  }
				}
				""");
		writeGeneratedTestSource(sources, "PackedWireUpgrader", """
				public final class PackedWireUpgrader
				    implements org.example.v0.upgraders.RootUpgrader.ReadUpgraderCapturedPacked {
				  @Override public long upgrade(
				      org.example.v0.upgraders.RootUpgrader.ReadInputCapturedPacked input) {
				    var view = input.valueView();
				    return view.isPresent() ? view.value().longValue() : -1L;
				  }
				}
				""");
		writeGeneratedTestSource(sources, "TextPresenceWireUpgrader", """
				public final class TextPresenceWireUpgrader
				    implements org.example.v0.upgraders.RootUpgrader.ReadUpgraderPresenceText {
				  @Override public boolean upgrade(
				      org.example.v0.upgraders.RootUpgrader.ReadInputPresenceText input) {
				    return input.valueView().isPresent();
				  }
				}
				""");
		writeGeneratedTestSource(sources, "PackedPresenceWireUpgrader", """
				public final class PackedPresenceWireUpgrader
				    implements org.example.v0.upgraders.RootUpgrader.ReadUpgraderPresencePacked {
				  @Override public boolean upgrade(
				      org.example.v0.upgraders.RootUpgrader.ReadInputPresencePacked input) {
				    return input.valueView().isPresent();
				  }
				}
				""");
	}

	private static void writeGeneratedTestSource(Path sources, String className, String body) throws Exception {
		Path file = sources.resolve("org/example/" + className + ".java");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "package org.example;\n" + body, StandardCharsets.UTF_8);
	}

	@Test
	void generatedReadersRejectMalformedLengthsAndUnionIdsBeforeAllocation(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v1
				superTypesData:
				  Choice: [Leaf, Other]
				baseTypesData:
				  Leaf:
				    data:
				      value: int
				  Other:
				    data:
				      code: long
				  Root:
				    data:
				      values: int[]
				      choice: Choice
				versions:
				  v1:
				""", sources);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);

			BufDataOutput validOutput = BufDataOutput.create();
			validOutput.writeInt(1);
			validOutput.writeInt(17);
			validOutput.writeByte(0);
			validOutput.writeInt(23);
			Buf valid = validOutput.asList();
			Object expected = invokeBoundReader(reader, valid, 0, valid.size());

			for (int malformedLength : List.of(-1, Integer.MAX_VALUE, 100)) {
				BufDataOutput malformed = BufDataOutput.create();
				malformed.writeInt(malformedLength);
				InvocationTargetException failure = assertThrows(InvocationTargetException.class,
						() -> invokeBoundReader(reader, malformed.asList(), 0, malformed.asList().size()));
				assertTrue(failure.getCause() instanceof MalformedDataException,
						() -> "unexpected malformed-length failure: " + failure.getCause());
				assertReaderCursorUnbound(reader);
				assertReadFramesCleared(reader);
				assertEquals(expected, invokeBoundReader(reader, valid, 0, valid.size()));
			}

			BufDataOutput invalidUnion = BufDataOutput.create();
			invalidUnion.writeInt(0);
			invalidUnion.writeByte(2);
			InvocationTargetException discriminator = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, invalidUnion.asList(), 0, invalidUnion.asList().size()));
			assertTrue(discriminator.getCause() instanceof IllegalArgumentException);
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);
			assertEquals(expected, invokeBoundReader(reader, valid, 0, valid.size()));
		}
	}

	@Test
	void recursiveNullableRecordsReadDirectlyAndRemainReusable(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v1
				baseTypesData:
				  Node:
				    data:
				      value: int
				      next: -Node
				  Root:
				    data:
				      node: Node
				versions:
				  v1:
				""", sources);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(1);
			output.writeBoolean(true);
			output.writeInt(2);
			output.writeBoolean(false);
			Buf payload = output.asList();

			Object root = invokeBoundReader(reader, payload, 0, payload.size());
			Object first = root.getClass().getMethod("node").invoke(root);
			assertEquals(1, first.getClass().getMethod("value").invoke(first));
			assertEquals(true, first.getClass().getMethod("hasNext").invoke(first));
			Object second = first.getClass().getMethod("next").invoke(first);
			assertEquals(2, second.getClass().getMethod("value").invoke(second));
			assertEquals(false, second.getClass().getMethod("hasNext").invoke(second));
			assertReaderCursorUnbound(reader);
			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));

			InvocationTargetException truncated = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size() - 1));
			assertTrue(truncated.getCause() instanceof MalformedDataException);
			assertReaderCursorUnbound(reader);
			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));
		}
	}

	@Test
	void recursiveCustomWireViewsGrowReusableDepthWithoutHistoricalNodes(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				baseTypesData:
				  Node:
				    data:
				      value: int
				      next: -Node
				  Root:
				    data:
				      node: Node
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: node
				          type: long
				          upgrader: org.example.ThrowingNodeObjectUpgrader
				          readTransform:
				            custom:
				              className: org.example.RecursiveNodeViewUpgrader
				""", sources);

		Path support = sources.resolve("org/example/ThrowingNodeObjectUpgrader.java");
		Files.createDirectories(support.getParent());
		Files.writeString(support, """
				package org.example;
				@SuppressWarnings({"rawtypes", "unchecked"})
				public final class ThrowingNodeObjectUpgrader implements it.cavallium.datagen.DataUpgrader {
					@Override public Object upgrade(it.cavallium.datagen.DataContext context, Object value) {
						throw new AssertionError("historical recursive node materialized");
					}
				}
				""", StandardCharsets.UTF_8);
		Files.writeString(sources.resolve("org/example/RecursiveNodeViewUpgrader.java"), """
				package org.example;
				import org.example.v0.upgraders.RootUpgrader.ReadInputNode;
				import org.example.v0.upgraders.RootUpgrader.ReadUpgraderNode;
				public final class RecursiveNodeViewUpgrader implements ReadUpgraderNode {
					public static boolean fail;
					@Override public long upgrade(ReadInputNode input) {
						var node = input.valueView();
						long result = node.value();
						while (node.nextView().isPresent()) {
							node = node.nextView().valueView();
							result += node.value();
						}
						if (fail) throw new IllegalStateException("recursive view failure");
						return result;
					}
				}
				""", StandardCharsets.UTF_8);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(1);
			output.writeBoolean(true);
			output.writeInt(2);
			output.writeBoolean(true);
			output.writeInt(3);
			output.writeBoolean(false);
			Buf payload = output.asList();
			Object root = invokeBoundReader(reader, payload, 0, payload.size());
			assertEquals(6L, root.getClass().getMethod("node").invoke(root));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			loader.loadClass("org.example.RecursiveNodeViewUpgrader").getField("fail").setBoolean(null, true);
			InvocationTargetException failure = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertEquals("recursive view failure", failure.getCause().getMessage());
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);
			loader.loadClass("org.example.RecursiveNodeViewUpgrader").getField("fail").setBoolean(null, false);
			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));
			assertReadFramesCleared(reader);
		}
	}

	@Test
	void recursiveDelegatingViewsRemainReusableAfterOpaqueBoundary(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v3
				baseTypesData:
				  Node:
				    data:
				      value: int
				      next: -Node
				  Root:
				    data:
				      node: Node
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newData:
				          transformClass: Node
				          to: middle
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				      - upgradeData:
				          transformClass: Root
				          from: node
				          type: Node
				          upgrader: org.example.NodeBoundaryUpgrader
				  v3:
				    previousVersion: v2
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: node
				          type: long
				          upgrader: org.example.ThrowingCurrentNodeObjectUpgrader
				          readTransform:
				            custom:
				              className: org.example.RecursiveDelegatingNodeViewUpgrader
				""", sources);

		Path boundary = sources.resolve("org/example/NodeBoundaryUpgrader.java");
		Files.createDirectories(boundary.getParent());
		Files.writeString(boundary, """
				package org.example;
				import it.cavallium.datagen.DataContextNone;
				import it.cavallium.datagen.DataUpgrader;
				public final class NodeBoundaryUpgrader implements DataUpgrader<DataContextNone,
						org.example.v0.data.Node, org.example.current.data.Node> {
					@Override public org.example.current.data.Node upgrade(DataContextNone context,
							org.example.v0.data.Node value) {
						return convert(value);
					}
					private static org.example.current.data.Node convert(org.example.v0.data.Node value) {
						org.example.current.data.Node next = value.hasNext() ? convert(value.next()) : null;
						return org.example.current.data.Node.of(value.value(), next, 123L);
					}
				}
				""", StandardCharsets.UTF_8);
		Files.writeString(sources.resolve("org/example/ThrowingCurrentNodeObjectUpgrader.java"), """
				package org.example;
				import it.cavallium.datagen.DataContextNone;
				import it.cavallium.datagen.DataUpgrader;
				public final class ThrowingCurrentNodeObjectUpgrader implements DataUpgrader<DataContextNone,
						org.example.current.data.Node, Long> {
					@Override public Long upgrade(DataContextNone context, org.example.current.data.Node value) {
						throw new AssertionError("recursive intermediate object upgrader entered");
					}
				}
				""", StandardCharsets.UTF_8);
		Files.writeString(sources.resolve("org/example/RecursiveDelegatingNodeViewUpgrader.java"), """
				package org.example;
				import org.example.v1.upgraders.RootUpgrader.ReadInputNode;
				import org.example.v1.upgraders.RootUpgrader.ReadUpgraderNode;
				public final class RecursiveDelegatingNodeViewUpgrader implements ReadUpgraderNode {
					public static boolean fail;
					@Override public long upgrade(ReadInputNode input) {
						var node = input.valueView();
						long result = 0;
						for (;;) {
							result += node.value();
							var next = node.nextView();
							if (!next.isPresent()) break;
							node = next.valueView();
						}
						if (fail) throw new IllegalStateException("delegating recursive failure");
						return result;
					}
				}
				""", StandardCharsets.UTF_8);

		String plan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(plan.contains("recursiveBound"), plan);
		assertTrue(plan.contains("wireValue()"), plan);
		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(1);
			output.writeBoolean(true);
			output.writeInt(2);
			output.writeBoolean(true);
			output.writeInt(3);
			output.writeBoolean(false);
			Buf payload = output.asList();
			Object root = invokeBoundReader(reader, payload, 0, payload.size());
			assertEquals(6L, root.getClass().getMethod("node").invoke(root));
			assertReadFramesCleared(reader);

			loader.loadClass("org.example.RecursiveDelegatingNodeViewUpgrader").getField("fail")
					.setBoolean(null, true);
			InvocationTargetException failure = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertEquals("delegating recursive failure", failure.getCause().getMessage());
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);
			loader.loadClass("org.example.RecursiveDelegatingNodeViewUpgrader").getField("fail")
					.setBoolean(null, false);
			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));
			assertReadFramesCleared(reader);
		}
	}

	@Test
	void fusesStructuralTailAfterOpaqueObjectUpgrader(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v3
				baseTypesData:
				  Leaf:
				    data:
				      value: int
				  Root:
				    data:
				      nested: Leaf
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newData:
				          transformClass: Leaf
				          to: middle
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				      - upgradeData:
				          transformClass: Root
				          from: nested
				          type: Leaf
				          upgrader: org.example.LeafBoundaryUpgrader
				  v3:
				    previousVersion: v2
				    transformations:
				      - newData:
				          transformClass: Leaf
				          to: tail
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				""", sources);

		Path boundaryUpgrader = sources.resolve("org/example/LeafBoundaryUpgrader.java");
		Files.createDirectories(boundaryUpgrader.getParent());
		Files.writeString(boundaryUpgrader, """
				package org.example;

				import it.cavallium.datagen.DataContextNone;
				import it.cavallium.datagen.DataUpgrader;

				public final class LeafBoundaryUpgrader implements DataUpgrader<DataContextNone,
						org.example.v0.data.Leaf, org.example.v1.data.Leaf> {
					@Override
					public org.example.v1.data.Leaf upgrade(DataContextNone context,
							org.example.v0.data.Leaf oldData) {
						return org.example.v1.data.Leaf.of(oldData.value(), 222L);
					}
				}
				""", StandardCharsets.UTF_8);

		String plan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(plan.contains("upgradePlan"), plan);
		assertFalse(plan.contains("LeafUpgraderInstance.upgrade"), plan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(41);
			Buf payload = output.asList();

			Object expected = historicalReadAndUpgrade(loader, currentVersion, baseType, rootType,
					0, 2, payload);
			Object actual = currentVersion.getMethod("read", int.class, baseType, SafeDataInput.class)
					.invoke(null, 0, rootType, BufDataInput.create(payload, LIMITS));
			assertEquals(expected, actual);
			Object nested = actual.getClass().getMethod("nested").invoke(actual);
			assertEquals(222L, nested.getClass().getMethod("middle").invoke(nested));
			assertEquals(123L, nested.getClass().getMethod("tail").invoke(nested));
		}
	}

	@Test
	void customReadInitializerUsesLazyWireContextWithoutObjectInitializer(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				baseTypesData:
				  Root:
				    data:
				      seed: int
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newData:
				          transformClass: Root
				          to: derived
				          type: long
				          initializer: org.example.ObjectDerivedInitializer
				          readTransform:
				            custom:
				              className: org.example.WireDerivedInitializer
				          contextParameters: [seed]
				""", sources);

		Path objectInitializer = sources.resolve("org/example/ObjectDerivedInitializer.java");
		Files.createDirectories(objectInitializer.getParent());
		Files.writeString(objectInitializer, """
				package org.example;

				import it.cavallium.datagen.DataInitializer;
				import org.example.v0.upgraders.RootUpgrader.ContextDerived;

				public final class ObjectDerivedInitializer implements DataInitializer<ContextDerived, Long> {
					public static int calls;

					@Override
					public Long initialize(ContextDerived context) {
						calls++;
						return context.seed() + 100L;
					}
				}
				""", StandardCharsets.UTF_8);
		Path wireInitializer = sources.resolve("org/example/WireDerivedInitializer.java");
		Files.writeString(wireInitializer, """
				package org.example;

				import org.example.v0.upgraders.RootUpgrader.ReadInitializerDerived;
				import org.example.v0.upgraders.RootUpgrader.ReadInitializerInputDerived;

				public final class WireDerivedInitializer implements ReadInitializerDerived {
					public static int calls;
					public static boolean fail;

					@Override
					public long initialize(ReadInitializerInputDerived input) {
						calls++;
						if (fail) throw new IllegalStateException("initializer failure");
						return input.contextSeed() + 100L;
					}
				}
				""", StandardCharsets.UTF_8);

		String plan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(plan.contains("implements RootUpgrader.ReadInitializerInputDerived"), plan);
		assertTrue(plan.contains("READ_INITIALIZER_"), plan);
		assertTrue(plan.contains("final long prepared0 = applyReadInitializer"), plan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Class<?> wireClass = loader.loadClass("org.example.WireDerivedInitializer");
			Class<?> objectClass = loader.loadClass("org.example.ObjectDerivedInitializer");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(7);
			Buf payload = output.asList();
			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);

			Object actual = invokeBoundReader(reader, payload, 0, payload.size());
			assertEquals(107L, actual.getClass().getMethod("derived").invoke(actual));
			assertEquals(1, wireClass.getField("calls").getInt(null));
			assertEquals(0, objectClass.getField("calls").getInt(null));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			wireClass.getField("fail").setBoolean(null, true);
			InvocationTargetException failure = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertEquals("initializer failure", failure.getCause().getMessage());
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);
			wireClass.getField("fail").setBoolean(null, false);
			assertEquals(actual, invokeBoundReader(reader, payload, 0, payload.size()));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);
		}
	}

	@Test
	void optimizedReadUpgraderUsesBoundedWireValueAndLazyContext(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				baseTypesData:
				  Root:
				    data:
				      value: int
				      needed: int
				      unusedText: String
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: value
				          type: long
				          upgrader: org.example.OpaqueValueUpgrader
				          readTransform:
				            custom:
				              className: org.example.WireValueUpgrader
				          contextParameters: [unusedText, needed]
				      - removeData:
				          transformClass: Root
				          from: unusedText
				      - removeData:
				          transformClass: Root
				          from: needed
				""", sources);

		Path opaque = sources.resolve("org/example/OpaqueValueUpgrader.java");
		Files.createDirectories(opaque.getParent());
		Files.writeString(opaque, """
				package org.example;

				import it.cavallium.datagen.DataUpgrader;
				import org.example.v0.upgraders.RootUpgrader.ContextValue;

				public final class OpaqueValueUpgrader implements DataUpgrader<ContextValue, Integer, Long> {
					public static int calls;

					@Override
					public Long upgrade(ContextValue context, Integer value) {
						calls++;
						return (long) value + context.needed();
					}
				}
				""", StandardCharsets.UTF_8);
		Path wire = sources.resolve("org/example/WireValueUpgrader.java");
		Files.writeString(wire, """
				package org.example;

				import org.example.v0.upgraders.RootUpgrader.ReadInputValue;
				import org.example.v0.upgraders.RootUpgrader.ReadUpgraderValue;

				public final class WireValueUpgrader implements ReadUpgraderValue {
					public static int calls;
					public static int mode;

					@Override
					public long upgrade(ReadInputValue input) {
						calls++;
						if (!input.hasSerializedValue() || input.serializedVersion() != 0) {
							throw new AssertionError();
						}
						int value = switch (mode) {
							case 0 -> input.serializedValue().readInt();
							case 1 -> input.value();
							case 2 -> input.serializedValue().readUnsignedByte();
							case 3 -> {
								input.serializedValue().readUnsignedByte();
								throw new IllegalStateException("custom failure");
							}
							case 4 -> {
								var serialized = (it.cavallium.buffer.BufDataCursor) input.serializedValue();
								int decoded = serialized.readInt();
								serialized.close();
								yield decoded;
							}
							case 5 -> {
								var serialized = (it.cavallium.buffer.BufDataCursor) input.serializedValue();
								int decoded = serialized.readUnsignedByte();
								serialized.close();
								yield decoded;
							}
							case 6 -> {
								var serialized = (it.cavallium.buffer.BufDataCursor) input.serializedValue();
								serialized.readUnsignedByte();
								serialized.close();
								throw new IllegalStateException("closed custom failure");
							}
							default -> throw new AssertionError();
						};
						return (long) value + input.contextNeeded();
					}
				}
				""", StandardCharsets.UTF_8);

		String plan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(plan.contains("implements RootUpgrader.ReadInputValue"), plan);
		assertTrue(plan.contains("parent.bindRegion(valueCursor, sourceStart, sourceLength)"), plan);
		assertTrue(plan.contains("contextNeeded()"), plan);
		assertTrue(plan.contains("randomInput.reserve(8)"), plan);
		assertTrue(plan.contains("final int raw0Length = 4"), plan);
		assertTrue(plan.contains("fixedRun0 + 4"), plan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Class<?> wireClass = loader.loadClass("org.example.WireValueUpgrader");
			Class<?> opaqueClass = loader.loadClass("org.example.OpaqueValueUpgrader");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(40);
			output.writeInt(2);
			output.writeInt(6);
			output.write("unused".getBytes(StandardCharsets.UTF_8));
			Buf payload = output.asList();

			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			Object actual = invokeBoundReader(reader, payload, 0, payload.size());
			assertEquals(42L, actual.getClass().getMethod("value").invoke(actual));
			assertEquals(1, wireClass.getField("calls").getInt(null));
			assertEquals(0, opaqueClass.getField("calls").getInt(null));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			wireClass.getField("mode").setInt(null, 1);
			assertEquals(actual, invokeBoundReader(reader, payload, 0, payload.size()));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			wireClass.getField("mode").setInt(null, 2);
			InvocationTargetException partial = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertTrue(partial.getCause() instanceof IllegalArgumentException);
			assertTrue(partial.getCause().getMessage().contains("Trailing bytes in serialized upgrade value"));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			wireClass.getField("mode").setInt(null, 3);
			InvocationTargetException customFailure = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertTrue(customFailure.getCause() instanceof IllegalStateException);
			assertEquals("custom failure", customFailure.getCause().getMessage());
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			wireClass.getField("mode").setInt(null, 4);
			assertEquals(actual, invokeBoundReader(reader, payload, 0, payload.size()));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			wireClass.getField("mode").setInt(null, 5);
			InvocationTargetException closedPartial = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertTrue(closedPartial.getCause() instanceof IllegalArgumentException);
			assertTrue(closedPartial.getCause().getMessage().contains("Trailing bytes in serialized upgrade value"));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			wireClass.getField("mode").setInt(null, 6);
			InvocationTargetException closedCustomFailure = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertTrue(closedCustomFailure.getCause() instanceof IllegalStateException);
			assertEquals("closed custom failure", closedCustomFailure.getCause().getMessage());
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			wireClass.getField("mode").setInt(null, 0);
			assertEquals(actual, invokeBoundReader(reader, payload, 0, payload.size()));
			try (var arena = Arena.ofConfined()) {
				MemorySegment nativeSegment = arena.allocate(payload.size(), 8);
				MemorySegment.copy(payload.asMemorySegment(), 0, nativeSegment, 0, payload.size());
				Buf nativeSource = new MemorySegmentBuf(nativeSegment) {
					@Override
					public byte[] asArray() {
						throw new AssertionError("Optimized upgrader copied native storage to heap");
					}

					@Override
					public it.cavallium.stream.SafeByteArrayInputStream binaryInputStream() {
						throw new AssertionError("Optimized upgrader created a native payload stream");
					}
				};
				assertEquals(actual, invokeBoundReader(reader, nativeSource, 0, nativeSource.size()));
			}
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			SafeDataInput streamInput = new SafeDataInputStream(new SafeByteArrayInputStream(payload.asArray()), LIMITS);
			Object fallback = currentVersion.getMethod("read", int.class, baseType, SafeDataInput.class)
					.invoke(null, 0, rootType, streamInput);
			assertEquals(actual, fallback);
			assertEquals(9, wireClass.getField("calls").getInt(null));
			assertEquals(1, opaqueClass.getField("calls").getInt(null));
		}
	}

	@Test
	void optimizedReadUpgraderCanConstructFinalFieldAndBypassStructuralTail(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v3
				baseTypesData:
				  Leaf:
				    data:
				      value: int
				  Root:
				    data:
				      nested: Leaf
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newData:
				          transformClass: Leaf
				          to: middle
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				      - upgradeData:
				          transformClass: Root
				          from: nested
				          type: Leaf
				          upgrader: org.example.OpaqueLeafUpgrader
				          readTransform:
				            type: Leaf
				            custom:
				              className: org.example.CurrentLeafReadUpgrader
				  v3:
				    previousVersion: v2
				    transformations:
				      - newData:
				          transformClass: Leaf
				          to: tail
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				""", sources);

		Path opaque = sources.resolve("org/example/OpaqueLeafUpgrader.java");
		Files.createDirectories(opaque.getParent());
		Files.writeString(opaque, """
				package org.example;

				import it.cavallium.datagen.DataContextNone;
				import it.cavallium.datagen.DataUpgrader;

				public final class OpaqueLeafUpgrader implements DataUpgrader<DataContextNone,
						org.example.v0.data.Leaf, org.example.v1.data.Leaf> {
					public static int calls;

					@Override
					public org.example.v1.data.Leaf upgrade(DataContextNone context,
							org.example.v0.data.Leaf value) {
						calls++;
						return org.example.v1.data.Leaf.of(value.value(), 123L);
					}
				}
				""", StandardCharsets.UTF_8);
		Path direct = sources.resolve("org/example/CurrentLeafReadUpgrader.java");
		Files.writeString(direct, """
				package org.example;

				import org.example.v0.upgraders.RootUpgrader.ReadInputNested;
				import org.example.v0.upgraders.RootUpgrader.ReadUpgraderNested;

				public final class CurrentLeafReadUpgrader implements ReadUpgraderNested {
					public static int calls;
					public static int mode;

					@Override
					public org.example.current.data.Leaf upgrade(ReadInputNested input) {
						calls++;
						var current = input.currentValue();
						if (mode == 1) input.value();
						if (mode == 2) input.serializedValue();
						return current;
					}
				}
				""", StandardCharsets.UTF_8);

		String inputApi = Files.readString(sources.resolve("org/example/v0/upgraders/RootUpgrader.java"));
		assertTrue(inputApi.contains("current.data.Leaf upgrade(ReadInputNested input)"), inputApi);
		assertTrue(inputApi.contains("current.data.Leaf currentValue()"), inputApi);
		String plan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(plan.contains("CurrentLeafReadUpgrader"), plan);
		assertTrue(plan.contains("LeafReadPlan.readV0(input, state.sharedState0())"), plan);
		assertFalse(plan.contains("final int recordStart"), plan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Class<?> directClass = loader.loadClass("org.example.CurrentLeafReadUpgrader");
			Class<?> opaqueClass = loader.loadClass("org.example.OpaqueLeafUpgrader");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(41);
			Buf payload = output.asList();

			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			Object root = invokeBoundReader(reader, payload, 0, payload.size());
			Object leaf = root.getClass().getMethod("nested").invoke(root);
			assertEquals(41, leaf.getClass().getMethod("value").invoke(leaf));
			assertEquals(123L, leaf.getClass().getMethod("middle").invoke(leaf));
			assertEquals(123L, leaf.getClass().getMethod("tail").invoke(leaf));
			assertEquals(1, directClass.getField("calls").getInt(null));
			assertEquals(0, opaqueClass.getField("calls").getInt(null));
			assertSharedReadStatePresent(reader, "LeafReadPlan$State");
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			directClass.getField("mode").setInt(null, 1);
			InvocationTargetException logicalAfterCurrent = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertTrue(logicalAfterCurrent.getCause() instanceof IllegalStateException);
			assertReadFramesCleared(reader);

			directClass.getField("mode").setInt(null, 2);
			InvocationTargetException serializedAfterCurrent = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertTrue(serializedAfterCurrent.getCause() instanceof IllegalStateException);
			assertReadFramesCleared(reader);

			directClass.getField("mode").setInt(null, 0);
			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));
			assertEquals(4, directClass.getField("calls").getInt(null));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);
		}
	}

	@Test
	void optimizedReadUpgraderCanFuseContextDirectlyToItsCurrentShape(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v3
				baseTypesData:
				  Leaf:
				    data:
				      value: int
				  Root:
				    data:
				      derived: int
				      leaf: Leaf
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newData:
				          transformClass: Leaf
				          to: middle
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				      - upgradeData:
				          transformClass: Root
				          from: derived
				          type: long
				          upgrader: org.example.OpaqueContextUpgrader
				          readTransform:
				            custom:
				              className: org.example.CurrentContextReadUpgrader
				          contextParameters: [leaf]
				      - removeData:
				          transformClass: Root
				          from: leaf
				  v3:
				    previousVersion: v2
				    transformations:
				      - newData:
				          transformClass: Leaf
				          to: tail
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				""", sources);

		Path opaque = sources.resolve("org/example/OpaqueContextUpgrader.java");
		Files.createDirectories(opaque.getParent());
		Files.writeString(opaque, """
				package org.example;

				import it.cavallium.datagen.DataUpgrader;
				import org.example.v0.upgraders.RootUpgrader.ContextDerived;

				public final class OpaqueContextUpgrader
						implements DataUpgrader<ContextDerived, Integer, Long> {
					public static int calls;

					@Override
					public Long upgrade(ContextDerived context, Integer value) {
						calls++;
						return value.longValue() + context.leaf().value() + 246L;
					}
				}
				""", StandardCharsets.UTF_8);
		Path direct = sources.resolve("org/example/CurrentContextReadUpgrader.java");
		Files.writeString(direct, """
				package org.example;

				import org.example.v0.upgraders.RootUpgrader.ReadInputDerived;
				import org.example.v0.upgraders.RootUpgrader.ReadUpgraderDerived;

				public final class CurrentContextReadUpgrader implements ReadUpgraderDerived {
					public static int calls;

					@Override
					public long upgrade(ReadInputDerived input) {
						calls++;
						var leaf = input.currentContextLeaf();
						return input.value() + leaf.value() + leaf.middle() + leaf.tail();
					}
				}
				""", StandardCharsets.UTF_8);

		String inputApi = Files.readString(sources.resolve("org/example/v0/upgraders/RootUpgrader.java"));
		assertTrue(inputApi.contains("current.data.Leaf currentContextLeaf()"), inputApi);
		String rootPlan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(rootPlan.contains("LeafReadPlan.readV0(input, state.sharedState0())"), rootPlan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Class<?> directClass = loader.loadClass("org.example.CurrentContextReadUpgrader");
			Class<?> opaqueClass = loader.loadClass("org.example.OpaqueContextUpgrader");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(10);
			output.writeInt(7);
			Buf payload = output.asList();

			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			Object root = invokeBoundReader(reader, payload, 0, payload.size());
			assertEquals(263L, root.getClass().getMethod("derived").invoke(root));
			assertEquals(1, directClass.getField("calls").getInt(null));
			assertEquals(0, opaqueClass.getField("calls").getInt(null));
			assertSharedReadStatePresent(reader, "LeafReadPlan$State");
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));
			assertEquals(2, directClass.getField("calls").getInt(null));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);
		}
	}

	@Test
	void currentContextCanUseAnEarlierTerminalReadUpgradeWithoutHistoricalObjects(@TempDir Path temp)
			throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v4
				baseTypesData:
				  Leaf:
				    data:
				      value: int
				  Root:
				    data:
				      derived: int
				      leaf: Leaf
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newData:
				          transformClass: Leaf
				          to: middle
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				      - upgradeData:
				          transformClass: Root
				          from: leaf
				          type: Leaf
				          upgrader: org.example.OpaqueLeafStepUpgrader
				          readTransform:
				            type: Leaf
				            custom:
				              className: org.example.FinalLeafReadUpgrader
				  v3:
				    previousVersion: v2
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: derived
				          type: long
				          upgrader: org.example.OpaqueIndirectContextUpgrader
				          readTransform:
				            custom:
				              className: org.example.IndirectCurrentContextReadUpgrader
				          contextParameters: [leaf]
				      - removeData:
				          transformClass: Root
				          from: leaf
				  v4:
				    previousVersion: v3
				    transformations:
				      - newData:
				          transformClass: Leaf
				          to: tail
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				""", sources);

		Path opaqueLeaf = sources.resolve("org/example/OpaqueLeafStepUpgrader.java");
		Files.createDirectories(opaqueLeaf.getParent());
		Files.writeString(opaqueLeaf, """
				package org.example;

				import it.cavallium.datagen.DataContextNone;
				import it.cavallium.datagen.DataUpgrader;

				public final class OpaqueLeafStepUpgrader implements DataUpgrader<DataContextNone,
						org.example.v0.data.Leaf, org.example.v2.data.Leaf> {
					public static int calls;

					@Override
					public org.example.v2.data.Leaf upgrade(DataContextNone context,
							org.example.v0.data.Leaf value) {
						calls++;
						return org.example.v2.data.Leaf.of(value.value(), 123L);
					}
				}
				""", StandardCharsets.UTF_8);
		Path finalLeaf = sources.resolve("org/example/FinalLeafReadUpgrader.java");
		Files.writeString(finalLeaf, """
				package org.example;

				import org.example.v0.upgraders.RootUpgrader.ReadInputLeaf;
				import org.example.v0.upgraders.RootUpgrader.ReadUpgraderLeaf;

				public final class FinalLeafReadUpgrader implements ReadUpgraderLeaf {
					public static int calls;

					@Override
					public org.example.current.data.Leaf upgrade(ReadInputLeaf input) {
						calls++;
						return input.currentValue();
					}
				}
				""", StandardCharsets.UTF_8);
		Path opaqueContext = sources.resolve("org/example/OpaqueIndirectContextUpgrader.java");
		Files.writeString(opaqueContext, """
				package org.example;

				import it.cavallium.datagen.DataUpgrader;
				import org.example.v1.upgraders.RootUpgrader.ContextDerived;

				public final class OpaqueIndirectContextUpgrader
						implements DataUpgrader<ContextDerived, Integer, Long> {
					public static int calls;

					@Override
					public Long upgrade(ContextDerived context, Integer value) {
						calls++;
						return value.longValue() + context.leaf().value() + context.leaf().middle() + 123L;
					}
				}
				""", StandardCharsets.UTF_8);
		Path directContext = sources.resolve("org/example/IndirectCurrentContextReadUpgrader.java");
		Files.writeString(directContext, """
				package org.example;

				import org.example.v1.upgraders.RootUpgrader.ReadInputDerived;
				import org.example.v1.upgraders.RootUpgrader.ReadUpgraderDerived;

				public final class IndirectCurrentContextReadUpgrader implements ReadUpgraderDerived {
					public static int calls;

					@Override
					public long upgrade(ReadInputDerived input) {
						calls++;
						var leaf = input.currentContextLeaf();
						return input.value() + leaf.value() + leaf.middle() + leaf.tail();
					}
				}
				""", StandardCharsets.UTF_8);

		String rootPlan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(rootPlan.contains("FinalLeafReadUpgrader"), rootPlan);
		assertTrue(rootPlan.contains("IndirectCurrentContextReadUpgrader"), rootPlan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Class<?> finalLeafClass = loader.loadClass("org.example.FinalLeafReadUpgrader");
			Class<?> directContextClass = loader.loadClass("org.example.IndirectCurrentContextReadUpgrader");
			Class<?> opaqueLeafClass = loader.loadClass("org.example.OpaqueLeafStepUpgrader");
			Class<?> opaqueContextClass = loader.loadClass("org.example.OpaqueIndirectContextUpgrader");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(10);
			output.writeInt(7);
			Buf payload = output.asList();

			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			Object root = invokeBoundReader(reader, payload, 0, payload.size());
			assertEquals(263L, root.getClass().getMethod("derived").invoke(root));
			assertEquals(1, finalLeafClass.getField("calls").getInt(null));
			assertEquals(1, directContextClass.getField("calls").getInt(null));
			assertEquals(0, opaqueLeafClass.getField("calls").getInt(null));
			assertEquals(0, opaqueContextClass.getField("calls").getInt(null));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));
			assertEquals(2, finalLeafClass.getField("calls").getInt(null));
			assertEquals(2, directContextClass.getField("calls").getInt(null));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			SafeDataInput streamInput = new SafeDataInputStream(new SafeByteArrayInputStream(payload.asArray()), LIMITS);
			Object fallback = currentVersion.getMethod("read", int.class, baseType, SafeDataInput.class)
					.invoke(null, 0, rootType, streamInput);
			assertEquals(root, fallback);
			assertEquals(1, opaqueLeafClass.getField("calls").getInt(null));
			assertEquals(1, opaqueContextClass.getField("calls").getInt(null));
		}
	}

	@Test
	void delegatedNestedReadPlanSharesStateAndCleansUpgradeFrames(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				baseTypesData:
				  Leaf:
				    data:
				      value: int
				  Root:
				    data:
				      leaf: Leaf
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - upgradeData:
				          transformClass: Leaf
				          from: value
				          type: long
				          upgrader: org.example.OpaqueNestedValueUpgrader
				          readTransform:
				            custom:
				              className: org.example.NestedValueReadUpgrader
				""", sources);

		Path opaque = sources.resolve("org/example/OpaqueNestedValueUpgrader.java");
		Files.createDirectories(opaque.getParent());
		Files.writeString(opaque, """
				package org.example;

				import it.cavallium.datagen.DataContextNone;
				import it.cavallium.datagen.DataUpgrader;

				public final class OpaqueNestedValueUpgrader
						implements DataUpgrader<DataContextNone, Integer, Long> {
					public static int calls;

					@Override
					public Long upgrade(DataContextNone context, Integer value) {
						calls++;
						return value.longValue();
					}
				}
				""", StandardCharsets.UTF_8);
		Path direct = sources.resolve("org/example/NestedValueReadUpgrader.java");
		Files.writeString(direct, """
				package org.example;

				import org.example.v0.upgraders.LeafUpgrader.ReadInputValue;
				import org.example.v0.upgraders.LeafUpgrader.ReadUpgraderValue;

				public final class NestedValueReadUpgrader implements ReadUpgraderValue {
					public static int calls;
					public static int mode;

					@Override
					public long upgrade(ReadInputValue input) {
						calls++;
						return switch (mode) {
							case 0 -> input.serializedValue().readInt();
							case 1 -> input.serializedValue().readUnsignedByte();
							case 2 -> {
								input.serializedValue().readUnsignedByte();
								throw new IllegalStateException("nested custom failure");
							}
							default -> throw new AssertionError();
						};
					}
				}
				""", StandardCharsets.UTF_8);

		String rootPlan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(rootPlan.contains("LeafReadPlan.readV0(input, state.sharedState0())"), rootPlan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			Class<?> directClass = loader.loadClass("org.example.NestedValueReadUpgrader");
			Class<?> opaqueClass = loader.loadClass("org.example.OpaqueNestedValueUpgrader");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(41);
			Buf payload = output.asList();

			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			Object root = invokeBoundReader(reader, payload, 0, payload.size());
			Object leaf = root.getClass().getMethod("leaf").invoke(root);
			assertEquals(41L, leaf.getClass().getMethod("value").invoke(leaf));
			assertEquals(1, directClass.getField("calls").getInt(null));
			assertEquals(0, opaqueClass.getField("calls").getInt(null));
			assertSharedReadStatePresent(reader, "LeafReadPlan$State");
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			directClass.getField("mode").setInt(null, 1);
			InvocationTargetException partial = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertTrue(partial.getCause() instanceof IllegalArgumentException);
			assertTrue(partial.getCause().getMessage().contains("Trailing bytes in serialized upgrade value"));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			directClass.getField("mode").setInt(null, 2);
			InvocationTargetException customFailure = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertTrue(customFailure.getCause() instanceof IllegalStateException);
			assertEquals("nested custom failure", customFailure.getCause().getMessage());
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			directClass.getField("mode").setInt(null, 0);
			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));
			assertEquals(4, directClass.getField("calls").getInt(null));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);
		}
	}

	@Test
	void arrayNullableAndUnionWireViewsStayOnBoundedStorage(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				superTypesData:
				  Choice: [Leaf, Other]
				baseTypesData:
				  Leaf:
				    data:
				      value: int
				  Other:
				    data:
				      code: long
				  Root:
				    data:
				      values: int[]
				      maybe: -int
				      choice: Choice
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: values
				          type: long
				          upgrader: org.example.OpaqueUpgrader
				          readTransform:
				            custom:
				              className: org.example.ArrayViewUpgrader
				      - upgradeData:
				          transformClass: Root
				          from: maybe
				          type: long
				          upgrader: org.example.OpaqueUpgrader
				          readTransform:
				            custom:
				              className: org.example.NullableViewUpgrader
				      - upgradeData:
				          transformClass: Root
				          from: choice
				          type: long
				          upgrader: org.example.OpaqueUpgrader
				          readTransform:
				            custom:
				              className: org.example.UnionViewUpgrader
				""", sources);

		Path opaque = sources.resolve("org/example/OpaqueUpgrader.java");
		Files.createDirectories(opaque.getParent());
		Files.writeString(opaque, """
				package org.example;
				@SuppressWarnings({"rawtypes", "unchecked"})
				public final class OpaqueUpgrader implements it.cavallium.datagen.DataUpgrader {
					@Override public Object upgrade(it.cavallium.datagen.DataContext context, Object value) {
						throw new AssertionError("object path entered");
					}
				}
				""", StandardCharsets.UTF_8);
		Files.writeString(sources.resolve("org/example/ArrayViewUpgrader.java"), """
				package org.example;
				public final class ArrayViewUpgrader
						implements org.example.v0.upgraders.RootUpgrader.ReadUpgraderValues {
					public static boolean fail;
					@Override public long upgrade(org.example.v0.upgraders.RootUpgrader.ReadInputValues input) {
						var view = input.valueView();
						long sum = 0;
						for (int i = 0; i < view.size(); i++) sum += view.get(i);
						if (fail) throw new IllegalStateException("array view failure");
						return sum;
					}
				}
				""", StandardCharsets.UTF_8);
		Files.writeString(sources.resolve("org/example/NullableViewUpgrader.java"), """
				package org.example;
				public final class NullableViewUpgrader
						implements org.example.v0.upgraders.RootUpgrader.ReadUpgraderMaybe {
					@Override public long upgrade(org.example.v0.upgraders.RootUpgrader.ReadInputMaybe input) {
						var view = input.valueView();
						return view.isPresent() ? view.value() : -1L;
					}
				}
				""", StandardCharsets.UTF_8);
		Files.writeString(sources.resolve("org/example/UnionViewUpgrader.java"), """
				package org.example;
				import org.example.v0.upgraders.RootUpgrader.WireValueChoiceKind;
				public final class UnionViewUpgrader
						implements org.example.v0.upgraders.RootUpgrader.ReadUpgraderChoice {
					@Override public long upgrade(org.example.v0.upgraders.RootUpgrader.ReadInputChoice input) {
						return input.valueView().kind() == WireValueChoiceKind.Leaf ? 1L : 2L;
					}
				}
				""", StandardCharsets.UTF_8);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(3);
			output.writeInt(1);
			output.writeInt(2);
			output.writeInt(3);
			output.writeBoolean(true);
			output.writeInt(9);
			output.writeByte(0);
			output.writeInt(44);
			Buf payload = output.asList();
			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			Object root = invokeBoundReader(reader, payload, 0, payload.size());
			assertEquals(6L, root.getClass().getMethod("values").invoke(root));
			assertEquals(9L, root.getClass().getMethod("maybe").invoke(root));
			assertEquals(1L, root.getClass().getMethod("choice").invoke(root));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			loader.loadClass("org.example.ArrayViewUpgrader").getField("fail").setBoolean(null, true);
			InvocationTargetException failure = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertEquals("array view failure", failure.getCause().getMessage());
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);
			loader.loadClass("org.example.ArrayViewUpgrader").getField("fail").setBoolean(null, false);
			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));
		}
	}

	@Test
	void structuralArrayWireViewsReuseSequentialElementBindings(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				baseTypesData:
				  Leaf:
				    data:
				      wanted: int
				      ignored: String
				  Root:
				    data:
				      leaves: Leaf[]
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: leaves
				          type: long
				          upgrader: org.example.OpaqueArrayUpgrader
				          readTransform:
				            custom:
				              className: org.example.StructuralArrayViewUpgrader
				""", sources);

		Path opaque = sources.resolve("org/example/OpaqueArrayUpgrader.java");
		Files.createDirectories(opaque.getParent());
		Files.writeString(opaque, """
				package org.example;
				@SuppressWarnings({"rawtypes", "unchecked"})
				public final class OpaqueArrayUpgrader implements it.cavallium.datagen.DataUpgrader {
					@Override public Object upgrade(it.cavallium.datagen.DataContext context, Object value) {
						throw new AssertionError("historical array materialized");
					}
				}
				""", StandardCharsets.UTF_8);
		Files.writeString(sources.resolve("org/example/StructuralArrayViewUpgrader.java"), """
				package org.example;
				import org.example.v0.upgraders.RootUpgrader.ReadInputLeaves;
				import org.example.v0.upgraders.RootUpgrader.ReadUpgraderLeaves;
				import org.example.v0.upgraders.RootUpgrader.WireValueLeavesElement;
				public final class StructuralArrayViewUpgrader implements ReadUpgraderLeaves {
					public static boolean fail;
					@Override public long upgrade(ReadInputLeaves input) {
						var cursor = input.valueView().cursor();
						WireValueLeavesElement previous = null;
						long sum = 0;
						while (cursor.hasNext()) {
							WireValueLeavesElement element = cursor.nextView();
							if (previous != null && previous != element) throw new AssertionError("element view allocated");
							previous = element;
							sum += element.wanted();
						}
						if (fail) throw new IllegalStateException("structural array view failure");
						return sum;
					}
				}
				""", StandardCharsets.UTF_8);

		String api = Files.readString(sources.resolve("org/example/v0/upgraders/RootUpgrader.java"));
		assertTrue(api.contains("interface WireValueLeavesElement"), api);
		assertTrue(api.contains("interface WireValueLeavesCursor"), api);
		assertTrue(api.contains("WireValueLeavesElement nextView()"), api);
		String plan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(plan.contains("elementViewStart"), plan);
		assertTrue(plan.contains("wireParent().getIntAt(fieldWantedStart)"), plan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(2);
			output.writeInt(11);
			output.writeMediumText("ignored-a", StandardCharsets.UTF_8);
			output.writeInt(31);
			output.writeMediumText("ignored-b", StandardCharsets.UTF_8);
			Buf payload = output.asList();
			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			Object root = invokeBoundReader(reader, payload, 0, payload.size());
			assertEquals(42L, root.getClass().getMethod("leaves").invoke(root));
			assertReadFramesCleared(reader);

			loader.loadClass("org.example.StructuralArrayViewUpgrader").getField("fail").setBoolean(null, true);
			InvocationTargetException failure = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertEquals("structural array view failure", failure.getCause().getMessage());
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);
			loader.loadClass("org.example.StructuralArrayViewUpgrader").getField("fail").setBoolean(null, false);
			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));
			assertReadFramesCleared(reader);
		}
	}

	@Test
	void nestedRecordNullableAndUnionViewsStayStructural(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				superTypesData:
				  Choice: [Leaf, Other]
				baseTypesData:
				  Leaf:
				    data:
				      wanted: int
				      ignored: String
				  Other:
				    data:
				      code: long
				  Holder:
				    data:
				      leaf: Leaf
				      maybe: -Leaf
				      choice: Choice
				  Root:
				    data:
				      holder: Holder
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: holder
				          type: long
				          upgrader: org.example.OpaqueHolderUpgrader
				          readTransform:
				            custom:
				              className: org.example.NestedViewUpgrader
				""", sources);

		Path opaque = sources.resolve("org/example/OpaqueHolderUpgrader.java");
		Files.createDirectories(opaque.getParent());
		Files.writeString(opaque, """
				package org.example;
				@SuppressWarnings({"rawtypes", "unchecked"})
				public final class OpaqueHolderUpgrader implements it.cavallium.datagen.DataUpgrader {
					@Override public Object upgrade(it.cavallium.datagen.DataContext context, Object value) {
						throw new AssertionError("historical holder materialized");
					}
				}
				""", StandardCharsets.UTF_8);
		Files.writeString(sources.resolve("org/example/NestedViewUpgrader.java"), """
				package org.example;
				import org.example.v0.upgraders.RootUpgrader.ReadInputHolder;
				import org.example.v0.upgraders.RootUpgrader.ReadUpgraderHolder;
				import org.example.v0.upgraders.RootUpgrader.WireValueHolderFieldChoiceKind;
				public final class NestedViewUpgrader implements ReadUpgraderHolder {
					public static boolean fail;
					@Override public long upgrade(ReadInputHolder input) {
						var holder = input.valueView();
						long result = holder.leafView().wanted();
						var maybe = holder.maybeView();
						if (maybe.isPresent()) result += maybe.valueView().wanted();
						var choice = holder.choiceView();
						if (choice.kind() == WireValueHolderFieldChoiceKind.Leaf) {
							result += choice.asLeafView().wanted();
						}
						if (fail) throw new IllegalStateException("nested view failure");
						return result;
					}
				}
				""", StandardCharsets.UTF_8);

		String api = Files.readString(sources.resolve("org/example/v0/upgraders/RootUpgrader.java"));
		assertTrue(api.contains("WireValueHolderFieldLeaf leafView()"), api);
		assertTrue(api.contains("WireValueHolderFieldMaybePresentValue valueView()"), api);
		assertTrue(api.contains("WireValueHolderFieldChoiceVariantLeaf asLeafView()"), api);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(10);
			output.writeMediumText("ignored-root", StandardCharsets.UTF_8);
			output.writeBoolean(true);
			output.writeInt(20);
			output.writeMediumText("ignored-nullable", StandardCharsets.UTF_8);
			output.writeByte(0);
			output.writeInt(30);
			output.writeMediumText("ignored-union", StandardCharsets.UTF_8);
			Buf payload = output.asList();
			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			Object root = invokeBoundReader(reader, payload, 0, payload.size());
			assertEquals(60L, root.getClass().getMethod("holder").invoke(root));
			assertReadFramesCleared(reader);

			loader.loadClass("org.example.NestedViewUpgrader").getField("fail").setBoolean(null, true);
			InvocationTargetException failure = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertEquals("nested view failure", failure.getCause().getMessage());
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);
			loader.loadClass("org.example.NestedViewUpgrader").getField("fail").setBoolean(null, false);
			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));
			assertReadFramesCleared(reader);
		}
	}

	@Test
	void customRecordWireViewsReadCapturedRegionsWithoutHistoricalOwners(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				baseTypesData:
				  Leaf:
				    data:
				      wanted: int
				      ignored: String
				  Root:
				    data:
				      value: Leaf
				      derived: int
				      contextLeaf: Leaf
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: value
				          type: long
				          upgrader: org.example.OpaqueUpgrader
				          readTransform:
				            custom:
				              className: org.example.ValueViewUpgrader
				      - upgradeData:
				          transformClass: Root
				          from: derived
				          type: long
				          upgrader: org.example.OpaqueUpgrader
				          contextParameters: [contextLeaf]
				          readTransform:
				            custom:
				              className: org.example.ContextViewUpgrader
				      - removeData:
				          transformClass: Root
				          from: contextLeaf
				""", sources);

		Path opaque = sources.resolve("org/example/OpaqueUpgrader.java");
		Files.createDirectories(opaque.getParent());
		Files.writeString(opaque, """
				package org.example;

				@SuppressWarnings({"rawtypes", "unchecked"})
				public final class OpaqueUpgrader implements it.cavallium.datagen.DataUpgrader {
					public static int calls;
					@Override public Object upgrade(it.cavallium.datagen.DataContext context, Object value) {
						calls++;
						throw new AssertionError("historical object path entered");
					}
				}
				""", StandardCharsets.UTF_8);
		Files.writeString(sources.resolve("org/example/ValueViewUpgrader.java"), """
				package org.example;

				import org.example.v0.upgraders.RootUpgrader.ReadInputValue;
				import org.example.v0.upgraders.RootUpgrader.ReadUpgraderValue;

				public final class ValueViewUpgrader implements ReadUpgraderValue {
					public static int calls;
					public static boolean fail;
					@Override public long upgrade(ReadInputValue input) {
						calls++;
						if (!input.hasValueView()) throw new AssertionError();
						long value = input.valueView().wanted();
						if (fail) throw new IllegalStateException("view failure");
						return value;
					}
				}
				""", StandardCharsets.UTF_8);
		Files.writeString(sources.resolve("org/example/ContextViewUpgrader.java"), """
				package org.example;

				import org.example.v0.upgraders.RootUpgrader.ReadInputDerived;
				import org.example.v0.upgraders.RootUpgrader.ReadUpgraderDerived;

				public final class ContextViewUpgrader implements ReadUpgraderDerived {
					public static int calls;
					@Override public long upgrade(ReadInputDerived input) {
						calls++;
						if (!input.hasContextContextLeafView()) throw new AssertionError();
						return input.value() + input.contextContextLeafView().wanted();
					}
				}
				""", StandardCharsets.UTF_8);

		String api = Files.readString(sources.resolve("org/example/v0/upgraders/RootUpgrader.java"));
		assertTrue(api.contains("interface WireValueValue"), api);
		assertTrue(api.contains("WireValueValue valueView()"), api);
		assertTrue(api.contains("WireContextDerivedContextLeaf contextContextLeafView()"), api);
		String plan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(plan.contains("wireParent().getIntAt(fieldWantedStart)"), plan);
		assertTrue(plan.contains("wireView"), plan);
		assertEquals(2, countOccurrences(plan, "private static final class ReadFrame"), plan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(41);
			output.writeMediumText("not materialized", StandardCharsets.UTF_8);
			output.writeInt(1);
			output.writeInt(7);
			output.writeMediumText("also not materialized", StandardCharsets.UTF_8);
			Buf payload = output.asList();

			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			Object root = invokeBoundReader(reader, payload, 0, payload.size());
			assertEquals(41L, root.getClass().getMethod("value").invoke(root));
			assertEquals(8L, root.getClass().getMethod("derived").invoke(root));
			assertEquals(0, loader.loadClass("org.example.OpaqueUpgrader").getField("calls").getInt(null));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			loader.loadClass("org.example.ValueViewUpgrader").getField("fail").setBoolean(null, true);
			InvocationTargetException failure = assertThrows(InvocationTargetException.class,
					() -> invokeBoundReader(reader, payload, 0, payload.size()));
			assertEquals("view failure", failure.getCause().getMessage());
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);
			loader.loadClass("org.example.ValueViewUpgrader").getField("fail").setBoolean(null, false);
			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));
			assertReadFramesCleared(reader);
		}
	}

	@Test
	void declarativeReadTransformsCompileToDirectTypedCode(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				baseTypesData:
				  Root:
				    data:
				      invoked: int
				      context: int
				      constructed: int
				      identical: int
				      values: int[]
				      maybe: -int
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: invoked
				          type: long
				          upgrader: org.example.OpaqueUpgrader
				          contextParameters: [context]
				          readTransform:
				            invokeStatic:
				              method: org.example.Builtins.combine
				              arguments:
				                - identity:
				                    source: value
				                - identity:
				                    source: currentContext.context
				      - upgradeData:
				          transformClass: Root
				          from: constructed
				          type: long
				          upgrader: org.example.OpaqueUpgrader
				          readTransform:
				            construct:
				              className: java.lang.Long
				              arguments:
				                - identity:
				                    source: value
				      - upgradeData:
				          transformClass: Root
				          from: identical
				          type: int
				          upgrader: org.example.OpaqueUpgrader
				          readTransform:
				            identity:
				              source: currentValue
				      - upgradeData:
				          transformClass: Root
				          from: values
				          type: long[]
				          upgrader: org.example.OpaqueUpgrader
				          readTransform:
				            mapArray:
				              source:
				                identity:
				                  source: value
				              transform:
				                invokeStatic:
				                  method: org.example.Builtins.widen
				                  arguments:
				                    - identity:
				                        source: value
				      - upgradeData:
				          transformClass: Root
				          from: maybe
				          type: -long
				          upgrader: org.example.OpaqueUpgrader
				          readTransform:
				            mapNullable:
				              source:
				                identity:
				                  source: value
				              transform:
				                invokeStatic:
				                  method: org.example.Builtins.widen
				                  arguments:
				                    - identity:
				                        source: value
				      - newData:
				          transformClass: Root
				          to: constantValue
				          type: long
				          initializer: org.example.OpaqueInitializer
				          readTransform:
				            constant:
				              value: 77
				""", sources);

		Path builtins = sources.resolve("org/example/Builtins.java");
		Files.createDirectories(builtins.getParent());
		Files.writeString(builtins, """
				package org.example;

				public final class Builtins {
					private Builtins() {}
					public static long combine(int value, int context) { return value + (long) context; }
					public static long widen(int value) { return value * 2L; }
				}
				""", StandardCharsets.UTF_8);
		Files.writeString(sources.resolve("org/example/OpaqueUpgrader.java"), """
				package org.example;

				@SuppressWarnings({"rawtypes", "unchecked"})
				public final class OpaqueUpgrader implements it.cavallium.datagen.DataUpgrader {
					public static int calls;
					@Override
					public Object upgrade(it.cavallium.datagen.DataContext context, Object value) {
						calls++;
						throw new AssertionError("object upgrader entered");
					}
				}
				""", StandardCharsets.UTF_8);
		Files.writeString(sources.resolve("org/example/OpaqueInitializer.java"), """
				package org.example;

				@SuppressWarnings({"rawtypes", "unchecked"})
				public final class OpaqueInitializer implements it.cavallium.datagen.DataInitializer {
					public static int calls;
					@Override
					public Object initialize(it.cavallium.datagen.DataContext context) {
						calls++;
						throw new AssertionError("object initializer entered");
					}
				}
				""", StandardCharsets.UTF_8);

		String plan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(plan.contains("Builtins.combine("), plan);
		assertTrue(plan.contains("Builtins.widen("), plan);
		assertTrue(plan.contains("for (int wireMapArrayIndex"), plan);
		assertTrue(plan.contains("wireTransformCursor"), plan);
		assertEquals(1, countOccurrences(plan, "final int[] raw4 ="), plan);
		assertEquals(1, countOccurrences(plan, "final Nullableint raw5 ="), plan);
		assertTrue(plan.contains("new Long("), plan);
		assertFalse(plan.contains("java.util.function"), plan);
		assertFalse(plan.contains("OpaqueUpgrader.upgrade"), plan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(5);
			output.writeInt(7);
			output.writeInt(9);
			output.writeInt(11);
			output.writeInt(3);
			output.writeInt(1);
			output.writeInt(2);
			output.writeInt(3);
			output.writeBoolean(true);
			output.writeInt(4);
			Buf payload = output.asList();

			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			Object root = invokeBoundReader(reader, payload, 0, payload.size());
			assertEquals(12L, root.getClass().getMethod("invoked").invoke(root));
			assertEquals(9L, root.getClass().getMethod("constructed").invoke(root));
			assertEquals(11, root.getClass().getMethod("identical").invoke(root));
			assertArrayEquals(new long[] {2L, 4L, 6L},
					(long[]) root.getClass().getMethod("valuesCopy").invoke(root));
			assertEquals(true, root.getClass().getMethod("hasMaybe").invoke(root));
			assertEquals(8L, root.getClass().getMethod("maybe").invoke(root));
			assertEquals(77L, root.getClass().getMethod("constantValue").invoke(root));
			assertEquals(0, loader.loadClass("org.example.OpaqueUpgrader").getField("calls").getInt(null));
			assertEquals(0, loader.loadClass("org.example.OpaqueInitializer").getField("calls").getInt(null));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);

			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));
			assertReaderCursorUnbound(reader);

			SafeDataInput stream = new SafeDataInputStream(new SafeByteArrayInputStream(payload.asArray()), LIMITS);
			InvocationTargetException fallback = assertThrows(InvocationTargetException.class,
					() -> currentVersion.getMethod("read", int.class, baseType, SafeDataInput.class)
							.invoke(null, 0, rootType, stream));
			assertTrue(fallback.getCause() instanceof AssertionError);
			assertEquals("object upgrader entered", fallback.getCause().getMessage());
			assertEquals(1, loader.loadClass("org.example.OpaqueUpgrader").getField("calls").getInt(null));
		}
	}

	@Test
	void declarativeRecordArrayMapsReadOnlyReferencedWirePaths(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v2
				baseTypesData:
				  Nested:
				    data:
				      score: long
				  Leaf:
				    data:
				      wanted: int
				      ignored: String
				      nested: Nested
				  Root:
				    data:
				      leaves: Leaf[]
				      maybeLeaf: -Leaf
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: leaves
				          type: long[]
				          upgrader: org.example.OpaqueUpgrader
				          readTransform:
				            mapArray:
				              source:
				                identity:
				                  source: value
				              transform:
				                invokeStatic:
				                  method: org.example.Builtins.combine
				                  arguments:
				                    - identity:
				                        source: value.wanted
				                    - identity:
				                        source: value.nested.score
				      - upgradeData:
				          transformClass: Root
				          from: maybeLeaf
				          type: -long
				          upgrader: org.example.OpaqueUpgrader
				          readTransform:
				            mapNullable:
				              source:
				                identity:
				                  source: value
				              transform:
				                invokeStatic:
				                  method: org.example.Builtins.combine
				                  arguments:
				                    - identity:
				                        source: value.wanted
				                    - identity:
				                        source: value.nested.score
				""", sources);

		Path builtins = sources.resolve("org/example/Builtins.java");
		Files.createDirectories(builtins.getParent());
		Files.writeString(builtins, """
				package org.example;
				public final class Builtins {
					private Builtins() {}
					public static long combine(int wanted, long score) { return wanted + score; }
				}
				""", StandardCharsets.UTF_8);
		Files.writeString(sources.resolve("org/example/OpaqueUpgrader.java"), """
				package org.example;
				@SuppressWarnings({"rawtypes", "unchecked"})
				public final class OpaqueUpgrader implements it.cavallium.datagen.DataUpgrader {
					@Override public Object upgrade(it.cavallium.datagen.DataContext context, Object value) {
						throw new AssertionError("historical record array materialized");
					}
				}
				""", StandardCharsets.UTF_8);

		String plan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(plan.contains("final int wireField"), plan);
		assertTrue(plan.contains("final long wireField"), plan);
		assertFalse(plan.contains("final Leaf wireMapArrayElement"), plan);
		assertFalse(plan.contains("final Leaf wireMapNullableElement"), plan);
		assertTrue(plan.matches("(?s).*skip\\d+\\(wireMapArrayCursor0\\).*"), plan);
		assertTrue(plan.contains("final HeapBufDataCursor wireMapArrayCursor"), plan);
		assertTrue(plan.contains("final MemorySegmentBufDataCursor wireMapArrayCursor"), plan);
		assertTrue(plan.contains("final FallbackBufDataCursor wireMapArrayCursor"), plan);
		assertTrue(plan.contains("final boolean prepared1Present"), plan);
		assertEquals(1, countOccurrences(plan, "final Nullablelong prepared1"), plan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(2);
			output.writeInt(3);
			output.writeMediumText("discard-a", StandardCharsets.UTF_8);
			output.writeLong(100L);
			output.writeInt(7);
			output.writeMediumText("discard-b", StandardCharsets.UTF_8);
			output.writeLong(200L);
			output.writeBoolean(true);
			output.writeInt(9);
			output.writeMediumText("discard-nullable", StandardCharsets.UTF_8);
			output.writeLong(300L);
			Buf payload = output.asList();
			Object reader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, 0, rootType, LIMITS);
			Object root = invokeBoundReader(reader, payload, 0, payload.size());
			assertArrayEquals(new long[] {103L, 207L},
					(long[]) root.getClass().getMethod("leavesCopy").invoke(root));
			assertEquals(true, root.getClass().getMethod("hasMaybeLeaf").invoke(root));
			assertEquals(309L, root.getClass().getMethod("maybeLeaf").invoke(root));
			assertReaderCursorUnbound(reader);
			assertReadFramesCleared(reader);
			assertEquals(root, invokeBoundReader(reader, payload, 0, payload.size()));
		}
	}

	@Test
	void rejectsMalformedReadTransformTreesAndRemovedFlatKeys(@TempDir Path out) {
		var ambiguous = new ReadTransformConfiguration();
		ambiguous.identity = new ReadTransformConfiguration.Identity();
		ambiguous.identity.source = "value";
		ambiguous.constant = new ReadTransformConfiguration.Constant();
		IllegalArgumentException ambiguousFailure = assertThrows(IllegalArgumentException.class,
				() -> ambiguous.validate("upgradeData.readTransform"));
		assertTrue(ambiguousFailure.getMessage().contains("exactly one operation"),
				ambiguousFailure::getMessage);

		var incompleteMap = new ReadTransformConfiguration();
		incompleteMap.mapArray = new ReadTransformConfiguration.MapArray();
		incompleteMap.mapArray.source = new ReadTransformConfiguration();
		incompleteMap.mapArray.source.identity = new ReadTransformConfiguration.Identity();
		incompleteMap.mapArray.source.identity.source = "value";
		IllegalArgumentException mapFailure = assertThrows(IllegalArgumentException.class,
				() -> incompleteMap.validate("upgradeData.readTransform"));
		assertTrue(mapFailure.getMessage().contains("mapArray.transform is required"), mapFailure::getMessage);

		var ambiguousConstruct = new ReadTransformConfiguration();
		ambiguousConstruct.construct = new ReadTransformConfiguration.Construct();
		ambiguousConstruct.construct.type = "long";
		ambiguousConstruct.construct.className = "java.lang.Long";
		IllegalArgumentException constructFailure = assertThrows(IllegalArgumentException.class,
				() -> ambiguousConstruct.validate("upgradeData.readTransform"));
		assertTrue(constructFailure.getMessage().contains("must not declare both"), constructFailure::getMessage);

		IllegalArgumentException nestedTypeFailure = assertThrows(IllegalArgumentException.class, () -> generate("""
				currentVersion: v2
				baseTypesData:
				  Root:
				    data:
				      value: int
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: value
				          type: long
				          upgrader: it.cavallium.datagen.plugin.TestSimpleIntToLongUpgrader
				          readTransform:
				            invokeStatic:
				              method: org.example.Transforms.convert
				              arguments:
				                - type: MissingType
				                  constant:
				                    value: 1
				""", out.resolve("unknown-type")));
		assertTrue(nestedTypeFailure.getMessage().contains("unknown schema type: MissingType"),
				nestedTypeFailure::getMessage);

		assertThrows(RuntimeException.class, () -> generate("""
				currentVersion: v2
				baseTypesData:
				  Root:
				    data:
				      value: int
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: value
				          type: long
				          upgrader: it.cavallium.datagen.plugin.TestSimpleIntToLongUpgrader
				          readUpgrader: org.example.RemovedFlatApi
				""", out.resolve("flat-key")));
	}

	@Test
	void rejectsMismatchedFinalReadUpgraderType(@TempDir Path out) {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> generate("""
				currentVersion: v2
				baseTypesData:
				  Root:
				    data:
				      value: int
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - upgradeData:
				          transformClass: Root
				          from: value
				          type: long
				          upgrader: it.cavallium.datagen.plugin.TestSimpleIntToLongUpgrader
				          readTransform:
				            type: String
				            custom:
				              className: org.example.InvalidReadUpgrader
				""", out));
		assertTrue(failure.getMessage().contains("readTransform.type String does not match current field"),
				failure::getMessage);
	}

	@Test
	void fusesNestedNullableArrayAndUnionTailsAfterOpaqueObjectUpgrader(@TempDir Path temp) throws Exception {
		Path sources = temp.resolve("sources");
		generate("""
				currentVersion: v3
				superTypesData:
				  Choice:
				    - Leaf
				    - Other
				baseTypesData:
				  Leaf:
				    data:
				      value: int
				  Other:
				    data:
				      code: long
				  Bundle:
				    data:
				      single: Leaf
				      maybe: -Leaf
				      leaves: Leaf[]
				      choice: Choice
				  Root:
				    data:
				      bundle: Bundle
				versions:
				  v1:
				  v2:
				    previousVersion: v1
				    transformations:
				      - newData:
				          transformClass: Leaf
				          to: middle
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				      - upgradeData:
				          transformClass: Root
				          from: bundle
				          type: Bundle
				          upgrader: org.example.BundleBoundaryUpgrader
				  v3:
				    previousVersion: v2
				    transformations:
				      - newData:
				          transformClass: Leaf
				          to: tail
				          type: long
				          initializer: it.cavallium.datagen.plugin.TestSimpleLongInitializer
				""", sources);

		Path boundaryUpgrader = sources.resolve("org/example/BundleBoundaryUpgrader.java");
		Files.createDirectories(boundaryUpgrader.getParent());
		Files.writeString(boundaryUpgrader, """
				package org.example;

				import it.cavallium.datagen.DataContextNone;
				import it.cavallium.datagen.DataUpgrader;
				public final class BundleBoundaryUpgrader implements DataUpgrader<DataContextNone,
						org.example.v0.data.Bundle, org.example.v1.data.Bundle> {
					@Override
					public org.example.v1.data.Bundle upgrade(DataContextNone context,
							org.example.v0.data.Bundle oldData) {
						org.example.v0.data.Leaf oldMaybe = oldData.hasMaybe() ? oldData.maybe() : null;
						org.example.v1.data.Leaf maybe = oldMaybe == null ? null : upgradeLeaf(oldMaybe);
						org.example.v0.data.Leaf[] oldLeaves = oldData.leavesUnsafeArray();
						org.example.v1.data.Leaf[] leaves = new org.example.v1.data.Leaf[oldLeaves.length];
						for (int i = 0; i < oldLeaves.length; i++) {
							leaves[i] = upgradeLeaf(oldLeaves[i]);
						}
						return org.example.v1.data.Bundle.unsafeOfOwned(
								upgradeLeaf(oldData.single()),
								maybe,
								leaves,
								upgradeLeaf((org.example.v0.data.Leaf) oldData.choice()));
					}

					private static org.example.v1.data.Leaf upgradeLeaf(org.example.v0.data.Leaf oldData) {
						return org.example.v1.data.Leaf.of(oldData.value(), 222L);
					}
				}
				""", StandardCharsets.UTF_8);

		String plan = Files.readString(sources.resolve("org/example/current/readers/RootReadPlan.java"));
		assertTrue(plan.contains("source.getNullable()"), plan);
		assertTrue(plan.contains("source.length"), plan);
		assertTrue(plan.contains("source.getMetaId$Choice()"), plan);
		assertFalse(plan.contains("LeafUpgraderInstance.upgrade"), plan);
		assertFalse(plan.contains("BundleUpgraderInstance.upgrade"), plan);

		try (var loader = compileGeneratedSources(sources, temp.resolve("classes"))) {
			Class<?> baseType = loader.loadClass("org.example.BaseType");
			Object rootType = enumValue(baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
			BufDataOutput output = BufDataOutput.create();
			output.writeInt(1);
			output.writeBoolean(true);
			output.writeInt(2);
			output.writeInt(2);
			output.writeInt(3);
			output.writeInt(4);
			output.writeByte(0);
			output.writeInt(5);
			Buf payload = output.asList();

			Object expected = historicalReadAndUpgrade(loader, currentVersion, baseType, rootType,
					0, 2, payload);
			Object actual = currentVersion.getMethod("read", int.class, baseType, SafeDataInput.class)
					.invoke(null, 0, rootType, BufDataInput.create(payload, LIMITS));
			assertEquals(expected, actual);
		}
	}

	private static Object historicalReadAndUpgrade(URLClassLoader loader,
			Class<?> currentVersion,
			Class<?> baseType,
			Object type,
			int version,
			int currentVersionNumber,
			Buf payload) throws Exception {
		String versionPackage = version == currentVersionNumber ? "current" : "v" + version;
		Class<?> versionClass = loader.loadClass("org.example." + versionPackage + ".Version");
		Object versionInstance = versionClass.getField("INSTANCE").get(null);
		Object codec = versionClass.getMethod("getCodec", baseType).invoke(versionInstance, type);
		Object historical = codec.getClass().getMethod("read", SafeDataInput.class)
				.invoke(codec, BufDataInput.create(payload, LIMITS));
		return currentVersion.getMethod("upgradeDataToLatestVersion", int.class, Object.class)
				.invoke(null, version, historical);
	}

	private static void assertHistoricalCodecRoundTrip(URLClassLoader loader,
			Class<?> baseType,
			Object type,
			int version,
			int currentVersionNumber,
			Buf payload) throws Exception {
		String versionPackage = version == currentVersionNumber ? "current" : "v" + version;
		Class<?> versionClass = loader.loadClass("org.example." + versionPackage + ".Version");
		Object versionInstance = versionClass.getField("INSTANCE").get(null);
		Object codec = versionClass.getMethod("getCodec", baseType).invoke(versionInstance, type);
		Object value = codec.getClass().getMethod("read", SafeDataInput.class)
				.invoke(codec, BufDataInput.create(payload, LIMITS));
		if (version != currentVersionNumber) return;
		BufDataOutput output = BufDataOutput.create(payload.size());
		var serialize = java.util.Arrays.stream(codec.getClass().getMethods())
				.filter(method -> method.getName().equals("serialize") && method.getParameterCount() == 2)
				.findFirst()
				.orElseThrow();
		serialize.invoke(codec, output, value);
		assertArrayEquals(payload.asArray(), output.asList().asArray());
	}

	private static Object invokeReader(Object reader, int version, Buf source, int offset, int length)
			throws ReflectiveOperationException {
		var method = reader.getClass().getMethod("read", int.class, Buf.class, int.class, int.class);
		method.setAccessible(true);
		return method.invoke(reader, version, source, offset, length);
	}

	private static Object invokeBoundReader(Object reader, Buf source, int offset, int length)
			throws ReflectiveOperationException {
		var method = reader.getClass().getMethod("read", Buf.class, int.class, int.class);
		method.setAccessible(true);
		return method.invoke(reader, source, offset, length);
	}

	private static int countOccurrences(String source, String fragment) {
		int count = 0;
		for (int offset = 0; (offset = source.indexOf(fragment, offset)) >= 0; offset += fragment.length()) {
			count++;
		}
		return count;
	}

	private static boolean allocationFollows(String source, String validation, String allocation) {
		int validationOffset = source.indexOf(validation);
		int allocationOffset = source.indexOf(allocation);
		return validationOffset >= 0 && allocationOffset > validationOffset;
	}

	private static void assertFixedCustomArrayBytecode(Path classFile) throws Exception {
		var model = ClassFile.of().parse(classFile);
		var instructions = model.methods().stream()
				.filter(method -> method.methodName().equalsString("readValue"))
				.flatMap(method -> method.code().stream())
				.flatMap(code -> code.elementStream())
				.filter(Instruction.class::isInstance)
				.map(Instruction.class::cast)
				.toList();
		assertFalse(instructions.stream().anyMatch(instruction -> instruction.opcode() == Opcode.INVOKEDYNAMIC));
		assertEquals(1L, instructions.stream()
				.filter(InvokeInstruction.class::isInstance)
				.map(InvokeInstruction.class::cast)
				.filter(invoke -> invoke.owner().asInternalName()
						.equals("it/cavallium/buffer/RandomAccessDataInput"))
				.filter(invoke -> invoke.name().equalsString("reserve"))
				.count());
		assertFalse(instructions.stream()
				.filter(InvokeInstruction.class::isInstance)
				.map(InvokeInstruction.class::cast)
				.anyMatch(invoke -> invoke.owner().asInternalName().contains("BufDataCursor")),
				"fixed custom arrays must not construct or bind per-element cursors in generated bytecode");
	}

	private static Buf serializedFusedFixture(int version) {
		BufDataOutput output = BufDataOutput.create();
		if (version == 0) {
			output.writeMediumText("removed opaque", StandardCharsets.UTF_8);
			output.writeInt52(123456L);
		}
		writeLeaf(output, version, 7, "nested");
		output.writeBoolean(true);
		writeLeaf(output, version, 8, "nullable");
		output.writeInt(2);
		writeLeaf(output, version, 9, "array-a");
		writeLeaf(output, version, 10, "array-b");
		output.writeByte(0);
		writeLeaf(output, version, 11, "choice");
		if (version >= 1) output.writeLong(123L);
		return output.asList();
	}

	private static void writeLeaf(BufDataOutput output, int version, int value, String text) {
		if (version < 2) output.writeInt(value);
		else output.writeLong(value + 1000L);
		output.writeMediumText(text, StandardCharsets.UTF_8);
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

	private static Buf forcedFallbackBuf(Buf delegate) {
		return (Buf) Proxy.newProxyInstance(SourcesGeneratorTest.class.getClassLoader(), new Class<?>[] {Buf.class},
				(proxy, method, arguments) -> {
					return switch (method.getName()) {
						case "getBackingByteArrayStrict", "asMemorySegmentStrict" -> null;
						case "getBackingByteArray", "asArray", "binaryInputStream" ->
								throw new AssertionError("Fallback reader converted the complete payload");
						default -> {
							try {
								yield method.invoke(delegate, arguments);
							} catch (InvocationTargetException failure) {
								throw failure.getCause();
							}
						}
					};
				});
	}

	private static void assertBoundReaderKernelBytecode(Path classes, String typeName, int version) throws Exception {
		Path classFile = classes.resolve("org/example/current/CurrentVersion$" + typeName + "V" + version
				+ "Reader.class");
		var model = ClassFile.of().parse(classFile);
		var allInstructions = model.methods().stream()
				.flatMap(method -> method.code().stream())
				.flatMap(code -> code.elementStream())
				.filter(Instruction.class::isInstance)
				.map(Instruction.class::cast)
				.toList();
		assertFalse(allInstructions.stream().anyMatch(instruction -> instruction.opcode() == Opcode.INVOKEDYNAMIC),
				"bound reader must not contain invokedynamic dispatch");
		assertFalse(allInstructions.stream().anyMatch(instruction -> instruction.opcode() == Opcode.TABLESWITCH
				|| instruction.opcode() == Opcode.LOOKUPSWITCH), "bound reader must not dispatch on version");

		String planOwner = "org/example/current/readers/" + typeName + "ReadPlan";
		var kernels = new LinkedHashMap<String, String>();
		kernels.put("readHeapValue", "it/cavallium/buffer/HeapBufDataCursor");
		kernels.put("readMemorySegmentValue", "it/cavallium/buffer/MemorySegmentBufDataCursor");
		kernels.put("readFallbackValue", "it/cavallium/buffer/FallbackBufDataCursor");
		for (var kernel : kernels.entrySet()) {
			var instructions = model.methods().stream()
					.filter(method -> method.methodName().equalsString(kernel.getKey()))
					.flatMap(method -> method.code().stream())
					.flatMap(code -> code.elementStream())
					.filter(Instruction.class::isInstance)
					.map(Instruction.class::cast)
					.toList();
			assertTrue(instructions.stream().filter(InvokeInstruction.class::isInstance)
					.map(InvokeInstruction.class::cast)
					.anyMatch(invoke -> invoke.opcode() == Opcode.INVOKESTATIC
							&& invoke.owner().asInternalName().equals(planOwner)
							&& invoke.name().equalsString("readV" + version)
							&& invoke.type().stringValue().contains("L" + kernel.getValue() + ";")),
					kernel.getKey() + " must directly invoke its concrete read-plan overload");
		}
	}

	private static void assertReaderCursorUnbound(Object reader) throws ReflectiveOperationException {
		var cursors = new ArrayList<BufDataCursor>();
		for (Class<?> owner = reader.getClass(); owner != null; owner = owner.getSuperclass()) {
			for (java.lang.reflect.Field field : owner.getDeclaredFields()) {
				if (!BufDataCursor.class.isAssignableFrom(field.getType())) continue;
				field.setAccessible(true);
				cursors.add((BufDataCursor) field.get(reader));
			}
		}
		assertFalse(cursors.isEmpty(), "reader exposes no reusable storage cursor");
		for (BufDataCursor cursor : cursors) {
			Class<?> core = cursor.getClass();
			while (core != null && !core.getSimpleName().equals("BufDataInputCore")) {
				core = core.getSuperclass();
			}
			if (core == null) throw new NoSuchFieldException("BufDataInputCore");
			var boundField = core.getDeclaredField("bound");
			boundField.setAccessible(true);
			assertEquals(false, boundField.get(cursor));
			for (String name : List.of("source", "heap", "segment", "fallback", "activeStorage")) {
				var field = core.getDeclaredField(name);
				field.setAccessible(true);
				assertEquals(null, field.get(cursor));
			}
		}
	}

	private static void assertReadFramesCleared(Object reader) throws ReflectiveOperationException {
		Object state = findFieldValue(reader, "state");
		if (state == null) state = findFieldValue(reader, "reader");
		if (state == null) throw new NoSuchFieldException("state or reader");
		var states = new ArrayList<Object>();
		states.add(state);
		Object shared = findFieldValue(state, "sharedStates");
		if (shared instanceof Object[] sharedStates) {
			for (Object sharedState : sharedStates) {
				if (sharedState != null && states.stream().noneMatch(existing -> existing == sharedState)) {
					states.add(sharedState);
				}
			}
		}
		for (Object inspectedState : states) {
			for (Class<?> stateClass = inspectedState.getClass(); stateClass != null;
					stateClass = stateClass.getSuperclass()) {
				for (java.lang.reflect.Field field : stateClass.getDeclaredFields()) {
					if (BufDataCursor.class.isAssignableFrom(field.getType())) {
						field.setAccessible(true);
						assertCursorStorageCleared((BufDataCursor) field.get(inspectedState), field.getName());
					}
					if (!field.getName().startsWith("readFrame")) continue;
					field.setAccessible(true);
					Object frame = field.get(inspectedState);
					while (frame != null) {
						for (java.lang.reflect.Field frameField : frame.getClass().getDeclaredFields()) {
							if (!frameField.getName().startsWith("wireView")) continue;
							frameField.setAccessible(true);
							assertWireViewCleared(frameField.get(frame));
						}
						for (String reference : List.of("parent", "state")) {
							try {
								java.lang.reflect.Field referenceField = frame.getClass().getDeclaredField(reference);
								referenceField.setAccessible(true);
								assertEquals(null, referenceField.get(frame), reference);
							} catch (NoSuchFieldException ignored) {
								// Lean frames omit storage they cannot use.
							}
						}
						for (String cursorName : List.of("valueCursor", "contextCursor")) {
							try {
								java.lang.reflect.Field cursorField = frame.getClass().getDeclaredField(cursorName);
								cursorField.setAccessible(true);
								Object cursor = cursorField.get(frame);
								assertFalse((boolean) cursor.getClass().getMethod("isBound").invoke(cursor));
								Class<?> core = cursor.getClass().getSuperclass();
								for (String storage : List.of("source", "heap", "segment", "fallback")) {
									java.lang.reflect.Field storageField = core.getDeclaredField(storage);
									storageField.setAccessible(true);
									assertEquals(null, storageField.get(cursor), cursorName + "." + storage);
								}
							} catch (NoSuchFieldException ignored) {
								// Lean frames omit storage they cannot use.
							}
						}
						java.lang.reflect.Field next = frame.getClass().getDeclaredField("next");
						next.setAccessible(true);
						frame = next.get(frame);
					}
				}
			}
		}
	}

	private static void assertWireViewCleared(Object view) throws ReflectiveOperationException {
		assertWireViewCleared(view, new IdentityHashMap<>());
	}

	private static void assertWireViewCleared(Object view, IdentityHashMap<Object, Boolean> inspected)
			throws ReflectiveOperationException {
		if (view == null || inspected.put(view, Boolean.TRUE) != null) return;
		for (java.lang.reflect.Field field : view.getClass().getDeclaredFields()) {
			field.setAccessible(true);
			Object value = field.get(view);
			if (BufDataCursor.class.isAssignableFrom(field.getType())) {
				assertCursorStorageCleared((BufDataCursor) value, field.getName());
			} else if (field.getType() == boolean.class
					&& (field.getName().equals("scanned") || field.getName().endsWith("Set"))) {
				assertEquals(false, value, field.getName());
			} else if (field.getName().endsWith("Value") && !field.getType().isPrimitive()) {
				assertEquals(null, value, field.getName());
			} else if ((field.getName().equals("parent") || field.getName().equals("state"))
					&& !java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
				assertEquals(null, value, field.getName());
			} else if (field.getName().startsWith("wireView")) {
				assertWireViewCleared(value, inspected);
			}
		}
	}

	private static void assertCursorStorageCleared(BufDataCursor cursor, String coordinate)
			throws ReflectiveOperationException {
		assertFalse(cursor.isBound(), coordinate);
		Class<?> core = cursor.getClass();
		while (core != null && !core.getSimpleName().equals("BufDataInputCore")) core = core.getSuperclass();
		if (core == null) throw new NoSuchFieldException("BufDataInputCore");
		for (String storage : List.of("source", "heap", "segment", "fallback", "activeStorage")) {
			java.lang.reflect.Field storageField = core.getDeclaredField(storage);
			storageField.setAccessible(true);
			assertEquals(null, storageField.get(cursor), coordinate + "." + storage);
		}
	}

	private static void assertSharedReadStatePresent(Object reader, String stateClassSuffix)
			throws ReflectiveOperationException {
		Object state = findFieldValue(reader, "state");
		if (state == null) state = findFieldValue(reader, "reader");
		if (state == null) throw new NoSuchFieldException("state or reader");
		Object shared = findFieldValue(state, "sharedStates");
		assertTrue(shared instanceof Object[]);
		assertTrue(java.util.Arrays.stream((Object[]) shared)
				.filter(sharedState -> sharedState != null)
				.anyMatch(sharedState -> sharedState.getClass().getName().endsWith(stateClassSuffix)));
	}

	private static Object findFieldValue(Object instance, String name) throws IllegalAccessException {
		for (Class<?> owner = instance.getClass(); owner != null; owner = owner.getSuperclass()) {
			try {
				java.lang.reflect.Field field = owner.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(instance);
			} catch (NoSuchFieldException ignored) {
				// Keep looking through generated implementation bases.
			}
		}
		return null;
	}

	private static LinkedHashMap<String, String> sourceSnapshot(Path root) throws Exception {
		var snapshot = new LinkedHashMap<String, String>();
		try (var paths = Files.walk(root)) {
			for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java"))
					.sorted().toList()) {
				snapshot.put(root.relativize(path).toString(), Files.readString(path));
			}
		}
		return snapshot;
	}

	private static String unionSchema(List<String> alternatives) {
		return """
				currentVersion: v1
				baseTypesData:
				  A: { data: {} }
				  B: { data: {} }
				superTypesData:
				  Choice: [%s]
				versions:
				  v1:
				""".formatted(String.join(", ", alternatives));
	}

	private static String wideUnionSchema(int alternatives) {
		StringBuilder yaml = new StringBuilder("currentVersion: v1\nbaseTypesData:\n");
		for (int index = 0; index < alternatives; index++) {
			yaml.append("  T").append(index).append(": { data: {} }\n");
		}
		yaml.append("superTypesData:\n  Choice:\n");
		for (int index = 0; index < alternatives; index++) {
			yaml.append("    - T").append(index).append('\n');
		}
		yaml.append("versions:\n  v1:\n");
		return yaml.toString();
	}

	private static String boundReaderFactorySchema(int typeCount, int versionCount) {
		StringBuilder yaml = new StringBuilder("currentVersion: v" + versionCount + "\nbaseTypesData:\n");
		for (int index = 0; index < typeCount; index++) {
			yaml.append("  T").append(index).append(": { data: {} }\n");
		}
		yaml.append("versions:\n");
		for (int version = 1; version <= versionCount; version++) {
			yaml.append("  v").append(version).append(":\n");
			if (version > 1) {
				yaml.append("    previousVersion: v").append(version - 1).append('\n');
			}
		}
		return yaml.toString();
	}

	private static String recordSchema(String fields) {
		return "currentVersion: v1\nbaseTypesData:\n  Value:\n    data:\n"
				+ fields + "versions:\n  v1:\n";
	}

    private static void generate(String yaml, Path out) throws Exception {
        generate(yaml, out, false);
    }

	private static void generate(String yaml, Path out, boolean generateOldSerializers) throws Exception {
		generate(yaml, out, generateOldSerializers, false);
	}

	private static void generate(String yaml, Path out, boolean generateOldSerializers, boolean binaryStrings)
			throws Exception {
		SourcesGenerator
				.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
				.generateSources(BASE_PACKAGE, out, false, generateOldSerializers, binaryStrings);
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
