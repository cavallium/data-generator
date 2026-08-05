package it.cavallium.datagen.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.BufDataInput;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.buffer.MemorySegmentBuf;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.stream.SafeDataInput;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Attributes;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A deliberately hostile evolution corpus. Unlike the focused generator tests, this fixture combines
 * long dependency chains, nested records, unions, nullable values, arrays, custom codecs, opaque
 * upgraders, context-sensitive transforms, moves, removals, projections, and no-op versions in one
 * generated model.
 */
class AdversarialEvolutionTest {
	private static final DecodeLimits LIMITS = DecodeLimits.unlimited();

	private static final String BASE_PACKAGE = "org.example";
	private static final int VERSION_COUNT = 32;
	private static final int CURRENT_VERSION = VERSION_COUNT - 1;

	@Test
	void generatedHistoricalReadersSurviveTheCompleteEvolutionHistory(@TempDir Path temp) throws Exception {
		String schema = resource("/adversarial-evolution.yaml");

		Path historicalSources = temp.resolve("historical-sources");
		Path historicalClasses = temp.resolve("historical-classes");
		generate(schema, historicalSources, true);
		writeOpaqueBoundaryUpgrader(historicalSources);
		List<CorpusRow> corpus;
		try (URLClassLoader loader = compileGeneratedSources(historicalSources, historicalClasses)) {
			assertGeneratedHistory(historicalSources, true);
			assertOptimizedSourceShape(historicalSources);
			assertReadPlanMethodSizes(historicalClasses);
			corpus = createAndVerifyHistoricalCorpus(loader, historicalClasses);
		}

		Path fusedSources = temp.resolve("fused-sources");
		Path fusedClasses = temp.resolve("fused-classes");
		generate(schema, fusedSources, false);
		writeOpaqueBoundaryUpgrader(fusedSources);
		try (URLClassLoader loader = compileGeneratedSources(fusedSources, fusedClasses)) {
			assertGeneratedHistory(fusedSources, false);
			assertOptimizedSourceShape(fusedSources);
			assertReadPlanMethodSizes(fusedClasses);
			verifyFusedOnlyCorpus(loader, corpus);
		}
	}

	private record CorpusRow(int version, byte[] payload, String currentSnapshot,
			String projectionSnapshot) {}

	private record BuildContext(URLClassLoader loader, Class<?> baseType, Object versionInstance,
			int version, EvolutionCoverage coverage) {

		Object codec(String typeName) throws ReflectiveOperationException {
			return versionInstance.getClass().getMethod("getCodec", baseType)
					.invoke(versionInstance, enumValue(baseType, typeName));
		}
	}

	private static final class EvolutionCoverage {

		private final Set<String> constructedTypes = new HashSet<>();
		private final Set<String> unionVariants = new HashSet<>();
		private boolean emptyArray;
		private boolean nonEmptyArray;
		private boolean nullablePrimitiveAbsent;
		private boolean nullablePrimitivePresent;
		private boolean nullableReferenceAbsent;
		private boolean nullableReferencePresent;
		private int maxDepth;

		void assertComplete() {
			assertTrue(constructedTypes.containsAll(Set.of("MegaRoot", "Payload", "Branch", "Boundary",
					"TwinA", "TwinB", "Leaf", "ChoiceA", "ChoiceB", "ChoiceC", "ChoiceD")),
					() -> "missing generated types: " + constructedTypes);
			assertEquals(Set.of("ChoiceA", "ChoiceB", "ChoiceC", "ChoiceD"), unionVariants);
			assertTrue(emptyArray, "corpus never constructed a canonical empty array");
			assertTrue(nonEmptyArray, "corpus never constructed a nonempty owned array");
			assertTrue(nullablePrimitiveAbsent, "corpus never flattened an absent primitive nullable");
			assertTrue(nullablePrimitivePresent, "corpus never flattened a present primitive nullable");
			assertTrue(nullableReferenceAbsent, "corpus never stored an absent reference nullable");
			assertTrue(nullableReferencePresent, "corpus never stored a present reference nullable");
			assertTrue(maxDepth >= 5, "corpus did not reach deeply nested records: " + maxDepth);
		}
	}

	@FunctionalInterface
	private interface ValueAssertion {
		void check(Object value) throws Exception;
	}

