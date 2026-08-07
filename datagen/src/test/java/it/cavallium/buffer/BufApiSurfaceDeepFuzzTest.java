package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.stream.SafeByteArrayOutputStream;
import it.unimi.dsi.fastutil.bytes.ByteListIterator;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/** Covers the remaining bulk, stream, factory, and fixed-size list surfaces of the buffer package. */
class BufApiSurfaceDeepFuzzTest {

	private static final DecodeLimits UNLIMITED = DecodeLimits.unlimited();
	private static final long BULK_SEED = 0x24F9_7B16_C0E5_83ADL;
	private static final long OUTPUT_SEED = 0x68A2_D3F5_19CE_470BL;
	private static final long FIXED_LIST_SEED = 0x51C7_0EA4_BD93_268FL;
	private static final long LIFECYCLE_SEED = 0x7D30_4AE9_C516_82BFL;
	private static final int BULK_CASES = 20_000;
	private static final int OUTPUT_CASES = 10_000;
	private static final int FIXED_LIST_CASES = 20_000;
	private static final int LIFECYCLE_CASES = 20_000;

	@Test
	void bulkDestinationReadsAndStreamConveniencesFuzzEveryStorageAndPrimitiveFamily() {
		var random = new Random(BULK_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < BULK_CASES; caseIndex++) {
				BulkKind kind = BulkKind.values()[random.nextInt(BulkKind.values().length)];
				BulkVector vector = kind.randomVector(random, random.nextInt(65));
				String diagnostic = "seed=" + BULK_SEED + ", case=" + caseIndex
						+ ", kind=" + kind + ", bytes=" + vector.payload().length;
				for (Storage storage : storages(arena, vector.payload())) {
					var cursor = new BufDataCursor(UNLIMITED);
					cursor.bind(storage.source(), storage.offset(), vector.payload().length);
					assertTrue(cursor.markSupported(), diagnostic);
					assertEquals(vector.payload().length, cursor.available(), diagnostic);

					Object destination = kind.destination(vector.length() + 4);
					kind.fillSentinel(destination);
					kind.read(cursor, destination, 2, vector.length());
					kind.assertSlice(vector.values(), destination, 2, vector.length(),
							diagnostic + ", storage=" + storage.name());
					assertEquals(vector.payload().length, cursor.position(), diagnostic);
					assertEquals(0, cursor.available(), diagnostic);
					assertEquals(-1, cursor.read(), diagnostic);
					assertEquals(0, cursor.read(new byte[0]), diagnostic);

					cursor.position(0);
					cursor.mark(1);
					int requested = random.nextInt(vector.payload().length + 9);
					byte[] copied = new byte[requested + 4];
					Arrays.fill(copied, (byte) 0x5a);
					int copiedCount = cursor.readNBytes(copied, 2, requested);
					int expectedCount = Math.min(requested, vector.payload().length);
					assertEquals(expectedCount, copiedCount, diagnostic);
					assertArrayEquals(Arrays.copyOf(vector.payload(), expectedCount),
							Arrays.copyOfRange(copied, 2, 2 + expectedCount), diagnostic);
					cursor.reset();
					assertEquals(0, cursor.position(), diagnostic);
					int skipped = cursor.skipBytes(requested);
					assertEquals(expectedCount, skipped, diagnostic);
					int beforeUnsupported = cursor.position();
					assertThrows(UnsupportedOperationException.class, cursor::readLine, diagnostic);
					assertEquals(beforeUnsupported, cursor.position(), diagnostic);

					if (vector.payload().length >= 7) {
						assertEquals(readInt52(vector.payload(), 0), cursor.getInt52At(0), diagnostic);
					}
					cursor.position(0);
					Object invalidDestination = kind.destination(vector.length() + 1);
					assertThrows(IndexOutOfBoundsException.class,
							() -> kind.read(cursor, invalidDestination, 2, vector.length()), diagnostic);
					assertEquals(0, cursor.position(), diagnostic);
					cursor.unbind();

					if (vector.payload().length != 0) {
						cursor.bind(storage.source(), storage.offset(), vector.payload().length - 1);
						Object truncatedDestination = kind.destination(vector.length());
						assertThrows(MalformedDataException.class,
								() -> kind.read(cursor, truncatedDestination, 0, vector.length()), diagnostic);
						assertEquals(0, cursor.position(), diagnostic);
						cursor.unbind();
					}
				}
			}
		}
	}

	@Test
	@SuppressWarnings({"deprecation", "removal"})
	void outputTextBulkAndBufWritesMatchDataOutputAcrossAllFactoriesAndSourceStorage() throws Exception {
		var random = new Random(OUTPUT_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < OUTPUT_CASES; caseIndex++) {
				BufDataOutput actual = switch (caseIndex % 4) {
					case 0 -> BufDataOutput.create();
					case 1 -> BufDataOutput.create(random.nextInt(257));
					case 2 -> BufDataOutput.createLimited(-1, random.nextInt(257));
					default -> BufDataOutput.createLimited(Integer.MAX_VALUE, random.nextInt(257));
				};
				var expectedBytes = new ByteArrayOutputStream();
				try (var expected = new DataOutputStream(expectedBytes)) {
					for (int operationIndex = 0; operationIndex < 64; operationIndex++) {
						int operation = random.nextInt(8);
						String diagnostic = "seed=" + OUTPUT_SEED + ", case=" + caseIndex
								+ ", operation=" + operationIndex + ", kind=" + operation;
						switch (operation) {
							case 0 -> {
								String text = randomCodeUnits(random, 65);
								actual.writeBytes(text);
								expected.writeBytes(text);
							}
							case 1 -> {
								String text = randomCodeUnits(random, 65);
								actual.writeChars(text);
								expected.writeChars(text);
							}
							case 2 -> {
								String text = randomCodeUnits(random, 65);
								byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
								actual.writeUTF(text);
								expected.writeShort(encoded.length);
								expected.write(encoded);
							}
							case 3 -> {
								byte[] bytes = randomBytes(random, 129);
								Storage storage = storages(arena, bytes).get(random.nextInt(3));
								Buf source = storage.source().subListForced(storage.offset(),
										storage.offset() + bytes.length);
								actual.writeBytes(source);
								expected.write(bytes);
							}
							case 4 -> {
								byte[] bytes = randomBytes(random, 129);
								int from = random.nextInt(bytes.length + 1);
								int length = random.nextInt(bytes.length - from + 1);
								actual.writeBytes(bytes, from, length);
								expected.write(bytes, from, length);
							}
							case 5 -> {
								long position = actual.position();
								actual.ensureWritable(random.nextInt(257));
								assertEquals(position, actual.position(), diagnostic);
							}
							case 6 -> {
								int value = random.nextInt();
								actual.write(value);
								expected.write(value);
							}
							case 7 -> {
								actual.resetUnderlyingBuffer();
								expectedBytes.reset();
							}
							default -> throw new AssertionError(operation);
						}
						expected.flush();
						assertArrayEquals(expectedBytes.toByteArray(), actual.asList().asArray(), diagnostic);
						assertEquals(expectedBytes.size(), actual.size(), diagnostic);
						assertEquals(expectedBytes.size(), actual.position(), diagnostic);
					}
				}

				Buf detached = actual.toList();
				byte[] detachedBytes = detached.asArray().clone();
				actual.writeByte(0x7f);
				assertArrayEquals(detachedBytes, detached.asArray());
				assertNotSame(actual.asList(), detached);
				assertTrue(actual.equals(actual));
				assertFalse(actual.equals(null));
			}
		}
	}

	@Test
	@SuppressWarnings({"deprecation", "removal"})
	void limitedAndWrappedFactoriesEnforceBoundsWithoutEnsureWritableBypass() {
		for (int limit = 0; limit <= 512; limit++) {
			for (int hint : List.of(-1, 0, limit, limit + 1, 1_024)) {
				BufDataOutput output = BufDataOutput.createLimited(limit, hint);
				output.ensureWritable(limit + 1);
				output.write(new byte[limit]);
				byte[] before = output.asList().asArray().clone();
				assertThrows(IndexOutOfBoundsException.class, () -> output.writeByte(1),
						"limit=" + limit + ", hint=" + hint);
				assertArrayEquals(before, output.asList().asArray());
				assertEquals(limit, output.position());
			}
		}

		for (int unlimited : List.of(-1, Integer.MAX_VALUE)) {
			BufDataOutput output = BufDataOutput.createLimited(unlimited, 0);
			output.write(new byte[2_048]);
			assertEquals(2_048, output.size());
		}

		BufDataOutput empty = BufDataOutput.wrap(Buf.createZeroes(0));
		assertEquals(0, empty.position());
		assertThrows(IndexOutOfBoundsException.class, () -> empty.writeByte(1));

		byte[] wrappedBytes = new byte[16];
		SafeByteArrayOutputStream wrappedStream = new SafeByteArrayOutputStream(wrappedBytes, 3, 13);
		BufDataOutput wrapped = new BufDataOutput(wrappedStream, 10);
		wrapped.writeLong(0x0102_0304_0506_0708L);
		wrapped.writeShort(0x090a);
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
				Arrays.copyOfRange(wrappedBytes, 3, 13));
		assertThrows(IndexOutOfBoundsException.class, () -> wrapped.writeByte(11));
	}

	@Test
	void memorySegmentBufBehavesAsAMutableFixedSizeListAndRejectsEveryStructuralMutation() {
		var random = new Random(FIXED_LIST_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < FIXED_LIST_CASES; caseIndex++) {
				byte[] initial = randomBytes(random, 257);
				if (initial.length == 0) initial = new byte[] {0};
				MemorySegment segment = arena.allocate(initial.length, 1);
				segment.copyFrom(MemorySegment.ofArray(initial));
				Buf buffer = new MemorySegmentBuf(segment);
				byte[] expected = initial.clone();
				String diagnostic = "seed=" + FIXED_LIST_SEED + ", case=" + caseIndex;

				int index = random.nextInt(expected.length);
				byte replacement = (byte) random.nextInt();
				assertEquals(expected[index], buffer.set(index, replacement), diagnostic);
				expected[index] = replacement;
				ByteListIterator iterator = buffer.listIterator(index);
				assertEquals(expected[index], iterator.nextByte(), diagnostic);
				byte iteratorValue = (byte) random.nextInt();
				iterator.set(iteratorValue);
				expected[index] = iteratorValue;
				int xor = random.nextInt();
				buffer.replaceAll((IntUnaryOperator) value -> (byte) (value ^ xor));
				for (int i = 0; i < expected.length; i++) expected[i] = (byte) (expected[i] ^ xor);
				assertArrayEquals(expected, buffer.asArray(), diagnostic);

				List<StructuralMutation> mutations = List.of(
						new StructuralMutation("add", value -> value.add((byte) 1)),
						new StructuralMutation("add-index", value -> value.add(0, (byte) 1)),
						new StructuralMutation("remove", value -> value.removeByte(0)),
						new StructuralMutation("clear", Buf::clear),
						new StructuralMutation("resize", value -> value.size(value.size() + 1)),
						new StructuralMutation("add-elements", value -> value.addElements(0, new byte[] {1})),
						new StructuralMutation("remove-elements", value -> value.removeElements(0, 1)),
						new StructuralMutation("binary-output", value -> value.binaryOutputStream().write(1)));
				for (StructuralMutation mutation : mutations) {
					byte[] before = buffer.asArray();
					assertThrows(UnsupportedOperationException.class,
							() -> mutation.action().mutate(buffer), mutation.name() + ", " + diagnostic);
					assertArrayEquals(before, buffer.asArray(), diagnostic);
				}

				Buf frozen = buffer.freeze();
				assertFalse(frozen.isMutable(), diagnostic);
				assertThrows(UnsupportedOperationException.class,
						() -> frozen.set(0, (byte) 1), diagnostic);
				byte external = (byte) (buffer.getByte(0) + 1);
				buffer.setByte(0, external);
				assertEquals(external, frozen.getByte(0), diagnostic);
				assertSame(frozen, frozen.freeze(), diagnostic);
			}
		}
	}

	@Test
	@SuppressWarnings({"deprecation", "removal"})
	void cursorBindingLifecycleUnsignedReadsAndAbsoluteTextViewsFuzzEveryStorage() {
		var random = new Random(LIFECYCLE_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < LIFECYCLE_CASES; caseIndex++) {
				int unsignedShort = random.nextInt(1 << Short.SIZE);
				String sourceText = randomCodeUnits(random, 129);
				String expectedText = new String(sourceText.getBytes(StandardCharsets.UTF_8),
						StandardCharsets.UTF_8);
				BufDataOutput encoded = BufDataOutput.create();
				encoded.writeShort(unsignedShort);
				int shortTextOffset = encoded.size();
				encoded.writeShortText(sourceText, StandardCharsets.UTF_8);
				int mediumTextOffset = encoded.size();
				encoded.writeMediumText(sourceText, StandardCharsets.UTF_8);
				byte[] payload = encoded.asList().asArray().clone();
				String diagnostic = "seed=" + LIFECYCLE_SEED + ", case=" + caseIndex
						+ ", unsignedShort=" + unsignedShort + ", bytes=" + payload.length;

				for (Storage storage : storages(arena, payload)) {
					Buf exact = storage.source().subListForced(storage.offset(),
							storage.offset() + payload.length);
					assertEquals(expectedText, exact.getShortText(shortTextOffset, StandardCharsets.UTF_8),
							diagnostic + ", short view=" + storage.name());
					assertEquals(expectedText, exact.getMediumText(mediumTextOffset, StandardCharsets.UTF_8),
							diagnostic + ", medium view=" + storage.name());

					var cursor = new BufDataCursor(UNLIMITED);
					assertThrows(IllegalStateException.class, cursor::remainingIncludingClosed, diagnostic);
					assertThrows(IndexOutOfBoundsException.class,
							() -> cursor.bind(storage.source(), -1, payload.length), diagnostic);
					assertThrows(IndexOutOfBoundsException.class,
							() -> cursor.bind(storage.source(), storage.source().size(), 1), diagnostic);
					assertFalse(cursor.isBound(), diagnostic);

					cursor.bind(storage.source(), storage.offset(), payload.length);
					assertEquals(unsignedShort, cursor.readUnsignedShort(), diagnostic);
					int beforeDoubleBind = cursor.position();
					assertThrows(IllegalStateException.class,
							() -> cursor.bind(storage.source(), storage.offset(), payload.length), diagnostic);
					assertEquals(beforeDoubleBind, cursor.position(), diagnostic);
					assertEquals(expectedText, cursor.readUTF(), diagnostic);
					assertEquals(expectedText, cursor.readMediumText(StandardCharsets.UTF_8), diagnostic);
					assertEquals(0, cursor.remainingIncludingClosed(), diagnostic);
					cursor.close();
					assertFalse(cursor.isBound(), diagnostic);
					assertEquals(0, cursor.remainingIncludingClosed(), diagnostic);
					cursor.unbind();
					assertEquals(0, cursor.remainingIncludingClosed(), diagnostic);

					assertThrows(IllegalStateException.class, cursor::position, diagnostic);
					assertThrows(IllegalStateException.class, cursor::length, diagnostic);
					assertThrows(IllegalStateException.class, cursor::remaining, diagnostic);
					assertThrows(IllegalStateException.class, cursor::available, diagnostic);
					assertThrows(IllegalStateException.class, cursor::decodeBudget, diagnostic);
					assertThrows(IllegalStateException.class, () -> cursor.directStorageOffset(0), diagnostic);
					assertThrows(IllegalStateException.class, cursor::read, diagnostic);

					int from = random.nextInt(payload.length + 1);
					int length = random.nextInt(payload.length - from + 1);
					cursor.bind(storage.source(), storage.offset() + from, length);
					assertEquals(length, cursor.remainingIncludingClosed(), diagnostic);
					cursor.close();
					assertEquals(length, cursor.remainingIncludingClosed(), diagnostic);
				}
			}
		}
	}

	private static List<Storage> storages(Arena arena, byte[] payload) {
		byte[] padded = new byte[payload.length + 6];
		System.arraycopy(payload, 0, padded, 3, payload.length);
		MemorySegment nativeSegment = arena.allocate(Math.max(1, padded.length), 1);
		MemorySegment.copy(MemorySegment.ofArray(padded), 0, nativeSegment, 0, padded.length);
		return List.of(
				new Storage("heap", Buf.wrap(padded), 3),
				new Storage("native", new MemorySegmentBuf(nativeSegment), 3),
				new Storage("fallback", forcedFallback(Buf.wrap(padded)), 3));
	}

	private static Buf forcedFallback(Buf delegate) {
		return (Buf) Proxy.newProxyInstance(BufApiSurfaceDeepFuzzTest.class.getClassLoader(),
				new Class<?>[] {Buf.class}, (proxy, method, arguments) -> switch (method.getName()) {
					case "getBackingByteArrayStrict", "asMemorySegmentStrict", "asArrayStrict",
							"asUnboundedArrayStrict" -> null;
					case "getBackingByteArray", "asArray", "asUnboundedArray", "binaryInputStream" ->
							throw new AssertionError("fallback bulk path copied the complete payload");
					default -> {
						try {
							yield method.invoke(delegate, arguments);
						} catch (InvocationTargetException failure) {
							throw failure.getCause();
						}
					}
				});
	}

	private static long readInt52(byte[] bytes, int offset) {
		return ((long) bytes[offset] & 0x0fL) << 48
				| ((long) bytes[offset + 1] & 0xffL) << 40
				| ((long) bytes[offset + 2] & 0xffL) << 32
				| ((long) bytes[offset + 3] & 0xffL) << 24
				| ((long) bytes[offset + 4] & 0xffL) << 16
				| ((long) bytes[offset + 5] & 0xffL) << 8
				| ((long) bytes[offset + 6] & 0xffL);
	}

	private static String randomCodeUnits(Random random, int exclusiveMaximumLength) {
		char[] value = new char[random.nextInt(exclusiveMaximumLength)];
		for (int index = 0; index < value.length; index++) value[index] = (char) random.nextInt(1 << 16);
		return new String(value);
	}

	private static byte[] randomBytes(Random random, int exclusiveMaximumLength) {
		byte[] result = new byte[random.nextInt(exclusiveMaximumLength)];
		random.nextBytes(result);
		return result;
	}

	private record Storage(String name, Buf source, int offset) {}

	private record BulkVector(byte[] payload, Object values, int length) {}

	private enum BulkKind {
		BOOLEAN(1) {
			@Override BulkVector randomVector(Random random, int length) {
				boolean[] values = new boolean[length];
				BufDataOutput output = BufDataOutput.create(length);
				for (int i = 0; i < length; i++) output.writeBoolean(values[i] = random.nextBoolean());
				return new BulkVector(output.asList().asArray().clone(), values, length);
			}
			@Override Object destination(int length) { return new boolean[length]; }
			@Override void fillSentinel(Object value) { Arrays.fill((boolean[]) value, true); }
			@Override void read(BufDataCursor input, Object value, int offset, int length) {
				input.readBooleans((boolean[]) value, offset, length);
			}
			@Override void assertSlice(Object expected, Object actual, int offset, int length, String diagnostic) {
				assertArrayEquals((boolean[]) expected,
						Arrays.copyOfRange((boolean[]) actual, offset, offset + length), diagnostic);
			}
		},
		BYTE(1) {
			@Override BulkVector randomVector(Random random, int length) {
				byte[] values = new byte[length];
				random.nextBytes(values);
				return new BulkVector(values.clone(), values, length);
			}
			@Override Object destination(int length) { return new byte[length]; }
			@Override void fillSentinel(Object value) { Arrays.fill((byte[]) value, (byte) 0x5a); }
			@Override void read(BufDataCursor input, Object value, int offset, int length) {
				input.readBytes((byte[]) value, offset, length);
			}
			@Override void assertSlice(Object expected, Object actual, int offset, int length, String diagnostic) {
				assertArrayEquals((byte[]) expected,
						Arrays.copyOfRange((byte[]) actual, offset, offset + length), diagnostic);
			}
		},
		SHORT(Short.BYTES) {
			@Override BulkVector randomVector(Random random, int length) {
				short[] values = new short[length];
				BufDataOutput output = BufDataOutput.create(length * width);
				for (int i = 0; i < length; i++) output.writeShort(values[i] = (short) random.nextInt());
				return new BulkVector(output.asList().asArray().clone(), values, length);
			}
			@Override Object destination(int length) { return new short[length]; }
			@Override void fillSentinel(Object value) { Arrays.fill((short[]) value, (short) 0x5a5a); }
			@Override void read(BufDataCursor input, Object value, int offset, int length) {
				input.readShorts((short[]) value, offset, length);
			}
			@Override void assertSlice(Object expected, Object actual, int offset, int length, String diagnostic) {
				assertArrayEquals((short[]) expected,
						Arrays.copyOfRange((short[]) actual, offset, offset + length), diagnostic);
			}
		},
		CHAR(Character.BYTES) {
			@Override BulkVector randomVector(Random random, int length) {
				char[] values = new char[length];
				BufDataOutput output = BufDataOutput.create(length * width);
				for (int i = 0; i < length; i++) output.writeChar(values[i] = (char) random.nextInt());
				return new BulkVector(output.asList().asArray().clone(), values, length);
			}
			@Override Object destination(int length) { return new char[length]; }
			@Override void fillSentinel(Object value) { Arrays.fill((char[]) value, (char) 0x5a5a); }
			@Override void read(BufDataCursor input, Object value, int offset, int length) {
				input.readChars((char[]) value, offset, length);
			}
			@Override void assertSlice(Object expected, Object actual, int offset, int length, String diagnostic) {
				assertArrayEquals((char[]) expected,
						Arrays.copyOfRange((char[]) actual, offset, offset + length), diagnostic);
			}
		},
		INT(Integer.BYTES) {
			@Override BulkVector randomVector(Random random, int length) {
				int[] values = new int[length];
				BufDataOutput output = BufDataOutput.create(length * width);
				for (int i = 0; i < length; i++) output.writeInt(values[i] = random.nextInt());
				return new BulkVector(output.asList().asArray().clone(), values, length);
			}
			@Override Object destination(int length) { return new int[length]; }
			@Override void fillSentinel(Object value) { Arrays.fill((int[]) value, 0x5a5a_5a5a); }
			@Override void read(BufDataCursor input, Object value, int offset, int length) {
				input.readInts((int[]) value, offset, length);
			}
			@Override void assertSlice(Object expected, Object actual, int offset, int length, String diagnostic) {
				assertArrayEquals((int[]) expected,
						Arrays.copyOfRange((int[]) actual, offset, offset + length), diagnostic);
			}
		},
		LONG(Long.BYTES) {
			@Override BulkVector randomVector(Random random, int length) {
				long[] values = new long[length];
				BufDataOutput output = BufDataOutput.create(length * width);
				for (int i = 0; i < length; i++) output.writeLong(values[i] = random.nextLong());
				return new BulkVector(output.asList().asArray().clone(), values, length);
			}
			@Override Object destination(int length) { return new long[length]; }
			@Override void fillSentinel(Object value) { Arrays.fill((long[]) value, 0x5a5a_5a5a_5a5a_5a5aL); }
			@Override void read(BufDataCursor input, Object value, int offset, int length) {
				input.readLongs((long[]) value, offset, length);
			}
			@Override void assertSlice(Object expected, Object actual, int offset, int length, String diagnostic) {
				assertArrayEquals((long[]) expected,
						Arrays.copyOfRange((long[]) actual, offset, offset + length), diagnostic);
			}
		},
		FLOAT(Float.BYTES) {
			@Override BulkVector randomVector(Random random, int length) {
				float[] values = new float[length];
				BufDataOutput output = BufDataOutput.create(length * width);
				for (int i = 0; i < length; i++) output.writeFloat(values[i] = Float.intBitsToFloat(random.nextInt()));
				return new BulkVector(output.asList().asArray().clone(), values, length);
			}
			@Override Object destination(int length) { return new float[length]; }
			@Override void fillSentinel(Object value) { Arrays.fill((float[]) value, -123.5f); }
			@Override void read(BufDataCursor input, Object value, int offset, int length) {
				input.readFloats((float[]) value, offset, length);
			}
			@Override void assertSlice(Object expected, Object actual, int offset, int length, String diagnostic) {
				float[] expectedValues = (float[]) expected;
				float[] actualValues = (float[]) actual;
				for (int i = 0; i < length; i++) {
					assertEquals(Float.floatToIntBits(expectedValues[i]),
							Float.floatToRawIntBits(actualValues[offset + i]), diagnostic + ", element=" + i);
				}
			}
		},
		DOUBLE(Double.BYTES) {
			@Override BulkVector randomVector(Random random, int length) {
				double[] values = new double[length];
				BufDataOutput output = BufDataOutput.create(length * width);
				for (int i = 0; i < length; i++) output.writeDouble(values[i] = Double.longBitsToDouble(random.nextLong()));
				return new BulkVector(output.asList().asArray().clone(), values, length);
			}
			@Override Object destination(int length) { return new double[length]; }
			@Override void fillSentinel(Object value) { Arrays.fill((double[]) value, -123.5d); }
			@Override void read(BufDataCursor input, Object value, int offset, int length) {
				input.readDoubles((double[]) value, offset, length);
			}
			@Override void assertSlice(Object expected, Object actual, int offset, int length, String diagnostic) {
				double[] expectedValues = (double[]) expected;
				double[] actualValues = (double[]) actual;
				for (int i = 0; i < length; i++) {
					assertEquals(Double.doubleToLongBits(expectedValues[i]),
							Double.doubleToRawLongBits(actualValues[offset + i]), diagnostic + ", element=" + i);
				}
			}
		};

		final int width;

		BulkKind(int width) {
			this.width = width;
		}

		abstract BulkVector randomVector(Random random, int length);

		abstract Object destination(int length);

		abstract void fillSentinel(Object value);

		abstract void read(BufDataCursor input, Object value, int offset, int length);

		abstract void assertSlice(Object expected, Object actual, int offset, int length, String diagnostic);
	}

	private record StructuralMutation(String name, Mutation action) {}

	@FunctionalInterface
	private interface Mutation {
		void mutate(Buf value) throws Exception;
	}
}
