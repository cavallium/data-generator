package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.datagen.nativedata.ArraybooleanSerializer;
import it.cavallium.datagen.nativedata.ArrayintSerializer;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BufDataCursorTest {
	private static final DecodeLimits LIMITS = DecodeLimits.unlimited();

	@Test
	void readsHeapSliceAndCanBeReused() {
		var source = Buf.wrap(new byte[] {99, 1, 2, 3, 4, 5, 6, 7, 8, 3, 'f', 'o', 'o', 88});
		var cursor = new BufDataCursor(LIMITS);

		cursor.bind(source, 1, 12);
		assertEquals(0x01020304, cursor.readInt());
		assertEquals(0x05060708, cursor.readInt());
		assertEquals("foo", cursor.readString(cursor.readUnsignedByte(), StandardCharsets.UTF_8));
		assertEquals(0, cursor.remaining());
		cursor.unbind();
		assertFalse(cursor.isBound());

		cursor.bind(source.subList(5, 13), 0, 8);
		assertEquals(0x05060708, cursor.readInt());
		assertEquals("foo", cursor.readString(cursor.readUnsignedByte(), StandardCharsets.UTF_8));
		cursor.close();
		assertFalse(cursor.isBound());
	}

	@Test
	void readsNativeStorageWithoutRequestingAHeapArray() {
		try (var arena = Arena.ofConfined()) {
			MemorySegment segment = arena.allocate(17, 8);
			segment.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 1, 0x0102030405060708L);
			segment.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 9, 0x11223344);
			var source = new NoHeapCopyMemorySegmentBuf(segment);
			var cursor = new BufDataCursor(LIMITS);

			cursor.bind(source, 1, 12);
			assertEquals(0x0102030405060708L, cursor.readLong());
			assertEquals(0x11223344, cursor.readInt());
			cursor.unbind();
			assertFalse(source.heapArrayRequested);
		}
	}

	@Test
	void oneShotInputReadsNativeStringsWithoutAWholePayloadCopy() {
		byte[] encoded = new byte[] {0, 0, 0, 3, 'f', 'o', 'o'};
		try (var arena = Arena.ofConfined()) {
			MemorySegment segment = arena.allocate(encoded.length, 1);
			MemorySegment.copy(MemorySegment.ofArray(encoded), 0, segment, 0, encoded.length);
			Buf source = new NoPayloadConversionMemorySegmentBuf(segment);
			BufDataInput input = BufDataInput.create(source, LIMITS);
			assertEquals("foo", input.readMediumText(StandardCharsets.UTF_8));
			assertEquals(0, input.available());
		}
	}

	@Test
	void clearsBindingAndChecksEveryFailurePath() {
		var cursor = new BufDataCursor(LIMITS);
		assertThrows(IndexOutOfBoundsException.class, () -> cursor.bind(Buf.wrap(new byte[2]), 1, 2));
		assertFalse(cursor.isBound());

		cursor.bind(Buf.wrap(new byte[] {1, 2}), 0, 2);
		assertThrows(MalformedDataException.class, cursor::readInt);
		assertTrue(cursor.isBound());
		cursor.unbind();
		assertFalse(cursor.isBound());
		assertThrows(IllegalStateException.class, cursor::readByte);
	}

	@Test
	void supportsBulkReadsWithoutChangingBufferLimits() {
		var cursor = new BufDataCursor(LIMITS);
		cursor.bind(Buf.wrap(new byte[] {1, 2, 3, 4}), 0, 4);
		var destination = ByteBuffer.allocate(6);
		destination.position(1);
		cursor.readFully(destination, 3);
		assertEquals(4, destination.position());
		assertEquals(1, cursor.remaining());
		// The remaining count includes the byte not consumed from the bound region.
		assertEquals(4, cursor.readUnsignedByte());
		cursor.unbind();
	}

	@Test
	void revisitsBoundSubregionsWithoutSlicesOrParentCursorMovement() {
		Buf source = Buf.wrap(new byte[] {99, 10, 11, 12, 13, 14, 15, 88}).subList(1, 7);
		var parent = new BufDataCursor(LIMITS);
		var child = new BufDataCursor(LIMITS);
		parent.bind(source, 1, 4);
		assertEquals(0, parent.position());
		assertEquals(4, parent.length());
		assertEquals(11, parent.readUnsignedByte());
		assertEquals(1, parent.position());

		parent.bindRegion(child, 1, 2);
		assertEquals(12, child.readUnsignedByte());
		assertEquals(13, child.readUnsignedByte());
		assertEquals(1, parent.position());
		child.unbind();

		parent.position(3);
		assertEquals(14, parent.readUnsignedByte());
		assertThrows(IndexOutOfBoundsException.class, () -> parent.position(5));
		assertThrows(IndexOutOfBoundsException.class, () -> parent.bindRegion(child, 3, 2));
		parent.unbind();
		assertFalse(parent.isBound());
		assertFalse(child.isBound());
	}

	@Test
	void borrowedCursorCannotSelectItsOwnPolicyAndSharesTheParentBudget() {
		DecodeLimits limits = new DecodeLimits(4, 16, 4, 16, 2);
		var parent = new BufDataCursor(limits);
		var child = BufDataCursor.borrowed();
		assertThrows(IllegalStateException.class, () -> child.bind(Buf.wrap(new byte[1]), 0, 1));

		parent.bind(Buf.wrap(new byte[] {10, 11, 12}), 0, 3);
		parent.bindReservedRegion(child, 1, 1);
		assertSame(parent.decodeBudget(), child.decodeBudget());
		assertEquals(11, child.readUnsignedByte());
		child.unbind();
		parent.unbind();
	}

	@Test
	void exactSkipIsAtomicAndClosedRegionsRetainTheirConsumptionCount() {
		var cursor = new BufDataCursor(LIMITS);
		cursor.bind(Buf.wrap(new byte[] {1, 2, 3, 4}), 0, 4);
		ProjectionReadSupport.skipBytes(cursor, 3);
		assertEquals(3, cursor.position());
		assertThrows(MalformedDataException.class,
					() -> ProjectionReadSupport.skipBytes(cursor, 2));
		assertEquals(3, cursor.position());
		cursor.close();
		assertEquals(1, cursor.remainingIncludingClosed());

		cursor.bind(Buf.wrap(new byte[] {5, 6}), 0, 2);
		cursor.skipExact(2);
		cursor.unbind();
		assertEquals(0, cursor.remainingIncludingClosed());
	}

	@Test
	void reservesFixedRunsAndBulkReadsEveryPrimitiveAcrossAllStorageKinds() {
		Buf payload = primitivePayload();
		byte[] container = new byte[payload.size() + 2];
		System.arraycopy(payload.asArray(), 0, container, 1, payload.size());

		assertPrimitivePayload(Buf.wrap(container), 1, payload.size());
		assertSpecializedPrimitivePayload(Buf.wrap(container), 1, payload.size(),
				BufDataCursor.StorageKind.HEAP);
		assertPrimitivePayload(Buf.wrap(container).subList(1, 1 + payload.size()), 0, payload.size());
		assertSpecializedPrimitivePayload(Buf.wrap(container).subList(1, 1 + payload.size()), 0, payload.size(),
				BufDataCursor.StorageKind.HEAP);
		assertPrimitivePayload(new FallbackBuf(container), 1, payload.size());
		assertSpecializedPrimitivePayload(new FallbackBuf(container), 1, payload.size(),
				BufDataCursor.StorageKind.FALLBACK);

		try (var arena = Arena.ofConfined()) {
			MemorySegment segment = arena.allocate(container.length, 1);
			MemorySegment.copy(MemorySegment.ofArray(container), 0, segment, 0, container.length);
			assertPrimitivePayload(new NoPayloadConversionMemorySegmentBuf(segment), 1, payload.size());
			assertSpecializedPrimitivePayload(new NoPayloadConversionMemorySegmentBuf(segment), 1, payload.size(),
					BufDataCursor.StorageKind.MEMORY_SEGMENT);
		}
	}

	@Test
	void primitiveReservationsRejectMalformedLengthsBeforeAllocationAndWithoutAdvancing() {
		var cursor = new BufDataCursor(LIMITS);
		cursor.bind(Buf.wrap(new byte[3]), 0, 3);
		assertThrows(MalformedDataException.class, () -> cursor.reserve(4));
		assertEquals(0, cursor.position());
		assertThrows(MalformedDataException.class, () -> cursor.readIntArray(1));
		assertEquals(0, cursor.position());
		assertThrows(MalformedDataException.class, () -> cursor.readBooleanArray(Integer.MAX_VALUE));
		assertEquals(0, cursor.position());
		assertThrows(MalformedDataException.class, () -> cursor.readIntArray(Integer.MAX_VALUE));
		assertEquals(0, cursor.position());
		assertThrows(IndexOutOfBoundsException.class, () -> cursor.readInts(new int[1], 1, 1));
		assertEquals(0, cursor.position());
		cursor.unbind();

		BufDataOutput hugeIntArray = BufDataOutput.create();
		hugeIntArray.writeInt(Integer.MAX_VALUE);
		assertThrows(MalformedDataException.class,
					() -> new ArrayintSerializer().read(BufDataInput.create(hugeIntArray.asList(), LIMITS)));
		BufDataOutput hugeBooleanArray = BufDataOutput.create();
		hugeBooleanArray.writeInt(Integer.MAX_VALUE);
		assertThrows(MalformedDataException.class,
					() -> new ArraybooleanSerializer().read(BufDataInput.create(hugeBooleanArray.asList(), LIMITS)));

		BufDataOutput truncated = BufDataOutput.create();
		truncated.writeInt(2);
		truncated.writeInt(1);
		BufDataInput input = BufDataInput.create(truncated.asList(), LIMITS);
		assertThrows(MalformedDataException.class, () -> new ArrayintSerializer().read(input));
		assertEquals(Integer.BYTES, input.position());
	}

	private static Buf primitivePayload() {
		BufDataOutput output = BufDataOutput.create();
		output.writeBoolean(true);
		output.writeByte(0xa5);
		output.writeShort(0x8123);
		output.writeChar('\uff10');
		output.writeInt(0x89abcdef);
		output.writeLong(0x8123456789abcdefL);
		output.writeInt52(0x0a23456789abcdL);
		output.writeFloat(-12.5f);
		output.writeDouble(12345.25d);

		for (boolean value : expectedBooleans()) output.writeBoolean(value);
		output.write(expectedBytes());
		for (short value : expectedShorts()) output.writeShort(value);
		for (char value : expectedChars()) output.writeChar(value);
		for (int value : expectedInts()) output.writeInt(value);
		for (long value : expectedLongs()) output.writeLong(value);
		for (float value : expectedFloats()) output.writeFloat(value);
		for (double value : expectedDoubles()) output.writeDouble(value);
		return output.asList();
	}

	private static void assertPrimitivePayload(Buf source, int offset, int length) {
		var cursor = new BufDataCursor(LIMITS);
		cursor.bind(source, offset, length);
		assertPrimitivePayload(cursor);
	}

	private static void assertSpecializedPrimitivePayload(Buf source,
			int offset,
			int length,
			BufDataCursor.StorageKind expectedKind) {
		var heap = new HeapBufDataCursor(LIMITS);
		var segment = new MemorySegmentBufDataCursor(LIMITS);
		var fallback = new FallbackBufDataCursor(LIMITS);
		BufDataCursor.StorageKind kind = BufDataCursor.bindSpecialized(source, offset, length,
				heap, segment, fallback);
		assertEquals(expectedKind, kind);
		BufDataCursor cursor = switch (kind) {
			case HEAP -> heap;
			case MEMORY_SEGMENT -> segment;
			case FALLBACK -> fallback;
		};
		assertPrimitivePayload(cursor);
		assertFalse(heap.isBound());
		assertFalse(segment.isBound());
		assertFalse(fallback.isBound());
	}

	private static void assertPrimitivePayload(BufDataCursor cursor) {
		int fixed = cursor.reserve(37);
		assertEquals(0, fixed);
		assertTrue(cursor.getBooleanAt(fixed));
		assertEquals((byte) 0xa5, cursor.getByteAt(fixed + 1));
		assertEquals(0xa5, cursor.getUnsignedByteAt(fixed + 1));
		assertEquals((short) 0x8123, cursor.getShortAt(fixed + 2));
		assertEquals(0x8123, cursor.getUnsignedShortAt(fixed + 2));
		assertEquals('\uff10', cursor.getCharAt(fixed + 4));
		assertEquals(0x89abcdef, cursor.getIntAt(fixed + 6));
		assertEquals(0x8123456789abcdefL, cursor.getLongAt(fixed + 10));
		assertEquals(0x0a23456789abcdL, cursor.getInt52At(fixed + 18));
		assertEquals(-12.5f, cursor.getFloatAt(fixed + 25));
		assertEquals(12345.25d, cursor.getDoubleAt(fixed + 29));
		assertEquals(37, cursor.position());

		int arraysStart = cursor.position();
		assertArrayEquals(expectedBooleans(), cursor.readBooleanArray(expectedBooleans().length));
		assertArrayEquals(expectedBytes(), cursor.readByteArray(expectedBytes().length));
		assertArrayEquals(expectedShorts(), cursor.readShortArray(expectedShorts().length));
		assertArrayEquals(expectedChars(), cursor.readCharArray(expectedChars().length));
		assertArrayEquals(expectedInts(), cursor.readIntArray(expectedInts().length));
		assertArrayEquals(expectedLongs(), cursor.readLongArray(expectedLongs().length));
		assertArrayEquals(expectedFloats(), cursor.readFloatArray(expectedFloats().length));
		assertArrayEquals(expectedDoubles(), cursor.readDoubleArray(expectedDoubles().length));
		assertEquals(0, cursor.remaining());

		cursor.position(arraysStart);
		boolean[] booleans = new boolean[expectedBooleans().length + 2];
		byte[] bytes = new byte[expectedBytes().length + 2];
		short[] shorts = new short[expectedShorts().length + 2];
		char[] chars = new char[expectedChars().length + 2];
		int[] ints = new int[expectedInts().length + 2];
		long[] longs = new long[expectedLongs().length + 2];
		float[] floats = new float[expectedFloats().length + 2];
		double[] doubles = new double[expectedDoubles().length + 2];
		cursor.readBooleans(booleans, 1, expectedBooleans().length);
		cursor.readBytes(bytes, 1, expectedBytes().length);
		cursor.readShorts(shorts, 1, expectedShorts().length);
		cursor.readChars(chars, 1, expectedChars().length);
		cursor.readInts(ints, 1, expectedInts().length);
		cursor.readLongs(longs, 1, expectedLongs().length);
		cursor.readFloats(floats, 1, expectedFloats().length);
		cursor.readDoubles(doubles, 1, expectedDoubles().length);
		assertArrayEquals(new boolean[] {false, false, true, true, false, false}, booleans);
		assertArrayEquals(new byte[] {0, -128, -1, 0, 1, 127, 0}, bytes);
		assertArrayEquals(new short[] {0, Short.MIN_VALUE, -1, 0, Short.MAX_VALUE, 0}, shorts);
		assertArrayEquals(new char[] {0, 0, 'A', '\uffff', 0}, chars);
		assertArrayEquals(new int[] {0, Integer.MIN_VALUE, -1, 0, 0x11223344, Integer.MAX_VALUE, 0}, ints);
		assertArrayEquals(new long[] {0, Long.MIN_VALUE, -1, 0, 0x1122334455667788L, Long.MAX_VALUE, 0}, longs);
		assertArrayEquals(new float[] {0, -0.0f, 1.25f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 0}, floats);
		assertArrayEquals(new double[] {0, -0.0d, 1.25d, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0}, doubles);
		assertEquals(0, cursor.remaining());
		cursor.unbind();
	}

	private static boolean[] expectedBooleans() {
		return new boolean[] {false, true, true, false};
	}

	private static byte[] expectedBytes() {
		return new byte[] {-128, -1, 0, 1, 127};
	}

	private static short[] expectedShorts() {
		return new short[] {Short.MIN_VALUE, -1, 0, Short.MAX_VALUE};
	}

	private static char[] expectedChars() {
		return new char[] {0, 'A', '\uffff'};
	}

	private static int[] expectedInts() {
		return new int[] {Integer.MIN_VALUE, -1, 0, 0x11223344, Integer.MAX_VALUE};
	}

	private static long[] expectedLongs() {
		return new long[] {Long.MIN_VALUE, -1, 0, 0x1122334455667788L, Long.MAX_VALUE};
	}

	private static float[] expectedFloats() {
		return new float[] {-0.0f, 1.25f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY};
	}

	private static double[] expectedDoubles() {
		return new double[] {-0.0d, 1.25d, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
	}

	private static final class NoHeapCopyMemorySegmentBuf extends MemorySegmentBuf {

		private boolean heapArrayRequested;

		private NoHeapCopyMemorySegmentBuf(MemorySegment segment) {
			super(segment);
		}

		@Override
		public byte[] asArray() {
			heapArrayRequested = true;
			throw new AssertionError("Native source was copied wholesale to heap");
		}
	}

	private static final class NoPayloadConversionMemorySegmentBuf extends MemorySegmentBuf {

		private NoPayloadConversionMemorySegmentBuf(MemorySegment segment) {
			super(segment);
		}

		@Override
		public byte[] asArray() {
			throw new AssertionError("Native source was copied wholesale to heap");
		}

		@Override
		public it.cavallium.stream.SafeByteArrayInputStream binaryInputStream() {
			throw new AssertionError("Native source was converted to a stream");
		}
	}

	private static final class FallbackBuf extends ByteListBuf {

		private FallbackBuf(byte[] data) {
			super(data);
		}

		@Override
		public byte[] getBackingByteArrayStrict() {
			return null;
		}

		@Override
		public MemorySegment asMemorySegmentStrict() {
			return null;
		}

		@Override
		public byte[] getBackingByteArray() {
			throw new AssertionError("Fallback input requested heap storage");
		}

		@Override
		public byte[] asArray() {
			throw new AssertionError("Fallback input copied the complete payload");
		}
	}
}