	private static List<CorpusRow> createAndVerifyHistoricalCorpus(URLClassLoader loader, Path classes)
			throws Exception {
		Class<?> baseType = loader.loadClass("org.example.BaseType");
		Object megaRootType = enumValue(baseType, "MegaRoot");
		Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
		Object mixedReader = currentVersion.getMethod("newReader", baseType, DecodeLimits.class).invoke(null, megaRootType, LIMITS);
		Class<?> projection = loader.loadClass("org.example.projections.MegaSummaryProjection");
		Object projectionReader = projection.getMethod("newReader", DecodeLimits.class).invoke(null, LIMITS);
		var rows = new ArrayList<CorpusRow>(VERSION_COUNT);
		var coverage = new EvolutionCoverage();

		for (int version = 0; version < VERSION_COUNT; version++) {
			final int rowVersion = version;
			Class<?> versionClass = versionClass(loader, version);
			Object versionInstance = versionClass.getField("INSTANCE").get(null);
			Object codec = versionClass.getMethod("getCodec", baseType).invoke(versionInstance, megaRootType);
			Method serialize = concreteSerializeMethod(codec);
			Class<?> historicalType = serialize.getParameterTypes()[1];
			Object historical = createValue(historicalType,
					new BuildContext(loader, baseType, versionInstance, version, coverage), 10_000 + version, 0,
					"MegaRoot");
			BufDataOutput output = BufDataOutput.create();
			serialize.invoke(codec, output, historical);
			Buf payload = output.asList();
			assertTrue(payload.size() > 32, "adversarial payload is unexpectedly small at version " + version);

			Object exact = codec.getClass().getMethod("read", SafeDataInput.class)
					.invoke(codec, BufDataInput.create(payload, LIMITS));
			assertEquals(historical, exact, "exact historical read at version " + version);
			BufDataOutput exactOutput = BufDataOutput.create(payload.size());
			serialize.invoke(codec, exactOutput, exact);
			assertArrayEquals(payload.asArray(), exactOutput.asList().asArray(),
					"historical wire round trip at version " + version);

			Object expected = currentVersion.getMethod("upgradeDataToLatestVersion", int.class, Object.class)
					.invoke(null, version, exact);
			BufDataInput streamInput = BufDataInput.create(payload, LIMITS);
			Object streamValue = currentVersion.getMethod("read", int.class, baseType, SafeDataInput.class)
					.invoke(null, version, megaRootType, streamInput);
			assertEquals(expected, streamValue, "fused stream read at version " + version);
			assertEquals(0, streamInput.available(), "stream reader left bytes at version " + version);

			Object projectionResult = projection.getMethod("read", int.class, SafeDataInput.class)
					.invoke(null, version, BufDataInput.create(payload, LIMITS));
			assertProjectionMatches(expected, projectionResult, version);
			String projectionSnapshot = projectionResult.toString();

			Object boundReader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, version, megaRootType, LIMITS);
			ValueAssertion objectAssertion = value -> assertEquals(expected, value,
					"storage-specialized read at version " + rowVersion);
			verifyAllStorageKinds(version, payload.asArray(), mixedReader, boundReader, objectAssertion);
			Object projectedFromBuf = invokeProjectionReader(projectionReader, version, payload, 0, payload.size());
			assertEquals(projectionSnapshot, projectedFromBuf.toString(),
					"reusable projection at version " + version);
			assertReaderCursorUnbound(projectionReader);

			verifyFailureRecovery(version, payload.asArray(), expected, mixedReader, boundReader);
			verifyUnionFailureSurfaces(version, payload.asArray(), codec, projection,
					projectionReader, projectionSnapshot);
			assertBoundReaderKernelBytecode(classes, "MegaRoot", version);
			rows.add(new CorpusRow(version, payload.asArray(), expected.toString(), projectionSnapshot));
		}

