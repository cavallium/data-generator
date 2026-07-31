package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BufDataCursorTest {

	@Test
	void readsHeapSliceAndCanBeReused() {
		var source = Buf.wrap(new byte[] {99, 1, 2, 3, 4, 5, 6, 7, 8, 3, 'f', 'o', 'o', 88});
		var cursor = new BufDataCursor();

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
			MemorySegment segment = arena.allocate(16, 8);
			segment.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 0, 0x0102030405060708L);
			segment.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 8, 0x11223344);
			var source = new NoHeapCopyMemorySegmentBuf(segment);
			var cursor = new BufDataCursor();

			cursor.bind(source, 0, 12);
			assertEquals(0x0102030405060708L, cursor.readLong());
			assertEquals(0x11223344, cursor.readInt());
			cursor.unbind();
			assertFalse(source.heapArrayRequested);
		}
	}

	@Test
	void clearsBindingAndChecksEveryFailurePath() {
		var cursor = new BufDataCursor();
		assertThrows(IndexOutOfBoundsException.class, () -> cursor.bind(Buf.wrap(new byte[2]), 1, 2));
		assertFalse(cursor.isBound());

		cursor.bind(Buf.wrap(new byte[] {1, 2}), 0, 2);
		assertThrows(IndexOutOfBoundsException.class, cursor::readInt);
		assertTrue(cursor.isBound());
		cursor.unbind();
		assertFalse(cursor.isBound());
		assertThrows(IllegalStateException.class, cursor::readByte);
	}

	@Test
	void supportsBulkReadsWithoutChangingBufferLimits() {
		var cursor = new BufDataCursor();
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
}
