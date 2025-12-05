package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeDataOutput;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class MemorySegmentBufTest2 {

	// --- Helpers ---

	private MemorySegmentBuf createOffHeapBuf(byte[] data) {
		Arena arena = Arena.ofAuto();
		MemorySegment segment = arena.allocate(data.length);
		MemorySegment.copy(MemorySegment.ofArray(data), 0, segment, 0, data.length);
		return new MemorySegmentBuf(segment);
	}

	private MemorySegmentBuf createHeapBuf(byte[] data) {
		// Creates a MemorySegmentBuf backed by a heap array (mutable usually)
		return new MemorySegmentBuf(MemorySegment.ofArray(data));
	}

	// --- Tests ---

	@Test
	void testCompareTo() {
		byte[] dataA = {1, 2, 3};
		byte[] dataB = {1, 2, 3};
		byte[] dataC = {1, 2, 4};     // Greater last byte
		byte[] dataD = {1, 2};        // Prefix
		byte[] dataE = {1, 2, 3, 4};  // Longer
		byte[] dataF = {2, 0, 0};     // Greater first byte

		MemorySegmentBuf bufA = createOffHeapBuf(dataA);
		MemorySegmentBuf bufB = createOffHeapBuf(dataB);
		MemorySegmentBuf bufC = createOffHeapBuf(dataC);
		MemorySegmentBuf bufD = createOffHeapBuf(dataD);
		MemorySegmentBuf bufE = createOffHeapBuf(dataE);
		MemorySegmentBuf bufF = createOffHeapBuf(dataF);

		// 1. Identity & Equality
		assertEquals(0, bufA.compareTo(bufA));
		assertEquals(0, bufA.compareTo(bufB));

		// 2. Content Mismatch
		assertTrue(bufA.compareTo(bufC) < 0); // 3 < 4
		assertTrue(bufC.compareTo(bufA) > 0);

		assertTrue(bufA.compareTo(bufF) < 0); // 1 < 2
		assertTrue(bufF.compareTo(bufA) > 0);

		// 3. Length Mismatch (Prefix)
		assertTrue(bufA.compareTo(bufD) > 0); // len 3 > len 2
		assertTrue(bufD.compareTo(bufA) < 0);

		assertTrue(bufA.compareTo(bufE) < 0); // len 3 < len 4
		assertTrue(bufE.compareTo(bufA) > 0);

		// 4. Compare against non-MemorySegmentBuf (fallback path)
		List<Byte> standardList = new ArrayList<>();
		for (byte b : dataA) {
			standardList.add(b); // {1, 2, 3}
		}
		assertEquals(0, bufA.compareTo(standardList));

		List<Byte> smallerList = new ArrayList<>();
		smallerList.add((byte) 0);
		assertTrue(bufA.compareTo(smallerList) > 0);
	}

	@Test
	void testEqualsExtensive() {
		byte[] data = {10, 20, 30, 40, 50};
		MemorySegmentBuf buf = createOffHeapBuf(data);

		// 1. Offset checks
		// Compare buf[1..3] ({20, 30}) with explicit array {20, 30}
		assertTrue(buf.equals(1, new byte[]{20, 30}, 0, 2));

		// Compare buf[1..3] with {0, 20, 30, 0} at offset 1
		assertTrue(buf.equals(1, new byte[]{0, 20, 30, 0}, 1, 2));

		// 2. Mismatches
		assertFalse(buf.equals(0, new byte[]{10, 21, 30}, 0, 3)); // Middle byte diff
		assertFalse(buf.equals(0, new byte[]{10, 20}, 0, 3));     // Length mismatch (implicit in logic if bounds checked)

		// 3. Bounds checks
		assertFalse(buf.equals(0, new byte[5], 0, 6)); // Length > buf size
		assertFalse(buf.equals(4, new byte[5], 0, 2)); // Offset+Length > buf size

		// 4. Compare with Buf wrapper (not MemorySegmentBuf)
		Buf heapBuf = ByteListBuf.wrap(new byte[]{10, 20, 30});
		assertTrue(buf.equals(0, heapBuf, 0, 3));
		assertFalse(buf.equals(1, heapBuf, 0, 3)); // {20,30,40} != {10,20,30}
	}

	@Test
	void testWriteTo() {
		// Create a buffer larger than the CHUNK_SIZE (64KB) to test the loop
		int size = 70_000;
		byte[] data = new byte[size];
		for (int i = 0; i < size; i++) {
			data[i] = (byte) (i % 127);
		}

		MemorySegmentBuf buf = createOffHeapBuf(data);

		// Mock SafeDataOutput using a ByteArrayOutputStream
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		SafeDataOutput mockOutput = new SafeDataOutput() {
			@Override
			public void write(int b) {
				baos.write(b);
			}

			@Override
			public void write(byte[] b) {
				try {
					baos.write(b);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}

			@Override
			public void write(byte[] b, int off, int len) {
				baos.write(b, off, len);
			}

			@Override
			public void writeBoolean(boolean v) {
			}

			@Override
			public void writeByte(int v) {
			}

			@Override
			public void writeShort(int v) {
			}

			@Override
			public void writeChar(int v) {
			}

			@Override
			public void writeInt(int v) {
			}

			@Override
			public void writeLong(long v) {
			}

			@Override
			public void writeInt52(long v) {

			}

			@Override
			public void writeFloat(float v) {
			}

			@Override
			public void writeDouble(double v) {
			}

			@Override
			public void writeBytes(String s) {
			}

			@Override
			public void writeChars(String s) {
			}

			@Override
			public void writeUTF(String s) {
			}

			@Override
			public void writeShortText(String s, Charset charset) {

			}

			@Override
			public void writeMediumText(String s, Charset charset) {

			}
		};

		buf.writeTo(mockOutput);

		assertArrayEquals(data, baos.toByteArray());
	}

	@Test
	void testBinaryInputStream() throws IOException {
		byte[] data = {1, 2, 3, 4, 5};
		MemorySegmentBuf buf = createOffHeapBuf(data);

		try (SafeByteArrayInputStream is = buf.binaryInputStream()) {
			assertEquals(1, is.read());
			assertEquals(2, is.read());
			byte[] rest = new byte[3];
			assertEquals(3, is.read(rest));
			assertArrayEquals(new byte[]{3, 4, 5}, rest);
			assertEquals(-1, is.read());
		}
	}

	@Test
	void testSetBytesFromBuf() {
		// Target: Native Heap Segment (Mutable)
		byte[] destBytes = new byte[10];
		MemorySegmentBuf dest = createHeapBuf(destBytes);

		// Source 1: MemorySegmentBuf (Native-to-Native copy)
		byte[] src1Bytes = {1, 2, 3};
		MemorySegmentBuf src1 = createOffHeapBuf(src1Bytes);
		dest.setBytesFromBuf(0, src1, 0, 3);

		// Source 2: ByteListBuf (Array-to-Native copy)
		byte[] src2Bytes = {4, 5, 6};
		Buf src2 = ByteListBuf.wrap(src2Bytes);
		dest.setBytesFromBuf(3, src2, 0, 3);

		// Source 3: Anonymous Buf (Fallback loop copy)
		Buf src3 = new ByteListBuf() {
			@Override
			public byte getByte(int index) {
				return (byte) 7;
			}

			@Override
			public int size() {
				return 1;
			}

			@Override
			public byte[] asArrayStrict() {
				return null;
			} // Force fallback
		};
		dest.setBytesFromBuf(6, src3, 0, 1);

		// Verify
		// 0-2: {1,2,3}, 3-5: {4,5,6}, 6: {7}, 7-9: {0,0,0}
		byte[] expected = {1, 2, 3, 4, 5, 6, 7, 0, 0, 0};
		assertArrayEquals(expected, destBytes);

		// Test Immutability check
		// Create read-only segment
		Arena arena = Arena.ofAuto();
		MemorySegment roSegment = arena.allocate(5).asReadOnly();
		MemorySegmentBuf roBuf = new MemorySegmentBuf(roSegment);
		assertFalse(roBuf.isMutable());
		assertThrows(UnsupportedOperationException.class, () -> roBuf.setBytesFromBuf(0, src1, 0, 1));
	}

	@Test
	void testHashCode() {
		byte[] data = {1, 2, 3};
		MemorySegmentBuf buf = createOffHeapBuf(data);

		// Expected hash code calculation for list [1, 2, 3]
		// 1
		// 31*1 + 1 = 32
		// 31*32 + 2 = 994
		// 31*994 + 3 = 30817
		int expected = 1;
		for (byte b : data) {
			expected = 31 * expected + b;
		}

		assertEquals(expected, buf.hashCode());

		// Compare with ByteArrayList hashcode
		ByteArrayList list = new ByteArrayList();
		for (byte b : data) {
			list.add(b);
		}
		assertEquals(list.hashCode(), buf.hashCode());
	}

	@Test
	void testSlicingAndCopying() {
		byte[] data = {10, 20, 30, 40};
		MemorySegmentBuf buf = createOffHeapBuf(data);

		// subList (Forced)
		Buf sub = buf.subListForced(1, 3); // {20, 30}
		assertEquals(2, sub.size());
		assertEquals(20, sub.getByte(0));
		assertTrue(sub instanceof MemorySegmentBuf); // Should remain zero-copy

		// copyOfRange
		Buf copy = buf.copyOfRange(1, 3); // {20, 30}
		assertEquals(2, copy.size());
		assertEquals(20, copy.getByte(0));
		assertFalse(copy instanceof MemorySegmentBuf); // Should be heap-based ByteListBuf

		// Full Copy
		Buf fullCopy = buf.copy();
		assertArrayEquals(data, fullCopy.asArray());

		// Empty Copy
		Buf emptyCopy = buf.copyOfRange(0, 0);
		assertEquals(0, emptyCopy.size());
	}
}