		Buf first = Buf.wrap(rows.getFirst().payload());
		InvocationTargetException unsupported = assertThrows(InvocationTargetException.class,
				() -> invokeReader(mixedReader, VERSION_COUNT + 7, first, 0, first.size()));
		assertTrue(unsupported.getCause() instanceof IllegalArgumentException, unsupported::toString);
		assertNormalReaderClean(mixedReader);
		InvocationTargetException unsupportedFactory = assertThrows(InvocationTargetException.class,
				() -> currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
						.invoke(null, VERSION_COUNT + 7, megaRootType, LIMITS));
		assertTrue(unsupportedFactory.getCause() instanceof IllegalArgumentException,
				unsupportedFactory::toString);
		coverage.assertComplete();
		return List.copyOf(rows);
	}

	private static void verifyFusedOnlyCorpus(URLClassLoader loader, List<CorpusRow> rows) throws Exception {
		Class<?> baseType = loader.loadClass("org.example.BaseType");
		Object megaRootType = enumValue(baseType, "MegaRoot");
		Class<?> currentVersion = loader.loadClass("org.example.current.CurrentVersion");
		Object mixedReader = currentVersion.getMethod("newReader", baseType, DecodeLimits.class).invoke(null, megaRootType, LIMITS);
		Class<?> projection = loader.loadClass("org.example.projections.MegaSummaryProjection");
		Object projectionReader = projection.getMethod("newReader", DecodeLimits.class).invoke(null, LIMITS);

		for (CorpusRow row : rows) {
			int version = row.version();
			Buf payload = Buf.wrap(row.payload());
			Class<?> versionClass = versionClass(loader, version);
			Object versionInstance = versionClass.getField("INSTANCE").get(null);
			Object codec = versionClass.getMethod("getCodec", baseType).invoke(versionInstance, megaRootType);
			Object exact = codec.getClass().getMethod("read", SafeDataInput.class)
					.invoke(codec, BufDataInput.create(payload, LIMITS));
			Object upgraded = currentVersion.getMethod("upgradeDataToLatestVersion", int.class, Object.class)
					.invoke(null, version, exact);
			assertEquals(row.currentSnapshot(), upgraded.toString(),
					"read-only historical codec upgrade at version " + version);

			Method serialize = concreteSerializeMethod(codec);
			if (codec.getClass().getName().matches("org\\.example\\.v\\d+\\.serializers\\..*")) {
				InvocationTargetException rejected = assertThrows(InvocationTargetException.class,
						() -> serialize.invoke(codec, BufDataOutput.create(), exact),
						"historical serializer unexpectedly wrote version " + version);
				assertEquals("NotSerializableException", rejected.getCause().getClass().getSimpleName(),
						"old serializer must be read-only at version " + version);
			} else {
				assertTrue(version >= CURRENT_VERSION - 1,
						"only the terminal no-op history may share the current codec");
				BufDataOutput output = BufDataOutput.create();
				serialize.invoke(codec, output, exact);
				assertArrayEquals(row.payload(), output.asList().asArray());
			}

			BufDataInput streamInput = BufDataInput.create(payload, LIMITS);
			Object streamValue = currentVersion.getMethod("read", int.class, baseType, SafeDataInput.class)
					.invoke(null, version, megaRootType, streamInput);
			assertEquals(row.currentSnapshot(), streamValue.toString(),
					"fused-only stream read at version " + version);
			assertEquals(0, streamInput.available());

			Object boundReader = currentVersion.getMethod("newReader", int.class, baseType, DecodeLimits.class)
					.invoke(null, version, megaRootType, LIMITS);
			verifyAllStorageKinds(version, row.payload(), mixedReader, boundReader,
					value -> assertEquals(row.currentSnapshot(), value.toString(),
							"fused-only storage read at version " + version));
			Object projectionResult = projection.getMethod("read", int.class, SafeDataInput.class)
					.invoke(null, version, BufDataInput.create(payload, LIMITS));
			assertEquals(row.projectionSnapshot(), projectionResult.toString(),
					"fused-only projection at version " + version);
			Object projectedFromBuf = invokeProjectionReader(
					projectionReader, version, payload, 0, payload.size());
			assertEquals(row.projectionSnapshot(), projectedFromBuf.toString());
			assertReaderCursorUnbound(projectionReader);
		}
	}

	private static Object createValue(Class<?> type, BuildContext context, int seed, int depth,
			String coordinate) throws Exception {
		assertTrue(depth < 16, () -> "unexpected recursive schema at " + coordinate + " (" + type + ")");
		context.coverage().maxDepth = Math.max(context.coverage().maxDepth, depth);
		if (type == String.class) {
			return "wire-" + context.version() + '-' + seed + "-λ-" + coordinate;
		}
		if (type == Integer.class) {
			return coordinate.endsWith(".fragile")
					? TortureExplodingIntCodec.GOOD_VALUE
					: 100_000 + Math.floorMod(seed * 97 + coordinate.hashCode(), 1_000_000);
		}
		if (type == Boolean.class || type == boolean.class) return (seed & 1) == 0;
		if (type == Byte.class || type == byte.class) return (byte) (seed * 17 + 3);
		if (type == Short.class || type == short.class) return (short) (seed * 31 + 7);
		if (type == Character.class || type == char.class) return (char) ('A' + Math.floorMod(seed, 40));
		if (type == Integer.TYPE) return seed * 101 + 17;
		if (type == Long.class || type == long.class) return 1_000_000_000L + seed * 10_007L;
		if (type == Float.class || type == float.class) return seed + 0.25F;
		if (type == Double.class || type == double.class) return seed + 0.125D;
		if (type.isArray()) {
			int size = Math.floorMod(seed + coordinate.hashCode() + context.version(), 5) == 0 ? 0 : 2;
			context.coverage().emptyArray |= size == 0;
			context.coverage().nonEmptyArray |= size != 0;
			Object array = Array.newInstance(type.componentType(), size);
			for (int index = 0; index < size; index++) {
				Array.set(array, index, createValue(type.componentType(), context,
						seed * 13 + index + 1, depth + 1, coordinate + '[' + index + ']'));
			}
			return array;
		}
		if (type.isInterface() && type.getSimpleName().equals("Choice")) {
			String subtype = "Choice" + (char) ('A'
					+ Math.floorMod(context.version() + coordinate.hashCode(), 4));
			context.coverage().unionVariants.add(subtype);
			Object subtypeCodec = context.codec(subtype);
			return createValue(concreteSerializeMethod(subtypeCodec).getParameterTypes()[1], context,
					seed * 19 + 5, depth + 1, coordinate + '<' + subtype + '>');
		}
		if (!type.getName().contains(".data.")) {
			throw new IllegalArgumentException("No adversarial value factory for " + type + " at " + coordinate);
		}
		context.coverage().constructedTypes.add(type.getSimpleName());

		Method factory = Arrays.stream(type.getDeclaredMethods())
				.filter(method -> method.getName().equals("unsafeOfOwned"))
				.filter(method -> Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()))
				.findFirst()
				.orElseThrow(() -> new NoSuchMethodException(type.getName() + ".unsafeOfOwned"));
		List<Field> fields = Arrays.stream(type.getDeclaredFields())
				.filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
				.toList();
		assertEquals(fields.size(), factory.getParameterCount(),
				() -> "factory/field mismatch for " + type.getName());
		Object[] arguments = new Object[fields.size()];
		for (int index = 0; index < fields.size(); index++) {
			Field field = fields.get(index);
			Class<?> parameterType = factory.getParameterTypes()[index];
			assertEquals(field.getType(), parameterType,
					() -> "factory order mismatch for " + type.getName() + '.' + field.getName());
			String childCoordinate = coordinate + '.' + field.getName();
			if (field.getType() == boolean.class && field.getName().startsWith("$datagen$present$")) {
				boolean present = Math.floorMod(context.version() + seed + index, 3) != 0;
				context.coverage().nullablePrimitivePresent |= present;
				context.coverage().nullablePrimitiveAbsent |= !present;
				arguments[index] = present;
			} else if (!field.getType().isPrimitive()
					&& isNullableReference(type, field.getName())) {
				boolean present = Math.floorMod(context.version() + seed + index, 4) != 0;
				context.coverage().nullableReferencePresent |= present;
				context.coverage().nullableReferenceAbsent |= !present;
				arguments[index] = present
						? createValue(parameterType, context, seed * 37 + index + 1,
								depth + 1, childCoordinate)
						: null;
			} else {
				arguments[index] = createValue(parameterType, context, seed * 37 + index + 1,
						depth + 1, childCoordinate);
			}
		}
		try {
			return factory.invoke(null, arguments);
		} catch (InvocationTargetException failure) {
			throw new IllegalStateException("Could not construct " + coordinate + " as " + type.getName(),
					failure.getCause());
		}
	}

	private static boolean isNullableReference(Class<?> owner, String fieldName) {
		String accessor = "has" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
		return Arrays.stream(owner.getMethods()).anyMatch(method -> method.getName().equals(accessor)
				&& method.getParameterCount() == 0 && method.getReturnType() == boolean.class);
	}

	private static Method concreteSerializeMethod(Object codec) {
		return Arrays.stream(codec.getClass().getMethods())
				.filter(method -> method.getName().equals("serialize") && method.getParameterCount() == 2)
				.filter(method -> !method.isBridge() && method.getParameterTypes()[1] != Object.class)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("No concrete serialize method on " + codec.getClass()));
	}

	private static Class<?> versionClass(URLClassLoader loader, int version) throws ClassNotFoundException {
		return loader.loadClass(version == CURRENT_VERSION
				? "org.example.current.Version"
				: "org.example.v" + version + ".Version");
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Object enumValue(Class<?> enumType, String name) {
		return Enum.valueOf((Class<? extends Enum>) enumType, name);
	}

	private static void assertProjectionMatches(Object current, Object projection, int version)
			throws ReflectiveOperationException {
		Object body = current.getClass().getMethod("body").invoke(current);
		Object branch = body.getClass().getMethod("mainBranch").invoke(body);
		Object leaf = branch.getClass().getMethod("head").invoke(branch);
		var expected = new LinkedHashMap<String, Object>();
		expected.put("anchor", current.getClass().getMethod("anchor").invoke(current));
		expected.put("epoch", current.getClass().getMethod("epoch").invoke(current));
		expected.put("payloadScore", body.getClass().getMethod("score").invoke(body));
		expected.put("leafScore", leaf.getClass().getMethod("score").invoke(leaf));
		expected.put("boundary", body.getClass().getMethod("guard").invoke(body));
		expected.put("marker", current.getClass().getMethod("marker").invoke(current));
		expected.put("finalMarker", current.getClass().getMethod("finalMarker").invoke(current));
		for (Map.Entry<String, Object> field : expected.entrySet()) {
			assertEquals(field.getValue(), projection.getClass().getMethod(field.getKey()).invoke(projection),
					"projection " + field.getKey() + " at version " + version);
		}
	}

	private static void verifyAllStorageKinds(int version, byte[] payload, Object mixedReader,
			Object boundReader, ValueAssertion assertion) throws Exception {
		byte[] padded = new byte[payload.length + 5];
		System.arraycopy(payload, 0, padded, 2, payload.length);
		padded[2 + payload.length] = 0x55;
		Buf heap = Buf.wrap(padded);
		assertBothReaders(version, heap, 2, payload.length, mixedReader, boundReader, assertion);
		Buf heapSlice = heap.subList(2, 2 + payload.length);
		assertBothReaders(version, heapSlice, 0, payload.length, mixedReader, boundReader, assertion);
		Buf fallback = forcedFallbackBuf(heap);
		assertBothReaders(version, fallback, 2, payload.length, mixedReader, boundReader, assertion);

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment segment = arena.allocate(payload.length + 5, 8);
			MemorySegment.copy(MemorySegment.ofArray(payload), 0, segment, 2, payload.length);
			Buf nativeSource = new MemorySegmentBuf(segment) {
				@Override
				public byte[] asArray() {
					throw new AssertionError("native reader copied the complete payload to heap");
				}

				@Override
				public it.cavallium.stream.SafeByteArrayInputStream binaryInputStream() {
					throw new AssertionError("native reader created a whole-payload stream");
				}
			};
			assertBothReaders(version, nativeSource, 2, payload.length,
					mixedReader, boundReader, assertion);
			Buf nativeSlice = nativeSource.subList(2, 2 + payload.length);
			assertBothReaders(version, nativeSlice, 0, payload.length,
					mixedReader, boundReader, assertion);
		}
	}

	private static void assertBothReaders(int version, Buf source, int offset, int length,
			Object mixedReader, Object boundReader, ValueAssertion assertion) throws Exception {
		assertion.check(invokeReader(mixedReader, version, source, offset, length));
		assertNormalReaderClean(mixedReader);
		assertion.check(invokeBoundReader(boundReader, source, offset, length));
		assertNormalReaderClean(boundReader);
	}

	private static void verifyFailureRecovery(int version, byte[] payload, Object expected,
			Object mixedReader, Object boundReader) throws Exception {
		Buf valid = Buf.wrap(payload);
		InvocationTargetException truncated = assertThrows(InvocationTargetException.class,
				() -> invokeReader(mixedReader, version, valid, 0, payload.length - 1));
		assertTrue(truncated.getCause() instanceof MalformedDataException,
				() -> "truncation v" + version + ": " + truncated.getCause());
		assertNormalReaderClean(mixedReader);
		assertEquals(expected, invokeReader(mixedReader, version, valid, 0, payload.length));
		assertNormalReaderClean(mixedReader);

		byte[] trailingBytes = Arrays.copyOf(payload, payload.length + 1);
		InvocationTargetException trailing = assertThrows(InvocationTargetException.class,
				() -> invokeBoundReader(boundReader, Buf.wrap(trailingBytes), 0, trailingBytes.length));
		assertTrue(trailing.getCause() instanceof IllegalArgumentException,
				() -> "trailing bytes v" + version + ": " + trailing.getCause());
		assertNormalReaderClean(boundReader);
		assertEquals(expected, invokeBoundReader(boundReader, valid, 0, payload.length));
		assertNormalReaderClean(boundReader);

		byte[] invalidUnion = payload.clone();
		invalidUnion[0] = 0x7f;
		InvocationTargetException discriminator = assertThrows(InvocationTargetException.class,
				() -> invokeReader(mixedReader, version, Buf.wrap(invalidUnion), 0, invalidUnion.length));
		assertTrue(discriminator.getCause() instanceof IllegalArgumentException,
				() -> "union discriminator v" + version + ": " + discriminator.getCause());
		assertNormalReaderClean(mixedReader);
		assertEquals(expected, invokeReader(mixedReader, version, valid, 0, payload.length));
		assertNormalReaderClean(mixedReader);

		byte[] customFailure = payload.clone();
		int sentinelOffset = uniqueIntOffset(customFailure, TortureExplodingIntCodec.GOOD_VALUE);
		writeBigEndianInt(customFailure, sentinelOffset, TortureExplodingIntCodec.FAILURE_VALUE);
		InvocationTargetException custom = assertThrows(InvocationTargetException.class,
				() -> invokeBoundReader(boundReader, Buf.wrap(customFailure), 0, customFailure.length));
		assertTrue(custom.getCause() instanceof IllegalStateException,
				() -> "custom codec v" + version + ": " + custom.getCause());
		assertNormalReaderClean(boundReader);
		assertEquals(expected, invokeBoundReader(boundReader, valid, 0, payload.length));
		assertNormalReaderClean(boundReader);
	}

	private static void verifyUnionFailureSurfaces(int version, byte[] payload, Object codec,
			Class<?> projection, Object projectionReader, String validProjectionSnapshot) throws Exception {
		byte[] invalidUnion = payload.clone();
		invalidUnion[0] = 0x7f;
		Buf malformed = Buf.wrap(invalidUnion);

		InvocationTargetException exactRead = assertThrows(InvocationTargetException.class,
				() -> codec.getClass().getMethod("read", SafeDataInput.class)
						.invoke(codec, BufDataInput.create(malformed, LIMITS)));
		assertMalformedUnion(exactRead, "exact codec read", version);
		InvocationTargetException exactSkip = assertThrows(InvocationTargetException.class,
				() -> codec.getClass().getMethod("skip", SafeDataInput.class)
						.invoke(codec, BufDataInput.create(malformed, LIMITS)));
		assertMalformedUnion(exactSkip, "exact codec skip", version);
		InvocationTargetException projectedStream = assertThrows(InvocationTargetException.class,
				() -> projection.getMethod("read", int.class, SafeDataInput.class)
						.invoke(null, version, BufDataInput.create(malformed, LIMITS)));
		assertMalformedUnion(projectedStream, "projection stream read", version);
		InvocationTargetException projectedBuf = assertThrows(InvocationTargetException.class,
				() -> invokeProjectionReader(projectionReader, version, malformed, 0, malformed.size()));
		assertMalformedUnion(projectedBuf, "projection reusable read", version);
		assertReaderCursorUnbound(projectionReader);

		Buf valid = Buf.wrap(payload);
		assertEquals(validProjectionSnapshot,
				invokeProjectionReader(projectionReader, version, valid, 0, valid.size()).toString());
		assertReaderCursorUnbound(projectionReader);
	}

	private static void assertMalformedUnion(InvocationTargetException failure, String path, int version) {
		assertTrue(failure.getCause() instanceof IllegalArgumentException,
				() -> path + " v" + version + ": " + failure.getCause());
		assertTrue(failure.getCause().getMessage().startsWith("Invalid union discriminator: "),
				() -> path + " has an unhelpful message: " + failure.getCause().getMessage());
	}

	private static int uniqueIntOffset(byte[] bytes, int value) {
		int found = -1;
		for (int index = 0; index <= bytes.length - Integer.BYTES; index++) {
			int candidate = (bytes[index] & 0xff) << 24
					| (bytes[index + 1] & 0xff) << 16
					| (bytes[index + 2] & 0xff) << 8
					| bytes[index + 3] & 0xff;
			if (candidate != value) continue;
			assertEquals(-1, found, "custom sentinel is not unique in the payload");
			found = index;
		}
		assertTrue(found >= 0, "custom sentinel is absent from the payload");
		return found;
	}

	private static void writeBigEndianInt(byte[] bytes, int offset, int value) {
		bytes[offset] = (byte) (value >>> 24);
		bytes[offset + 1] = (byte) (value >>> 16);
		bytes[offset + 2] = (byte) (value >>> 8);
		bytes[offset + 3] = (byte) value;
	}

	private static Object invokeReader(Object reader, int version, Buf source, int offset, int length)
			throws ReflectiveOperationException {
		Method method = reader.getClass().getMethod("read", int.class, Buf.class, int.class, int.class);
		method.setAccessible(true);
		return method.invoke(reader, version, source, offset, length);
	}

	private static Object invokeBoundReader(Object reader, Buf source, int offset, int length)
			throws ReflectiveOperationException {
		Method method = reader.getClass().getMethod("read", Buf.class, int.class, int.class);
		method.setAccessible(true);
		return method.invoke(reader, source, offset, length);
	}

	private static Object invokeProjectionReader(Object reader, int version, Buf source, int offset,
			int length) throws ReflectiveOperationException {
		Method method = reader.getClass().getMethod("read", int.class, Buf.class, int.class, int.class);
		return method.invoke(reader, version, source, offset, length);
	}

	private static Buf forcedFallbackBuf(Buf delegate) {
		return (Buf) Proxy.newProxyInstance(AdversarialEvolutionTest.class.getClassLoader(),
				new Class<?>[] {Buf.class}, (proxy, method, arguments) -> switch (method.getName()) {
					case "getBackingByteArrayStrict", "asMemorySegmentStrict" -> null;
					case "getBackingByteArray", "asArray", "binaryInputStream" ->
							throw new AssertionError("fallback reader converted the complete payload");
					default -> {
						try {
							yield method.invoke(delegate, arguments);
						} catch (InvocationTargetException failure) {
							throw failure.getCause();
						}
					}
				});
	}

	private static void assertOptimizedSourceShape(Path sources) throws IOException {
		String rootPlan = Files.readString(
				sources.resolve("org/example/current/readers/MegaRootReadPlan.java"));
		String payloadPlan = Files.readString(
				sources.resolve("org/example/current/readers/PayloadReadPlan.java"));
		String currentVersion = Files.readString(
				sources.resolve("org/example/current/CurrentVersion.java"));
		assertTrue(rootPlan.contains("fixedRun0 = randomInput.reserve("),
				"the oldest fixed-width run must have one bounds check");
		assertTrue(rootPlan.contains("getLongAt(fixedRun0 +"),
				"retained fixed values must decode at constant offsets");
		assertTrue(rootPlan.contains("codecReadState().session(\"Fragile\",")
				&& rootPlan.contains("Version.FragileSerializerInstance)"),
				"retained custom codecs must use the reader-owned lane session");
		assertTrue(rootPlan.contains("readReserved(randomInput"),
				"retained fixed custom codecs must decode from the coalesced reserved run");
		assertTrue(rootPlan.contains("codecReadState().session(\"Opaque\",")
				&& rootPlan.contains("Version.OpaqueSerializerInstance)"),
				"removed variable custom values must use their reader-owned session");
		assertTrue(rootPlan.contains(".skip(input)"),
				"removed variable custom values must still invoke the explicit session skipper");
		assertTrue(rootPlan.contains("wireMapArrayIndex"),
				"declarative array transforms must be fused into the wire plan");
		assertTrue(payloadPlan.contains("TortureBoundaryUpgrader"));
		assertTrue(payloadPlan.contains("upgradePlan"),
				"structural evolution after the opaque boundary must use a fused tail");
		assertFalse(rootPlan.contains("MegaRootUpgraderInstance.upgrade"),
				"fused reads must not re-enter the historical root upgrader chain");
		assertFalse(rootPlan.contains("PayloadUpgraderInstance.upgrade"),
				"fused reads must not re-enter the historical payload upgrader chain");
		assertFalse(currentVersion.contains("java.util.function.Function"));
		assertFalse(currentVersion.contains("::read"));
		for (int version = 0; version < VERSION_COUNT; version++) {
			assertTrue(currentVersion.contains("new MegaRootV" + version + "Reader(limits)"),
					"missing version-bound reader " + version);
		}
	}

	private static void assertReadPlanMethodSizes(Path classes) throws Exception {
		Path readers = classes.resolve("org/example/current/readers");
		var plans = new ArrayList<Path>();
		try (var paths = Files.walk(readers)) {
			paths.filter(path -> path.getFileName().toString().endsWith("ReadPlan.class"))
					.forEach(plans::add);
		}
		assertFalse(plans.isEmpty(), "no generated read-plan bytecode");
		int maxCodeLength = 0;
		int methodCount = 0;
		for (Path plan : plans) {
			var model = ClassFile.of().parse(plan);
			for (var method : model.methods()) {
				var code = method.findAttribute(Attributes.code());
				if (code.isEmpty()) continue;
				int codeLength = code.orElseThrow().codeLength();
				maxCodeLength = Math.max(maxCodeLength, codeLength);
				methodCount++;
				assertTrue(codeLength <= 32_768,
						() -> plan.getFileName() + "#" + method.methodName().stringValue()
								+ " is too large for reliable JIT compilation: " + codeLength);
			}
		}
		assertTrue(methodCount >= VERSION_COUNT * 3,
				"storage/version specialization unexpectedly collapsed");
		assertTrue(maxCodeLength > 0);
	}

	private static void assertBoundReaderKernelBytecode(Path classes, String typeName, int version)
			throws Exception {
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
				"bound reader must not contain invokedynamic dispatch at version " + version);
		assertFalse(allInstructions.stream().anyMatch(instruction -> instruction.opcode() == Opcode.TABLESWITCH
				|| instruction.opcode() == Opcode.LOOKUPSWITCH),
				"bound reader must not dispatch on version " + version);

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
					kernel.getKey() + " must directly invoke version " + version + " for " + typeName);
		}
	}

	private static void assertNormalReaderClean(Object reader) throws ReflectiveOperationException {
		assertReaderCursorUnbound(reader);
		assertReadFramesCleared(reader);
	}

	private static void assertReaderCursorUnbound(Object reader) throws ReflectiveOperationException {
		var cursors = new ArrayList<BufDataCursor>();
		for (Class<?> owner = reader.getClass(); owner != null; owner = owner.getSuperclass()) {
			for (Field field : owner.getDeclaredFields()) {
				if (!BufDataCursor.class.isAssignableFrom(field.getType())) continue;
				field.setAccessible(true);
				cursors.add((BufDataCursor) field.get(reader));
			}
		}
		assertFalse(cursors.isEmpty(), "reader exposes no reusable storage cursor");
		for (BufDataCursor cursor : cursors) assertCursorStorageCleared(cursor, "reader");
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
				for (Field field : stateClass.getDeclaredFields()) {
					if (BufDataCursor.class.isAssignableFrom(field.getType())) {
						field.setAccessible(true);
						assertCursorStorageCleared((BufDataCursor) field.get(inspectedState), field.getName());
					}
					if (!field.getName().startsWith("readFrame")) continue;
					field.setAccessible(true);
					Object frame = field.get(inspectedState);
					while (frame != null) {
						for (Field frameField : frame.getClass().getDeclaredFields()) {
							if (!frameField.getName().startsWith("wireView")) continue;
							frameField.setAccessible(true);
							assertWireViewCleared(frameField.get(frame));
						}
						for (String reference : List.of("parent", "state")) {
							try {
								Field referenceField = frame.getClass().getDeclaredField(reference);
								referenceField.setAccessible(true);
								assertEquals(null, referenceField.get(frame), reference);
							} catch (NoSuchFieldException ignored) {
								// Lean frames omit storage they cannot use.
							}
						}
						for (String cursorName : List.of("valueCursor", "contextCursor")) {
							try {
								Field cursorField = frame.getClass().getDeclaredField(cursorName);
								cursorField.setAccessible(true);
								BufDataCursor cursor = (BufDataCursor) cursorField.get(frame);
								assertCursorStorageCleared(cursor, cursorName);
							} catch (NoSuchFieldException ignored) {
								// Lean frames omit storage they cannot use.
							}
						}
						Field next = frame.getClass().getDeclaredField("next");
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
		for (Field field : view.getClass().getDeclaredFields()) {
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
					&& !Modifier.isFinal(field.getModifiers())) {
				assertEquals(null, value, field.getName());
			} else if (field.getName().startsWith("wireView")) {
				assertWireViewCleared(value, inspected);
			}
		}
	}

	private static void assertCursorStorageCleared(BufDataCursor cursor, String coordinate)
			throws ReflectiveOperationException {
		assertNotNull(cursor, coordinate);
		assertFalse(cursor.isBound(), coordinate);
		Class<?> core = cursor.getClass();
		while (core != null && !core.getSimpleName().equals("BufDataInputCore")) core = core.getSuperclass();
		if (core == null) throw new NoSuchFieldException("BufDataInputCore");
		for (String storage : List.of("source", "heap", "segment", "fallback", "activeStorage")) {
			Field storageField = core.getDeclaredField(storage);
			storageField.setAccessible(true);
			assertEquals(null, storageField.get(cursor), coordinate + '.' + storage);
		}
	}

	private static Object findFieldValue(Object instance, String name) throws IllegalAccessException {
		for (Class<?> owner = instance.getClass(); owner != null; owner = owner.getSuperclass()) {
			try {
				Field field = owner.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(instance);
			} catch (NoSuchFieldException ignored) {
				// Keep looking through generated implementation bases.
			}
		}
		return null;
	}

	private static void assertGeneratedHistory(Path sources, boolean oldSerializers) throws IOException {
		for (int version = 0; version < VERSION_COUNT - 1; version++) {
			Path versionSource = sources.resolve("org/example/v" + version + "/Version.java");
			assertTrue(Files.isRegularFile(versionSource), () -> "missing " + versionSource);
		}
		var historicalSerializers = new ArrayList<Path>();
		try (var paths = Files.walk(sources.resolve("org/example"))) {
			paths.filter(path -> path.toString().matches(".*[/\\\\]v\\d+[/\\\\]serializers[/\\\\].*Serializer\\.java"))
					.forEach(historicalSerializers::add);
		}
		assertFalse(historicalSerializers.isEmpty(), "no historical serializers were generated");
		for (Path serializer : historicalSerializers) {
			String source = Files.readString(serializer);
			assertEquals(!oldSerializers, source.contains("throw new NotSerializableException()"),
					() -> "unexpected historical serializer mode in " + serializer);
		}
		assertTrue(Files.isRegularFile(sources.resolve("org/example/current/Version.java")));
		assertTrue(Files.isRegularFile(sources.resolve("org/example/current/CurrentVersion.java")));
		assertTrue(Files.isRegularFile(sources.resolve("org/example/current/readers/MegaRootReadPlan.java")));
	}

	private static String resource(String name) throws IOException {
		try (InputStream input = AdversarialEvolutionTest.class.getResourceAsStream(name)) {
			if (input == null) throw new IOException("Missing test resource " + name);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void generate(String yaml, Path out, boolean generateOldSerializers) throws Exception {
		SourcesGenerator.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
				.generateSources(BASE_PACKAGE, out, false, generateOldSerializers, false);
	}

	private static void writeOpaqueBoundaryUpgrader(Path sources) throws IOException {
		Path source = sources.resolve("org/example/TortureBoundaryUpgrader.java");
		Files.createDirectories(source.getParent());
		Files.writeString(source, """
				package org.example;

				import it.cavallium.datagen.DataContextNone;
				import it.cavallium.datagen.DataUpgrader;

				public final class TortureBoundaryUpgrader implements DataUpgrader<DataContextNone,
						org.example.v11.data.Boundary, org.example.v11.data.Boundary> {
					@Override
					public org.example.v11.data.Boundary upgrade(DataContextNone context,
							org.example.v11.data.Boundary oldData) {
						return org.example.v11.data.Boundary.of(
								oldData.value() + 5_000,
								oldData.description() + "#boundary");
					}
				}
				""", StandardCharsets.UTF_8);
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
					List.of("--release", "25", "-classpath", classPath, "-d", classes.toString()),
					null, units).call();
			assertTrue(success, () -> diagnostics.getDiagnostics().toString());
		}
		return new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()},
				AdversarialEvolutionTest.class.getClassLoader());
	}
}
