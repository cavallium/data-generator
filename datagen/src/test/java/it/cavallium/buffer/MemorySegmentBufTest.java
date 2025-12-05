package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MemorySegmentBufTest {

	// Helper to create a MemorySegmentBuf from a byte array
	private MemorySegmentBuf createBuf(byte[] data) {
		// We use an Arena to manage the memory. For tests, auto is fine,
		// but explicit confined arena ensures we can close it if needed (though GC handles Heap segments).
		// Using ofArray implies heap segment, which mimics the API surface.
		// For pure off-heap testing, we can allocateNative.
		MemorySegment segment = MemorySegment.ofArray(data);
		return new MemorySegmentBuf(segment);
	}

	private MemorySegmentBuf createOffHeapBuf(byte[] data) {
		Arena arena = Arena.ofAuto();
		MemorySegment segment = arena.allocate(data.length);
		MemorySegment.copy(MemorySegment.ofArray(data), 0, segment, 0, data.length);
		return new MemorySegmentBuf(segment);
	}

	@Test
	void testGetPrimitivesBigEndian() {
		// Pattern: 0x01 02 03 04 05 06 07 08
		byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
		Buf buf = createOffHeapBuf(data);

		// Byte
		assertEquals(1, buf.getByte(0));
		assertEquals(8, buf.getByte(7));

		// Short (Big Endian) -> 0x0102 = 258
		assertEquals((short) 0x0102, buf.getShort(0));
		// Short at offset 1 -> 0x0203 = 515
		assertEquals((short) 0x0203, buf.getShort(1));

		// Int (Big Endian) -> 0x01020304
		assertEquals(0x01020304, buf.getInt(0));

		// Long (Big Endian) -> 0x0102030405060708L
		assertEquals(0x0102030405060708L, buf.getLong(0));
	}

	@Test
	void testGetIntLittleEndian() {
		// Pattern: 0x01 02 03 04
		byte[] data = {1, 2, 3, 4};
		Buf buf = createOffHeapBuf(data);

		// Int LE -> 0x04030201
		assertEquals(0x04030201, buf.getIntLE(0));
	}

	@Test
	void testFloatingPoint() {
		float f = 123.456f;
		double d = 789.123d;

		byte[] fBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(f).array();
		byte[] dBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(d).array();

		// Combine them
		byte[] combined = new byte[12];
		System.arraycopy(fBytes, 0, combined, 0, 4);
		System.arraycopy(dBytes, 0, combined, 4, 8);

		Buf buf = createOffHeapBuf(combined);

		assertEquals(f, buf.getFloat(0), 0.0f);
		assertEquals(d, buf.getDouble(4), 0.0d);
	}

	@Test
	void testSlicingIsZeroCopy() {
		byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
		MemorySegmentBuf buf = createOffHeapBuf(data);

		// Slice [3..7) -> {3, 4, 5, 6}
		Buf slice = buf.subList(3, 7);

		assertEquals(4, slice.size());
		assertEquals(3, slice.getByte(0));
		assertEquals(6, slice.getByte(3));

		// Verify it's a MemorySegmentBuf (Zero Copy)
		assertTrue(slice instanceof MemorySegmentBuf);

		// Verify subList of subList
		// Slice [3..7) -> Slice [1..3) -> Original indices [4..6) -> {4, 5}
		Buf subSlice = slice.subList(1, 3);
		assertEquals(2, subSlice.size());
		assertEquals(4, subSlice.getByte(0));
		assertEquals(5, subSlice.getByte(1));
	}

	@Test
	void testArraysAndConversions() {
		byte[] data = {10, 20, 30};
		Buf buf = createOffHeapBuf(data);

		// asArray() should return a copy
		byte[] copy = buf.asArray();
		assertArrayEquals(data, copy);
		assertNotSame(data, copy); // Ensure it's a copy if off-heap

		// asArrayStrict() must return null for off-heap/memory segment
		assertNull(buf.asArrayStrict());

		// asUnboundedArray() -> copy
		assertArrayEquals(data, buf.asUnboundedArray());

		// asHeapByteBuffer()
		ByteBuffer bb = buf.asHeapByteBuffer();
		assertEquals(10, bb.get(0));
		assertTrue(bb.hasArray()); // It wrapped the copied array
	}

	@Test
	void testEqualsVectorized() {
		byte[] data1 = {1, 2, 3, 4, 5};
		byte[] data2 = {1, 2, 3, 4, 5};
		byte[] data3 = {1, 2, 0, 4, 5}; // Diff at index 2

		Buf buf1 = createOffHeapBuf(data1);
		Buf buf2 = createBuf(data2); // Heap segment
		Buf buf3 = createOffHeapBuf(data3);

		// 1. Buf vs Buf (Equal)
		assertTrue(buf1.equals(0, buf2, 0, 5));

		// 2. Buf vs Buf (Not Equal)
		assertFalse(buf1.equals(0, buf3, 0, 5));

		// 3. Buf vs Buf (Prefix match: 1,2,3,4 vs 1,2,3,4)
		assertTrue(buf1.equals(0, buf2, 0, 4));

		// 4. Buf vs Buf (Prefix mismatch: 1,2,3 vs 1,2,0)
		assertFalse(buf1.equals(0, buf3, 0, 3));

		// 5. Buf vs Buf (Prefix match before diff: 1,2 vs 1,2)
		assertTrue(buf1.equals(0, buf3, 0, 2));

		// 6. Buf vs byte[]
		assertTrue(buf1.equals(0, data2, 0, 5));
		assertFalse(buf1.equals(0, data3, 0, 5));

		// 7. Out of bounds check
		// Trying to compare 6 bytes from a 5-byte buffer
		assertFalse(buf1.equals(0, buf2, 0, 6));
	}

	@Test
	void testToStringAndStrings() {
		String testStr = "Hello RocksDB";
		byte[] data = testStr.getBytes(StandardCharsets.UTF_8);
		Buf buf = createOffHeapBuf(data);

		assertEquals(testStr, buf.toString(StandardCharsets.UTF_8));
		assertEquals("Rocks", buf.getString(6, 5, StandardCharsets.UTF_8));
	}

	@Test
	void testEdgeCases() {
		Buf empty = createOffHeapBuf(new byte[0]);
		assertEquals(0, empty.size());

		// Out of bounds
		assertThrows(IndexOutOfBoundsException.class, () -> empty.getByte(0));

		// Slicing out of bounds
		Buf buf = createOffHeapBuf(new byte[]{1, 2, 3});
		assertThrows(IndexOutOfBoundsException.class, () -> buf.subList(0, 4));
		assertThrows(IndexOutOfBoundsException.class, () -> buf.subList(-1, 2));
	}

	@Test
	void testSetBytesFromBuf() {
		// Source: {1, 2, 3, 4}
		Buf source = createOffHeapBuf(new byte[]{1, 2, 3, 4});

		// Dest: {0, 0, 0, 0, 0} (Heap array wrapped in MemorySegmentBuf for mutability test if supported)
		// Note: MemorySegmentBuf checks isMutable(). MemorySegment.ofArray() is mutable.
		byte[] destArray = new byte[5];
		MemorySegmentBuf dest = new MemorySegmentBuf(MemorySegment.ofArray(destArray));

		assertTrue(dest.isMutable());

		// Copy source[1..3] -> {2, 3} to dest[2]
		dest.setBytesFromBuf(2, source, 1, 2);

		// Expected dest: {0, 0, 2, 3, 0}
		assertArrayEquals(new byte[]{0, 0, 2, 3, 0}, destArray);
	}

	@Test
	void testCopyOfRange() {
		byte[] data = {10, 20, 30, 40, 50};
		Buf buf = createOffHeapBuf(data);

		Buf copy = buf.copyOfRange(1, 4); // {20, 30, 40}
		assertEquals(3, copy.size());
		assertEquals(20, copy.getByte(0));
		assertEquals(40, copy.getByte(2));

		// Verify it is a distinct copy (Heap Buf)
		assertTrue(copy instanceof ByteListBuf);
	}

}