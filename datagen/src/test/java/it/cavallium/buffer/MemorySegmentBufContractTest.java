package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MemorySegmentBufContractTest {

	@Test
	void heapViewsAreExactAndZeroCopy() {
		byte[] bytes = {10, 20, 30, 40, 50};
		Buf slice = Buf.wrap(bytes).subList(1, 4);

		MemorySegment segment = slice.asMemorySegmentStrict();
		assertNotNull(segment);
		assertEquals(3, segment.byteSize());
		segment.set(ValueLayout.JAVA_BYTE, 1, (byte) 99);
		assertEquals(99, bytes[2]);

		ByteBuffer byteBuffer = slice.asByteBuffer();
		assertEquals(0, byteBuffer.position());
		assertEquals(3, byteBuffer.limit());
		assertEquals(3, byteBuffer.capacity());
		byteBuffer.put(2, (byte) 77);
		assertEquals(77, bytes[3]);
	}

	@Test
	void nativeViewsAndSlicesPreserveTheOriginalStorage() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment segment = arena.allocateFrom(ValueLayout.JAVA_BYTE,
					new byte[] {1, 2, 3, 4, 5});
			MemorySegmentBuf buf = new MemorySegmentBuf(segment);

			assertSame(segment, buf.asMemorySegment());
			assertSame(segment, buf.asMemorySegmentStrict());
			assertTrue(buf.asByteBuffer().isDirect());

			Buf slice = buf.subList(1, 4);
			assertArrayEquals(new byte[] {2, 3, 4}, slice.asArray());
			slice.setByte(1, (byte) 42);
			assertEquals(42, segment.get(ValueLayout.JAVA_BYTE, 2));
		}
	}

	@Test
	void freezeCreatesAReadOnlyZeroCopyView() {
		byte[] storage = {1, 2, 3};
		MemorySegmentBuf mutable = new MemorySegmentBuf(MemorySegment.ofArray(storage));
		Buf frozen = mutable.freeze();

		assertTrue(mutable.isMutable());
		assertFalse(frozen.isMutable());
		assertThrows(UnsupportedOperationException.class,
				() -> frozen.setByte(0, (byte) 9));

		mutable.setByte(0, (byte) 8);
		assertEquals(8, frozen.getByte(0));
		assertSame(frozen, frozen.freeze());
	}

	@Test
	void mutablePrimitiveSettersMatchHeapBuf() {
		Random random = new Random(0x4d53425546L);
		try (Arena arena = Arena.ofConfined()) {
			MemorySegmentBuf nativeBuf = new MemorySegmentBuf(arena.allocate(256, 8));
			Buf heapBuf = Buf.createZeroes(256);

			for (int operation = 0; operation < 10_000; operation++) {
				int kind = random.nextInt(8);
				int width = switch (kind) {
					case 0 -> 1;
					case 1, 2 -> 2;
					case 3, 4, 5 -> 4;
					default -> 8;
				};
				int offset = random.nextInt(257 - width);
				switch (kind) {
					case 0 -> {
						byte value = (byte) random.nextInt();
						nativeBuf.setByte(offset, value);
						heapBuf.setByte(offset, value);
					}
					case 1 -> {
						char value = (char) random.nextInt();
						nativeBuf.setChar(offset, value);
						heapBuf.setChar(offset, value);
					}
					case 2 -> {
						short value = (short) random.nextInt();
						nativeBuf.setShort(offset, value);
						heapBuf.setShort(offset, value);
					}
					case 3 -> {
						int value = random.nextInt();
						nativeBuf.setInt(offset, value);
						heapBuf.setInt(offset, value);
					}
					case 4 -> {
						int value = random.nextInt();
						nativeBuf.setIntLE(offset, value);
						heapBuf.setIntLE(offset, value);
					}
					case 5 -> {
						float value = Float.intBitsToFloat(random.nextInt());
						nativeBuf.setFloat(offset, value);
						heapBuf.setFloat(offset, value);
					}
					case 6 -> {
						long value = random.nextLong();
						nativeBuf.setLong(offset, value);
						heapBuf.setLong(offset, value);
					}
					case 7 -> {
						double value = Double.longBitsToDouble(random.nextLong());
						nativeBuf.setDouble(offset, value);
						heapBuf.setDouble(offset, value);
					}
					default -> throw new AssertionError();
				}
				assertTrue(nativeBuf.equals(0, heapBuf, 0, heapBuf.size()));
			}
			assertArrayEquals(heapBuf.asArray(), nativeBuf.asArray());
		}
	}

	@Test
	void randomizedHeapNativeBulkInteropMatchesArrayCopy() {
		Random random = new Random(0x42554c4bL);
		try (Arena arena = Arena.ofConfined()) {
			for (int round = 0; round < 1_000; round++) {
				int sourceSize = random.nextInt(2049);
				int targetSize = random.nextInt(2049);
				byte[] source = new byte[sourceSize];
				byte[] expected = new byte[targetSize];
				random.nextBytes(source);
				random.nextBytes(expected);

				MemorySegment sourceSegment = arena.allocate(sourceSize, 1);
				MemorySegment targetSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, expected);
				if (sourceSize != 0) {
					MemorySegment.copy(MemorySegment.ofArray(source), 0,
							sourceSegment, 0, sourceSize);
				}
				Buf nativeSource = new MemorySegmentBuf(sourceSegment);
				MemorySegmentBuf nativeTarget = new MemorySegmentBuf(targetSegment);

				int length = random.nextInt(Math.min(sourceSize, targetSize) + 1);
				int sourceOffset = random.nextInt(sourceSize - length + 1);
				int targetOffset = random.nextInt(targetSize - length + 1);
				nativeTarget.setBytesFromBuf(targetOffset, nativeSource, sourceOffset, length);
				System.arraycopy(source, sourceOffset, expected, targetOffset, length);
				assertArrayEquals(expected, nativeTarget.asArray());

				Buf heapTarget = Buf.createZeroes(targetSize);
				heapTarget.setBytesFromBuf(targetOffset, nativeSource, sourceOffset, length);
				byte[] expectedHeap = new byte[targetSize];
				System.arraycopy(source, sourceOffset, expectedHeap, targetOffset, length);
				assertArrayEquals(expectedHeap, heapTarget.asArray());
				assertTrue(nativeSource.equals(sourceOffset, source, sourceOffset, length));
			}
		}
	}

	@Test
	void closedScopeInvalidatesOriginalSliceAndFrozenViews() {
		Arena arena = Arena.ofConfined();
		MemorySegmentBuf original = new MemorySegmentBuf(arena.allocate(8));
		Buf slice = original.subList(2, 6);
		Buf frozen = slice.freeze();
		ByteBuffer byteBuffer = slice.asByteBuffer();
		arena.close();

		assertThrows(IllegalStateException.class, () -> original.getByte(0));
		assertThrows(IllegalStateException.class, () -> slice.getByte(0));
		assertThrows(IllegalStateException.class, () -> frozen.getByte(0));
		assertThrows(IllegalStateException.class, () -> byteBuffer.get(0));
	}

	@Test
	void invalidBulkRangesAreRejectedBeforeCopy() {
		MemorySegmentBuf target = new MemorySegmentBuf(MemorySegment.ofArray(new byte[4]));
		Buf source = Buf.wrap(new byte[4]);
		assertThrows(IndexOutOfBoundsException.class,
				() -> target.setBytesFromBuf(0, source, 3, 2));
		assertThrows(IndexOutOfBoundsException.class,
				() -> target.setBytesFromBuf(3, source, 0, 2));
		assertArrayEquals(new byte[4], target.asArray());
	}
}
