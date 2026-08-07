package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.stream.SafeByteArrayOutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Deep boundary, interop, aliasing, and lifetime fuzzing for every {@link Buf} storage shape. */
class BufStorageLifecycleDeepFuzzTest {

	private static final long PRIMITIVE_SEED = 0x29C8_4F16_AE73_50BDL;
	private static final long TRANSFER_SEED = 0x71E3_B940_2D5A_C86FL;
	private static final long TEXT_SEED = 0x5A10_D7EC_349B_82F6L;
	private static final long COMPARATOR_SEED = 0x0CB4_6F91_E2D8_357AL;
	private static final int PRIMITIVE_CASES = 20_000;
	private static final int TRANSFER_CASES = 20_000;
	private static final int TEXT_CASES = 10_000;
	private static final int COMPARATOR_CASES = 100_000;

	@Test
	void primitiveGettersAndSettersMatchByteBufferAtEveryValidOffsetAndStorageKind() {
		var random = new Random(PRIMITIVE_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < PRIMITIVE_CASES; caseIndex++) {
				byte[] expected = randomBytes(random, 129);
				if (expected.length < Long.BYTES) expected = Arrays.copyOf(expected, Long.BYTES);
				Buf heap = randomHeapShape(random, expected.clone());
				Buf nativeBuffer = nativeCopy(arena, expected);
				Buf heapSegment = new MemorySegmentBuf(MemorySegment.ofArray(expected.clone()));
				List<Buf> buffers = List.of(heap, nativeBuffer, heapSegment);
				int operation = random.nextInt(10);
				String diagnostic = diagnostic(PRIMITIVE_SEED, caseIndex, operation, expected.length);

				switch (operation) {
					case 0 -> {
						int offset = random.nextInt(expected.length);
						byte value = (byte) random.nextInt();
						for (Buf buffer : buffers) buffer.setByte(offset, value);
						expected[offset] = value;
					}
					case 1 -> {
						int offset = offset(random, expected.length, Short.BYTES);
						short value = (short) random.nextInt();
						for (Buf buffer : buffers) buffer.setShort(offset, value);
						ByteBuffer.wrap(expected).putShort(offset, value);
					}
					case 2 -> {
						int offset = offset(random, expected.length, Character.BYTES);
						char value = (char) random.nextInt();
						for (Buf buffer : buffers) buffer.setChar(offset, value);
						ByteBuffer.wrap(expected).putChar(offset, value);
					}
					case 3 -> {
						int offset = offset(random, expected.length, Integer.BYTES);
						int value = random.nextInt();
						for (Buf buffer : buffers) buffer.setInt(offset, value);
						ByteBuffer.wrap(expected).putInt(offset, value);
					}
					case 4 -> {
						int offset = offset(random, expected.length, Integer.BYTES);
						int value = random.nextInt();
						for (Buf buffer : buffers) buffer.setIntLE(offset, value);
						ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
					}
					case 5 -> {
						int offset = offset(random, expected.length, Long.BYTES);
						long value = random.nextLong();
						for (Buf buffer : buffers) buffer.setLong(offset, value);
						ByteBuffer.wrap(expected).putLong(offset, value);
					}
					case 6 -> {
						int offset = offset(random, expected.length, 7);
						long value = random.nextLong() & ((1L << 52) - 1);
						for (Buf buffer : buffers) buffer.setInt52(offset, value);
						for (int index = 0; index < 7; index++) {
							expected[offset + index] = (byte) (value >>> ((6 - index) * 8));
						}
					}
					case 7 -> {
						int offset = offset(random, expected.length, Float.BYTES);
						float value = Float.intBitsToFloat(random.nextInt());
						for (Buf buffer : buffers) buffer.setFloat(offset, value);
						ByteBuffer.wrap(expected).putInt(offset, Float.floatToRawIntBits(value));
					}
					case 8 -> {
						int offset = offset(random, expected.length, Double.BYTES);
						double value = Double.longBitsToDouble(random.nextLong());
						for (Buf buffer : buffers) buffer.setDouble(offset, value);
						ByteBuffer.wrap(expected).putLong(offset, Double.doubleToRawLongBits(value));
					}
					case 9 -> {
						int offset = random.nextInt(expected.length);
						boolean value = random.nextBoolean();
						for (Buf buffer : buffers) buffer.setBoolean(offset, value);
						expected[offset] = value ? (byte) 1 : 0;
					}
					default -> throw new AssertionError(operation);
				}

				for (Buf buffer : buffers) {
					assertArrayEquals(expected, buffer.asArray(), diagnostic + ", storage=" + storageName(buffer));
					assertPrimitiveReads(expected, buffer, random, diagnostic);
				}
			}
		}
	}

	@Test
	void everyRangeBoundaryFailsConsistentlyWithoutEscapingItsStorage() {
		var random = new Random(PRIMITIVE_SEED ^ -1L);
		try (var arena = Arena.ofConfined()) {
			for (int size = 0; size <= 96; size++) {
				byte[] bytes = new byte[size];
				random.nextBytes(bytes);
				for (Buf buffer : List.of(Buf.wrap(bytes.clone()), nativeCopy(arena, bytes),
						new MemorySegmentBuf(MemorySegment.ofArray(bytes.clone())))) {
					String diagnostic = "size=" + size + ", storage=" + storageName(buffer);
					for (int index : hostileIndices(size)) {
						if (index < 0 || index >= size) {
							assertThrows(RuntimeException.class, () -> buffer.getByte(index), diagnostic + ", byte=" + index);
						}
						if (index < 0 || index > size - Short.BYTES) {
							assertThrows(RuntimeException.class, () -> buffer.getShort(index), diagnostic + ", short=" + index);
							assertThrows(RuntimeException.class, () -> buffer.getChar(index), diagnostic + ", char=" + index);
						}
						if (index < 0 || index > size - Integer.BYTES) {
							assertThrows(RuntimeException.class, () -> buffer.getInt(index), diagnostic + ", int=" + index);
							assertThrows(RuntimeException.class, () -> buffer.getIntLE(index), diagnostic + ", intLE=" + index);
							assertThrows(RuntimeException.class, () -> buffer.getFloat(index), diagnostic + ", float=" + index);
						}
						if (index < 0 || index > size - Long.BYTES) {
							assertThrows(RuntimeException.class, () -> buffer.getLong(index), diagnostic + ", long=" + index);
							assertThrows(RuntimeException.class, () -> buffer.getDouble(index), diagnostic + ", double=" + index);
						}
						if (index < 0 || index > size - 7) {
							assertThrows(RuntimeException.class, () -> buffer.getInt52(index), diagnostic + ", int52=" + index);
						}
					}

					for (int from : hostileIndices(size)) {
						for (int to : hostileIndices(size)) {
							boolean valid = from >= 0 && from <= to && to <= size;
							if (valid) {
								assertArrayEquals(Arrays.copyOfRange(bytes, from, to),
										buffer.subListForced(from, to).asArray(), diagnostic);
								assertArrayEquals(Arrays.copyOfRange(bytes, from, to),
										buffer.copyOfRange(from, to).asArray(), diagnostic);
							} else {
								assertThrows(RuntimeException.class, () -> buffer.subListForced(from, to),
										diagnostic + ", slice=" + from + ".." + to);
								assertThrows(RuntimeException.class, () -> buffer.copyOfRange(from, to),
										diagnostic + ", copy=" + from + ".." + to);
							}
						}
					}
				}
			}
		}
	}

	@Test
	void overlappingTransfersCopiesSlicesAndAllInteropViewsPreserveExactAliasing() {
		var random = new Random(TRANSFER_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < TRANSFER_CASES; caseIndex++) {
				byte[] initial = randomBytes(random, 513);
				int length = random.nextInt(initial.length + 1);
				int sourceOffset = random.nextInt(initial.length - length + 1);
				int targetOffset = random.nextInt(initial.length - length + 1);
				byte[] expected = initial.clone();
				System.arraycopy(expected, sourceOffset, expected, targetOffset, length);
				String diagnostic = diagnostic(TRANSFER_SEED, caseIndex, length, initial.length)
						+ ", source=" + sourceOffset + ", target=" + targetOffset;

				for (Buf buffer : List.of(Buf.wrap(initial.clone()), nativeCopy(arena, initial),
						new MemorySegmentBuf(MemorySegment.ofArray(initial.clone())))) {
					buffer.setBytesFromBuf(targetOffset, buffer, sourceOffset, length);
					assertArrayEquals(expected, buffer.asArray(), diagnostic + ", storage=" + storageName(buffer));

					Buf copy = buffer.copy();
					assertArrayEquals(expected, copy.asArray(), diagnostic);
					if (copy.size() != 0) {
						byte original = buffer.getByte(0);
						copy.setByte(0, (byte) (copy.getByte(0) + 1));
						assertEquals(original, buffer.getByte(0), diagnostic);
					}

					int from = random.nextInt(buffer.size() + 1);
					int to = from + random.nextInt(buffer.size() - from + 1);
					Buf view = buffer.subListForced(from, to);
					MemorySegment strictSegment = view.asMemorySegmentStrict();
					assertNotNull(strictSegment, diagnostic);
					assertEquals(to - from, strictSegment.byteSize(), diagnostic);
					assertArrayEquals(Arrays.copyOfRange(expected, from, to),
							strictSegment.toArray(ValueLayout.JAVA_BYTE), diagnostic);
					ByteBuffer exactView = view.asByteBuffer();
					assertEquals(0, exactView.position(), diagnostic);
					assertEquals(to - from, exactView.remaining(), diagnostic);
					if (view.isMutable() && !view.isEmpty()) {
						byte value = (byte) random.nextInt();
						exactView.put(0, value);
						assertEquals(value, buffer.getByte(from), diagnostic);
					}
				}
			}
		}
	}

	@Test
	void heapAndNativeInteropStrictnessMatchesCopyAndOwnershipContracts() {
		byte[] backing = new byte[] {99, 1, 2, 3, 4, 88};
		Buf heap = Buf.wrap(backing).subListForced(1, 5);
		assertSame(backing, heap.getBackingByteArrayStrict());
		assertSame(backing, heap.getBackingByteArray());
		assertEquals(1, heap.getBackingByteArrayOffset());
		assertEquals(4, heap.getBackingByteArrayLength());
		assertEquals(1, heap.getBackingByteArrayFrom());
		assertEquals(5, heap.getBackingByteArrayTo());
		assertNull(heap.asArrayStrict());
		assertNull(heap.asUnboundedArrayStrict());
		assertArrayEquals(new byte[] {1, 2, 3, 4}, heap.asArray());
		assertArrayEquals(new byte[] {1, 2, 3, 4}, heap.asMemorySegmentStrict().toArray(ValueLayout.JAVA_BYTE));

		try (var arena = Arena.ofConfined()) {
			MemorySegment nativeSegment = arena.allocate(4, 1);
			nativeSegment.copyFrom(MemorySegment.ofArray(new byte[] {1, 2, 3, 4}));
			Buf nativeBuffer = new MemorySegmentBuf(nativeSegment);
			assertNull(nativeBuffer.asArrayStrict());
			assertNull(nativeBuffer.asUnboundedArrayStrict());
			assertNull(nativeBuffer.getBackingByteArrayStrict());
			assertThrows(UnsupportedOperationException.class, nativeBuffer::getBackingByteArray);
			assertEquals(0, nativeBuffer.getBackingByteArrayOffset());
			assertEquals(4, nativeBuffer.getBackingByteArrayLength());
			assertEquals(0, nativeBuffer.getBackingByteArrayFrom());
			assertEquals(4, nativeBuffer.getBackingByteArrayTo());
			assertSame(nativeSegment, nativeBuffer.asMemorySegment());
			assertSame(nativeSegment, nativeBuffer.asMemorySegmentStrict());
			assertArrayEquals(new byte[] {1, 2, 3, 4}, nativeBuffer.asArray());

			byte[] heapCopy = nativeBuffer.asArray();
			heapCopy[0] = 77;
			assertEquals(1, nativeBuffer.getByte(0));
			ByteBuffer copiedHeapView = nativeBuffer.asHeapByteBuffer();
			copiedHeapView.put(0, (byte) 66);
			assertEquals(1, nativeBuffer.getByte(0));
			ByteBuffer directView = nativeBuffer.asByteBuffer();
			directView.put(0, (byte) 55);
			assertEquals(55, nativeBuffer.getByte(0));
			assertThrows(UnsupportedOperationException.class, nativeBuffer::binaryOutputStream);
		}

		byte[] segmentBacking = new byte[] {4, 3, 2, 1};
		Buf heapSegment = new MemorySegmentBuf(MemorySegment.ofArray(segmentBacking));
		assertNull(heapSegment.getBackingByteArrayStrict());
		assertNull(heapSegment.asArrayStrict());
		heapSegment.asHeapByteBuffer().put(0, (byte) 9);
		assertEquals(9, segmentBacking[0]);
	}

	@Test
	void readOnlyAndClosedMemorySegmentLifetimesPropagateThroughSlicesBuffersAndFrozenViews() {
		Arena arena = Arena.ofConfined();
		MemorySegment segment = arena.allocate(32, 1);
		for (int index = 0; index < 32; index++) segment.set(ValueLayout.JAVA_BYTE, index, (byte) index);
		Buf mutable = new MemorySegmentBuf(segment);
		Buf frozen = mutable.freeze();
		Buf frozenAgain = frozen.freeze();
		Buf slice = mutable.subListForced(3, 19);
		ByteBuffer byteBuffer = slice.asByteBuffer();
		MemorySegment exactSegment = frozen.asMemorySegmentStrict();

		assertTrue(mutable.isMutable());
		assertFalse(frozen.isMutable());
		assertSame(frozen, frozenAgain);
		assertNotSame(mutable, frozen);
		assertThrows(UnsupportedOperationException.class, () -> frozen.setByte(0, (byte) 1));
		assertThrows(UnsupportedOperationException.class,
				() -> frozen.setBytesFromBuf(0, Buf.wrap((byte) 1), 0, 1));
		assertArrayEquals(mutable.asArray(), frozen.asArray());

		arena.close();
		assertThrows(IllegalStateException.class, () -> mutable.getByte(0));
		assertThrows(IllegalStateException.class, () -> frozen.getInt(0));
		assertThrows(IllegalStateException.class, slice::asArray);
		assertThrows(IllegalStateException.class, () -> byteBuffer.get(0));
		assertThrows(IllegalStateException.class,
				() -> exactSegment.get(ValueLayout.JAVA_BYTE, 0));
	}

	@Test
	void binaryStreamsWriteToAndCharsetViewsFuzzHeapSlicesAndNativeCopies() throws Exception {
		var random = new Random(TEXT_SEED);
		List<Charset> charsets = List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16BE,
				StandardCharsets.ISO_8859_1, StandardCharsets.US_ASCII);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < TEXT_CASES; caseIndex++) {
				byte[] bytes = randomBytes(random, 2_049);
				int from = random.nextInt(bytes.length + 1);
				int to = from + random.nextInt(bytes.length - from + 1);
				byte[] expected = Arrays.copyOfRange(bytes, from, to);
				String diagnostic = diagnostic(TEXT_SEED, caseIndex, from, bytes.length) + ", to=" + to;
				for (Buf buffer : List.of(Buf.wrap(bytes).subListForced(from, to), nativeCopy(arena, expected),
						new MemorySegmentBuf(MemorySegment.ofArray(expected.clone())))) {
					assertArrayEquals(expected, buffer.binaryInputStream().readAllBytes(), diagnostic);
					BufDataOutput output = BufDataOutput.create();
					buffer.writeTo(output);
					assertArrayEquals(expected, output.asList().asArray(), diagnostic);
					for (Charset charset : charsets) {
						assertEquals(new String(expected, charset), buffer.toString(charset), diagnostic);
						int textFrom = random.nextInt(expected.length + 1);
						int length = random.nextInt(expected.length - textFrom + 1);
						assertEquals(new String(expected, textFrom, length, charset),
								buffer.getString(textFrom, length, charset), diagnostic);
					}
				}

				byte[] writable = new byte[expected.length + 8];
				Arrays.fill(writable, (byte) 0x6a);
				Buf writableView = Buf.wrap(writable).subListForced(4, 4 + expected.length);
				try (SafeByteArrayOutputStream stream = writableView.binaryOutputStream()) {
					stream.write(expected);
				}
				assertArrayEquals(expected, Arrays.copyOfRange(writable, 4, 4 + expected.length), diagnostic);
				assertArrayEquals(new byte[] {0x6a, 0x6a, 0x6a, 0x6a}, Arrays.copyOf(writable, 4), diagnostic);
			}
		}
	}

	@Test
	void comparatorAndRangeComparatorMatchTheirLengthFirstUnsignedReference() {
		var random = new Random(COMPARATOR_SEED);
		ArraysComparator comparator = new VariableLengthLexiconographicComparator();
		for (int caseIndex = 0; caseIndex < COMPARATOR_CASES; caseIndex++) {
			byte[] left = randomBytes(random, 257);
			byte[] right = randomBytes(random, 257);
			int expected = lengthFirstCompare(left, 0, left.length, right, 0, right.length);
			String diagnostic = "seed=" + COMPARATOR_SEED + ", case=" + caseIndex;
			assertEquals(Integer.signum(expected), Integer.signum(comparator.compare(left, right)), diagnostic);

			int leftFrom = random.nextInt(left.length + 1);
			int leftTo = leftFrom + random.nextInt(left.length - leftFrom + 1);
			int rightFrom = random.nextInt(right.length + 1);
			int rightTo = rightFrom + random.nextInt(right.length - rightFrom + 1);
			expected = lengthFirstCompare(left, leftFrom, leftTo, right, rightFrom, rightTo);
			assertEquals(Integer.signum(expected), Integer.signum(comparator.compare(left, leftFrom, leftTo,
					right, rightFrom, rightTo)), diagnostic);
		}
		byte[] bytes = new byte[8];
		assertThrows(NullPointerException.class, () -> comparator.compare(null, bytes));
		assertThrows(NullPointerException.class, () -> comparator.compare(bytes, null));
		for (int invalid : List.of(Integer.MIN_VALUE, -1, 9, Integer.MAX_VALUE)) {
			assertThrows(RuntimeException.class,
					() -> comparator.compare(bytes, invalid, 8, bytes, 0, 8));
			assertThrows(RuntimeException.class,
					() -> comparator.compare(bytes, 0, 8, bytes, invalid, 8));
		}
	}

	@Test
	void defaultBufFallbackConversionsAndIgnoreCoverageMetadataAreExplicit() {
		byte[] bytes = new byte[] {1, 2, 3, 4};
		Buf fallback = forcedFallback(Buf.wrap(bytes));
		assertNull(fallback.getBackingByteArrayStrict());
		assertNull(fallback.asMemorySegmentStrict());
		assertArrayEquals(bytes, fallback.asMemorySegment().toArray(ValueLayout.JAVA_BYTE));
		assertArrayEquals(bytes, toArray(fallback.asByteBuffer()));

		Retention retention = IgnoreCoverage.class.getAnnotation(Retention.class);
		Target target = IgnoreCoverage.class.getAnnotation(Target.class);
		assertNotNull(retention);
		assertEquals(RetentionPolicy.CLASS, retention.value());
		assertNull(target);
	}

	private static void assertPrimitiveReads(byte[] expected,
			Buf actual,
			Random random,
			String diagnostic) {
		ByteBuffer bigEndian = ByteBuffer.wrap(expected);
		int byteOffset = random.nextInt(expected.length);
		assertEquals(expected[byteOffset], actual.getByte(byteOffset), diagnostic);
		assertEquals(expected[byteOffset] != 0, actual.getBoolean(byteOffset), diagnostic);
		int shortOffset = offset(random, expected.length, Short.BYTES);
		assertEquals(bigEndian.getShort(shortOffset), actual.getShort(shortOffset), diagnostic);
		assertEquals(bigEndian.getChar(shortOffset), actual.getChar(shortOffset), diagnostic);
		int intOffset = offset(random, expected.length, Integer.BYTES);
		assertEquals(bigEndian.getInt(intOffset), actual.getInt(intOffset), diagnostic);
		assertEquals(ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).getInt(intOffset),
				actual.getIntLE(intOffset), diagnostic);
		assertEquals(Float.floatToRawIntBits(bigEndian.getFloat(intOffset)),
				Float.floatToRawIntBits(actual.getFloat(intOffset)), diagnostic);
		int longOffset = offset(random, expected.length, Long.BYTES);
		assertEquals(bigEndian.getLong(longOffset), actual.getLong(longOffset), diagnostic);
		assertEquals(Double.doubleToRawLongBits(bigEndian.getDouble(longOffset)),
				Double.doubleToRawLongBits(actual.getDouble(longOffset)), diagnostic);
		int int52Offset = offset(random, expected.length, 7);
		long expectedInt52 = 0;
		for (int index = 0; index < 7; index++) {
			expectedInt52 = expectedInt52 << 8 | Byte.toUnsignedLong(expected[int52Offset + index]);
		}
		assertEquals(expectedInt52, actual.getInt52(int52Offset), diagnostic);
	}

	private static Buf randomHeapShape(Random random, byte[] exact) {
		if (random.nextBoolean()) return Buf.wrap(exact);
		byte[] padded = new byte[exact.length + 8];
		System.arraycopy(exact, 0, padded, 4, exact.length);
		return Buf.wrap(padded).subListForced(4, 4 + exact.length);
	}

	private static Buf nativeCopy(Arena arena, byte[] bytes) {
		MemorySegment segment = arena.allocate(Math.max(1, bytes.length), 1);
		if (bytes.length != 0) {
			MemorySegment.copy(MemorySegment.ofArray(bytes), 0, segment, 0, bytes.length);
		}
		return bytes.length == 0 ? new MemorySegmentBuf(segment.asSlice(0, 0)) : new MemorySegmentBuf(segment);
	}

	private static Buf forcedFallback(Buf delegate) {
		return (Buf) Proxy.newProxyInstance(BufStorageLifecycleDeepFuzzTest.class.getClassLoader(),
				new Class<?>[] {Buf.class}, (proxy, method, arguments) -> switch (method.getName()) {
					case "getBackingByteArrayStrict", "asMemorySegmentStrict", "asArrayStrict",
							"asUnboundedArrayStrict" -> null;
					case "getBackingByteArray" -> throw new UnsupportedOperationException();
					default -> {
						try {
							yield method.invoke(delegate, arguments);
						} catch (InvocationTargetException failure) {
							throw failure.getCause();
						}
					}
				});
	}

	private static byte[] toArray(ByteBuffer buffer) {
		byte[] result = new byte[buffer.remaining()];
		buffer.get(result);
		return result;
	}

	private static int[] hostileIndices(int size) {
		return new int[] {Integer.MIN_VALUE, -2, -1, 0, 1, Math.max(0, size - 1), size,
				size == Integer.MAX_VALUE ? size : size + 1, Integer.MAX_VALUE};
	}

	private static int lengthFirstCompare(byte[] left,
			int leftFrom,
			int leftTo,
			byte[] right,
			int rightFrom,
			int rightTo) {
		int leftLength = leftTo - leftFrom;
		int rightLength = rightTo - rightFrom;
		return leftLength != rightLength ? Integer.compare(leftLength, rightLength)
				: Arrays.compareUnsigned(left, leftFrom, leftTo, right, rightFrom, rightTo);
	}

	private static int offset(Random random, int size, int width) {
		return random.nextInt(size - width + 1);
	}

	private static byte[] randomBytes(Random random, int exclusiveMaximumLength) {
		byte[] result = new byte[random.nextInt(exclusiveMaximumLength)];
		random.nextBytes(result);
		return result;
	}

	private static String storageName(Buf buffer) {
		return buffer.getBackingByteArrayStrict() != null ? "heap"
				: buffer.asMemorySegmentStrict() != null && buffer.asMemorySegmentStrict().isNative()
				? "native" : "heap-segment";
	}

	private static String diagnostic(long seed, int caseIndex, int operation, int size) {
		return "seed=" + seed + ", case=" + caseIndex + ", operation=" + operation + ", size=" + size;
	}
}
