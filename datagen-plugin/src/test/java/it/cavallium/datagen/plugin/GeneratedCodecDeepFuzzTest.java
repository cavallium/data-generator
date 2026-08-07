package it.cavallium.datagen.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.buffer.MemorySegmentBuf;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeLimitExceededException;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.nativedata.BinaryString;
import it.cavallium.datagen.nativedata.Int52;
import java.io.ByteArrayInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedCodecDeepFuzzTest {

	private static final long VALUE_SEED = 0x59D3_0A7C_E218_64BFL;
	private static final long MUTATION_SEED = 0x2F81_C6B4_70DA_395EL;
	private static final int VALUE_CASES_PER_STRING_MODE = 2_000;
	private static final DecodeLimits LIMITS = new DecodeLimits(64, 1_024, 2_048, 32_768, 16);

	@Test
	@SuppressWarnings("unchecked")
	void generatedCodecsReadersAndProjectionsFuzzAcrossValuesWireMutationsAndStorageKinds(
			@TempDir Path temp) throws Exception {
		for (boolean binaryStrings : List.of(false, true)) {
			Path sources = temp.resolve("sources-binary-" + binaryStrings);
			Path classes = temp.resolve("classes-binary-" + binaryStrings);
			generate(sources, binaryStrings);
			try (var loader = compileGeneratedSources(sources, classes);
					var arena = Arena.ofConfined()) {
				var harness = new GeneratedHarness(loader, binaryStrings);
				var valueRandom = new Random(VALUE_SEED ^ (binaryStrings ? -1L : 0L));
				var mutationRandom = new Random(MUTATION_SEED ^ (binaryStrings ? Long.MIN_VALUE : 0L));

				for (int caseIndex = 0; caseIndex < VALUE_CASES_PER_STRING_MODE; caseIndex++) {
					GeneratedValue generated = harness.randomValue(valueRandom);
					BufDataOutput output = BufDataOutput.create();
					harness.codec.serialize(output, generated.value());
					byte[] payload = output.asList().asArray().clone();
					String diagnostic = diagnostic(binaryStrings, caseIndex, payload.length);
					Object expectedProjection = invokeProjectionReader(harness.projectionReader,
							Buf.wrap(payload), 0, payload.length);

					for (BoundSource storage : storageKinds(arena, payload)) {
						assertEquals(generated.value(), harness.exactReader.read(storage.source(),
								storage.offset(), payload.length), diagnostic + ", exact=" + storage.name());
						assertEquals(generated.value(), invokeBoundReader(harness.currentReader,
								storage.source(), storage.offset(), payload.length),
								diagnostic + ", current=" + storage.name());
						assertEquals(expectedProjection, invokeProjectionReader(harness.projectionReader,
								storage.source(), storage.offset(), payload.length),
								diagnostic + ", projection=" + storage.name());
					}
					assertAllCursorsUnbound(harness.currentReader, diagnostic + ", current");
					assertAllCursorsUnbound(harness.projectionReader, diagnostic + ", projection");

					if (caseIndex % 32 == 0) {
						for (byte[] mutation : mutations(payload, mutationRandom)) {
							assertMutationStorageParity(harness, arena, mutation,
									diagnostic + ", mutationLength=" + mutation.length);
						}
						assertEquals(generated.value(), harness.exactReader.read(Buf.wrap(payload)),
								diagnostic + ", exact recovery");
						assertEquals(generated.value(), invokeBoundReader(harness.currentReader,
								Buf.wrap(payload), 0, payload.length), diagnostic + ", current recovery");
						assertEquals(expectedProjection, invokeProjectionReader(harness.projectionReader,
								Buf.wrap(payload), 0, payload.length), diagnostic + ", projection recovery");
						assertAllCursorsUnbound(harness.currentReader, diagnostic + ", current recovery");
						assertAllCursorsUnbound(harness.projectionReader, diagnostic + ", projection recovery");
					}

					if (caseIndex % 127 == 0) {
						assertArrayLimitIsEnforcedByEveryGeneratedReadPath(harness, generated,
								payload, diagnostic);
					}
				}
			}
		}
	}

	private static void assertMutationStorageParity(GeneratedHarness harness,
			Arena arena,
			byte[] mutation,
			String diagnostic) throws Exception {
		List<BoundSource> storages = storageKinds(arena, mutation);
		BoundSource first = storages.getFirst();
		Outcome exact = outcome(() -> harness.exactReader.read(first.source(), first.offset(), mutation.length));
		Outcome current = outcome(() -> invokeBoundReader(harness.currentReader,
				first.source(), first.offset(), mutation.length));
		Outcome projection = outcome(() -> invokeProjectionReader(harness.projectionReader,
				first.source(), first.offset(), mutation.length));
		for (BoundSource storage : storages.subList(1, storages.size())) {
			assertEquals(exact, outcome(() -> harness.exactReader.read(storage.source(),
					storage.offset(), mutation.length)), diagnostic + ", exact=" + storage.name());
			assertEquals(current, outcome(() -> invokeBoundReader(harness.currentReader,
					storage.source(), storage.offset(), mutation.length)),
					diagnostic + ", current=" + storage.name());
			assertEquals(projection, outcome(() -> invokeProjectionReader(harness.projectionReader,
					storage.source(), storage.offset(), mutation.length)),
					diagnostic + ", projection=" + storage.name());
		}
		assertAllCursorsUnbound(harness.currentReader, diagnostic + ", current");
		assertAllCursorsUnbound(harness.projectionReader, diagnostic + ", projection");
	}

	private static void assertArrayLimitIsEnforcedByEveryGeneratedReadPath(GeneratedHarness harness,
			GeneratedValue generated,
			byte[] payload,
			String diagnostic) throws Exception {
		int oneShort = generated.maximumArrayLength() - 1;
		assertTrue(oneShort >= 0, diagnostic);
		DecodeLimits limits = new DecodeLimits(oneShort, 1_024, 2_048, 32_768, 16);
		DataCodec.Reader<Object> exact = harness.codec.newReader(limits);
		assertThrows(DecodeLimitExceededException.class, () -> exact.read(Buf.wrap(payload)), diagnostic);

		Object current = harness.newCurrentReader(limits);
		InvocationTargetException currentFailure = assertThrows(InvocationTargetException.class,
				() -> invokeBoundReader(current, Buf.wrap(payload), 0, payload.length), diagnostic);
		assertTrue(currentFailure.getCause() instanceof DecodeLimitExceededException,
				() -> diagnostic + ", current cause=" + currentFailure.getCause());

		Object projection = harness.newProjectionReader(limits);
		InvocationTargetException projectionFailure = assertThrows(InvocationTargetException.class,
				() -> invokeProjectionReader(projection, Buf.wrap(payload), 0, payload.length), diagnostic);
		assertTrue(projectionFailure.getCause() instanceof DecodeLimitExceededException,
				() -> diagnostic + ", projection cause=" + projectionFailure.getCause());
		assertAllCursorsUnbound(current, diagnostic + ", limited current");
		assertAllCursorsUnbound(projection, diagnostic + ", limited projection");
	}

	private static List<byte[]> mutations(byte[] payload, Random random) {
		var mutations = new ArrayList<byte[]>();
		int cutCount = Math.min(64, payload.length + 1);
		for (int index = 0; index < cutCount; index++) {
			int cut = cutCount == 1 ? 0
					: (int) ((long) index * payload.length / (cutCount - 1));
			mutations.add(Arrays.copyOf(payload, cut));
		}
		if (payload.length != 0) {
			for (int index = 0; index < 64; index++) {
				byte[] flipped = payload.clone();
				int offset = random.nextInt(flipped.length);
				flipped[offset] ^= (byte) (1 << random.nextInt(Byte.SIZE));
				mutations.add(flipped);

				byte[] overwritten = payload.clone();
				int overwriteOffset = random.nextInt(overwritten.length);
				overwritten[overwriteOffset] = switch (index & 3) {
					case 0 -> 0;
					case 1 -> (byte) 0xff;
					case 2 -> 0x7f;
					case 3 -> (byte) 0x80;
					default -> throw new AssertionError();
				};
				mutations.add(overwritten);
			}
		}
		if (payload.length >= Integer.BYTES) {
			int availableOffsets = payload.length - Integer.BYTES + 1;
			int offsetCount = Math.min(32, availableOffsets);
			for (int offsetIndex = 0; offsetIndex < offsetCount; offsetIndex++) {
				int offset = offsetCount == 1 ? 0
						: (int) ((long) offsetIndex * (availableOffsets - 1) / (offsetCount - 1));
				for (int hostile : List.of(-1, Integer.MIN_VALUE, Integer.MAX_VALUE,
						0x4000_0000, 0x0100_0000, 0)) {
					byte[] lengthBomb = payload.clone();
					ByteBufferAccess.writeInt(lengthBomb, offset, hostile);
					mutations.add(lengthBomb);
				}
			}
		}
		for (int trailingLength : List.of(1, 2, 3, 7, 16, 31)) {
			byte[] trailing = Arrays.copyOf(payload, payload.length + trailingLength);
			for (int offset = payload.length; offset < trailing.length; offset++) {
				trailing[offset] = (byte) random.nextInt();
			}
			mutations.add(trailing);
		}
		return mutations;
	}

	private static List<BoundSource> storageKinds(Arena arena, byte[] payload) {
		byte[] padded = new byte[payload.length + 6];
		System.arraycopy(payload, 0, padded, 3, payload.length);
		MemorySegment nativeSegment = arena.allocate(Math.max(1, payload.length + 6L), 1);
		if (payload.length != 0) {
			MemorySegment.copy(MemorySegment.ofArray(payload), 0, nativeSegment, 3, payload.length);
		}
		return List.of(
				new BoundSource("heap", Buf.wrap(payload), 0),
				new BoundSource("heap-offset", Buf.wrap(padded), 3),
				new BoundSource("heap-nested-slice", Buf.wrap(padded).subListForced(1, payload.length + 5)
						.subListForced(2, payload.length + 2), 0),
				new BoundSource("native-offset", new StrictNativeBuf(nativeSegment), 3),
				new BoundSource("fallback", forcedFallbackBuf(Buf.wrap(padded)), 3));
	}

	private static Outcome outcome(ThrowingSupplier supplier) {
		try {
			return new Outcome(supplier.get(), null);
		} catch (Throwable failure) {
			Throwable unwrapped = failure instanceof InvocationTargetException invocation
					? invocation.getCause() : failure;
			if (unwrapped instanceof Error error) throw error;
			return new Outcome(null, unwrapped.getClass().getName());
		}
	}

	private static Object invokeBoundReader(Object reader, Buf source, int offset, int length)
			throws ReflectiveOperationException {
		Method method = reader.getClass().getMethod("read", Buf.class, int.class, int.class);
		method.setAccessible(true);
		return method.invoke(reader, source, offset, length);
	}

	private static Object invokeProjectionReader(Object reader, Buf source, int offset, int length)
			throws ReflectiveOperationException {
		Method method = reader.getClass().getMethod("read", int.class, Buf.class, int.class, int.class);
		method.setAccessible(true);
		return method.invoke(reader, 0, source, offset, length);
	}

	private static void assertAllCursorsUnbound(Object reader, String diagnostic)
			throws ReflectiveOperationException {
		var cursors = new ArrayList<BufDataCursor>();
		for (Class<?> owner = reader.getClass(); owner != null; owner = owner.getSuperclass()) {
			for (var field : owner.getDeclaredFields()) {
				if (!BufDataCursor.class.isAssignableFrom(field.getType())) continue;
				field.setAccessible(true);
				cursors.add((BufDataCursor) field.get(reader));
			}
		}
		assertFalse(cursors.isEmpty(), diagnostic + ", reader exposes no cursors");
		for (BufDataCursor cursor : cursors) assertFalse(cursor.isBound(), diagnostic);
	}

	private static Buf forcedFallbackBuf(Buf delegate) {
		return (Buf) Proxy.newProxyInstance(GeneratedCodecDeepFuzzTest.class.getClassLoader(),
				new Class<?>[] {Buf.class}, (proxy, method, arguments) -> switch (method.getName()) {
					case "getBackingByteArrayStrict", "asMemorySegmentStrict", "asArrayStrict",
							"asUnboundedArrayStrict" -> null;
					case "getBackingByteArray", "asArray", "asUnboundedArray", "binaryInputStream" ->
							throw new AssertionError("Fallback reader converted the complete payload");
					default -> {
						try {
							yield method.invoke(delegate, arguments);
						} catch (InvocationTargetException failure) {
							throw failure.getCause();
						}
					}
				});
	}

	private static void generate(Path sources, boolean binaryStrings) throws Exception {
		SourcesGenerator.load(new ByteArrayInputStream(SCHEMA.getBytes(StandardCharsets.UTF_8)))
				.generateSources("org.example.deepfuzz", sources, false, false, binaryStrings, false);
	}

	private static URLClassLoader compileGeneratedSources(Path sources, Path classes) throws Exception {
		Files.createDirectories(classes);
		var compiler = ToolProvider.getSystemJavaCompiler();
		var diagnostics = new DiagnosticCollector<JavaFileObject>();
		try (var fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			var files = new ArrayList<Path>();
			try (var paths = Files.walk(sources)) {
				paths.filter(path -> path.toString().endsWith(".java")).sorted().forEach(files::add);
			}
			var units = fileManager.getJavaFileObjectsFromPaths(files);
			String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
			boolean success = compiler.getTask(null, fileManager, diagnostics,
					List.of("--release", "25", "-classpath", classPath, "-d", classes.toString()),
					null, units).call();
			assertTrue(success, () -> diagnostics.getDiagnostics().toString());
		}
		return new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()},
				GeneratedCodecDeepFuzzTest.class.getClassLoader());
	}

	private static String diagnostic(boolean binaryStrings, int caseIndex, int payloadLength) {
		return "seed=" + VALUE_SEED + ", binaryStrings=" + binaryStrings + ", case=" + caseIndex
				+ ", payloadLength=" + payloadLength;
	}

	private record BoundSource(String name, Buf source, int offset) {}

	private record Outcome(Object value, String failureClass) {}

	private record GeneratedValue(Object value, int maximumArrayLength) {}

	@FunctionalInterface
	private interface ThrowingSupplier {
		Object get() throws Throwable;
	}

	private static final class StrictNativeBuf extends MemorySegmentBuf {

		private StrictNativeBuf(MemorySegment segment) {
			super(segment);
		}

		@Override
		public byte[] asArray() {
			throw new AssertionError("Native reader copied the complete payload");
		}
	}

	private static final class GeneratedHarness {

		private final boolean binaryStrings;
		private final Class<?> stringType;
		private final Class<?> leafType;
		private final Class<?> choiceType;
		private final Class<?> stringArrayType;
		private final Class<?> leafArrayType;
		private final Method leafFactory;
		private final Method numberChoiceFactory;
		private final Method textChoiceFactory;
		private final Method rootFactory;
		private final Method currentReaderFactory;
		private final Method projectionReaderFactory;
		private final Object rootBaseType;
		private final Class<?> projectionClass;
		private final DataCodec<Object> codec;
		private final DataCodec.Reader<Object> exactReader;
		private final Object currentReader;
		private final Object projectionReader;

		@SuppressWarnings({"unchecked", "rawtypes"})
		private GeneratedHarness(ClassLoader loader, boolean binaryStrings) throws Exception {
			this.binaryStrings = binaryStrings;
			this.stringType = binaryStrings ? BinaryString.class : String.class;
			this.leafType = loader.loadClass("org.example.deepfuzz.current.data.Leaf");
			Class<?> numberChoiceType = loader.loadClass("org.example.deepfuzz.current.data.NumberChoice");
			Class<?> textChoiceType = loader.loadClass("org.example.deepfuzz.current.data.TextChoice");
			this.choiceType = loader.loadClass("org.example.deepfuzz.current.data.Choice");
			Class<?> rootType = loader.loadClass("org.example.deepfuzz.current.data.Root");
			this.stringArrayType = Array.newInstance(stringType, 0).getClass();
			this.leafArrayType = Array.newInstance(leafType, 0).getClass();
			this.leafFactory = leafType.getMethod("of", long.class, stringType);
			this.numberChoiceFactory = numberChoiceType.getMethod("of", int.class);
			this.textChoiceFactory = textChoiceType.getMethod("of", stringType);
			this.rootFactory = rootType.getMethod("unsafeOfOwned", boolean.class, byte.class, short.class,
					char.class, int.class, long.class, Int52.class, float.class, double.class,
					boolean.class, long.class, stringType, stringType, leafType, leafType,
					boolean[].class, byte[].class, short[].class, char[].class, int[].class,
					long[].class, float[].class, double[].class, stringArrayType, leafArrayType,
					choiceType, choiceType, int.class);

			Class<?> version = loader.loadClass("org.example.deepfuzz.current.Version");
			this.codec = (DataCodec<Object>) version.getField("RootSerializerInstance").get(null);
			this.exactReader = codec.newReader(LIMITS);
			Class<?> baseType = loader.loadClass("org.example.deepfuzz.BaseType");
			this.rootBaseType = Enum.valueOf((Class<? extends Enum>) baseType, "Root");
			Class<?> currentVersion = loader.loadClass("org.example.deepfuzz.current.CurrentVersion");
			this.currentReaderFactory = currentVersion.getMethod("newReader", int.class, baseType,
					DecodeLimits.class);
			this.currentReader = newCurrentReader(LIMITS);
			this.projectionClass = loader.loadClass("org.example.deepfuzz.projections.RootDigestProjection");
			this.projectionReaderFactory = projectionClass.getMethod("newReader", DecodeLimits.class);
			this.projectionReader = newProjectionReader(LIMITS);
		}

		private Object newCurrentReader(DecodeLimits limits) throws ReflectiveOperationException {
			return currentReaderFactory.invoke(null, 0, rootBaseType, limits);
		}

		private Object newProjectionReader(DecodeLimits limits) throws ReflectiveOperationException {
			return projectionReaderFactory.invoke(null, limits);
		}

		private GeneratedValue randomValue(Random random) throws ReflectiveOperationException {
			Object leaf = randomLeaf(random);
			Object maybeLeaf = random.nextBoolean() ? randomLeaf(random) : null;
			boolean[] flags = new boolean[random.nextInt(17)];
			for (int index = 0; index < flags.length; index++) flags[index] = random.nextBoolean();
			byte[] bytes = new byte[1 + random.nextInt(16)];
			random.nextBytes(bytes);
			short[] shorts = new short[random.nextInt(17)];
			for (int index = 0; index < shorts.length; index++) shorts[index] = (short) random.nextInt();
			char[] chars = new char[random.nextInt(17)];
			for (int index = 0; index < chars.length; index++) chars[index] = (char) random.nextInt();
			int[] ints = new int[random.nextInt(17)];
			for (int index = 0; index < ints.length; index++) ints[index] = random.nextInt();
			long[] longs = new long[random.nextInt(17)];
			for (int index = 0; index < longs.length; index++) longs[index] = random.nextLong();
			float[] floats = new float[random.nextInt(17)];
			for (int index = 0; index < floats.length; index++) {
				floats[index] = Float.intBitsToFloat(random.nextInt());
			}
			double[] doubles = new double[random.nextInt(17)];
			for (int index = 0; index < doubles.length; index++) {
				doubles[index] = Double.longBitsToDouble(random.nextLong());
			}
			Object labels = Array.newInstance(stringType, random.nextInt(17));
			for (int index = 0; index < Array.getLength(labels); index++) {
				Array.set(labels, index, stringValue(randomUnicode(random)));
			}
			Object leaves = Array.newInstance(leafType, random.nextInt(17));
			for (int index = 0; index < Array.getLength(leaves); index++) {
				Array.set(leaves, index, randomLeaf(random));
			}
			Object choice = randomChoice(random);
			Object maybeChoice = random.nextBoolean() ? randomChoice(random) : null;
			boolean maybeTotalPresent = random.nextBoolean();
			Object title = stringValue(randomUnicode(random));
			Object maybeTitle = random.nextBoolean() ? stringValue(randomUnicode(random)) : null;

			Object value = rootFactory.invoke(null,
					random.nextBoolean(), (byte) random.nextInt(), (short) random.nextInt(),
					(char) random.nextInt(), random.nextInt(), random.nextLong(),
					Int52.fromLong(random.nextLong() & Int52.MAX_VALUE_L),
					Float.intBitsToFloat(random.nextInt()), Double.longBitsToDouble(random.nextLong()),
					maybeTotalPresent, random.nextLong(), title, maybeTitle, leaf, maybeLeaf,
					flags, bytes, shorts, chars, ints, longs, floats, doubles, labels, leaves,
					choice, maybeChoice, random.nextInt());
			int maximumArrayLength = List.of(flags.length, bytes.length, shorts.length, chars.length,
					ints.length, longs.length, floats.length, doubles.length, Array.getLength(labels),
					Array.getLength(leaves)).stream().mapToInt(Integer::intValue).max().orElseThrow();
			return new GeneratedValue(value, maximumArrayLength);
		}

		private Object randomLeaf(Random random) throws ReflectiveOperationException {
			return leafFactory.invoke(null, random.nextLong(), stringValue(randomUnicode(random)));
		}

		private Object randomChoice(Random random) throws ReflectiveOperationException {
			return random.nextBoolean()
					? numberChoiceFactory.invoke(null, random.nextInt())
					: textChoiceFactory.invoke(null, stringValue(randomUnicode(random)));
		}

		private Object stringValue(String value) {
			return binaryStrings ? new BinaryString(value.getBytes(StandardCharsets.UTF_8)) : value;
		}
	}

	private static String randomUnicode(Random random) {
		int codePoints = random.nextInt(33);
		var result = new StringBuilder(codePoints);
		for (int index = 0; index < codePoints; index++) {
			int codePoint = switch (random.nextInt(8)) {
				case 0 -> 0;
				case 1 -> 32 + random.nextInt(95);
				case 2 -> 0x80 + random.nextInt(0x700);
				case 3 -> 0x800 + random.nextInt(0x5000);
				case 4 -> 0xe000 + random.nextInt(0x1fff);
				default -> 0x1_0000 + random.nextInt(0x10_0000);
			};
			result.appendCodePoint(codePoint);
		}
		return result.toString();
	}

	private static final class ByteBufferAccess {

		private static void writeInt(byte[] bytes, int offset, int value) {
			bytes[offset] = (byte) (value >>> 24);
			bytes[offset + 1] = (byte) (value >>> 16);
			bytes[offset + 2] = (byte) (value >>> 8);
			bytes[offset + 3] = (byte) value;
		}
	}

	private static final String SCHEMA = """
			currentVersion: v1
			superTypesData:
			  Choice:
			    - NumberChoice
			    - TextChoice
			baseTypesData:
			  Leaf:
			    data:
			      id: long
			      label: String
			  NumberChoice:
			    data:
			      number: int
			  TextChoice:
			    data:
			      text: String
			  Root:
			    data:
			      active: boolean
			      code: byte
			      small: short
			      letter: char
			      count: int
			      total: long
			      packed: Int52
			      ratio: float
			      score: double
			      maybeTotal: -long
			      title: String
			      maybeTitle: -String
			      leaf: Leaf
			      maybeLeaf: -Leaf
			      flags: boolean[]
			      bytes: byte[]
			      shorts: short[]
			      chars: char[]
			      ints: int[]
			      longs: long[]
			      floats: float[]
			      doubles: double[]
			      labels: String[]
			      leaves: Leaf[]
			      choice: Choice
			      maybeChoice: -Choice
			      tail: int
			projectionsData:
			  RootDigest:
			    sourceType: Root
			    fields:
			      active: active
			      maybeTotal: maybeTotal
			      leafId: leaf.id
			      maybeLeafId: maybeLeaf.id
			      choice: choice
			      maybeChoice: maybeChoice
			      tail: tail
			versions:
			  v1:
			""";
}
