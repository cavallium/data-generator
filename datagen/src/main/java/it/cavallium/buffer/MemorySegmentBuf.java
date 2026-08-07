package it.cavallium.buffer;

import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeByteArrayOutputStream;
import it.cavallium.stream.SafeDataOutput;
import it.unimi.dsi.fastutil.bytes.AbstractByteList;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A zero-copy implementation of {@link Buf} backed by an exact
 * {@link MemorySegment} view.
 * <p>
 * This class does not own or extend the lifetime of the segment. The caller
 * remains responsible for keeping its scope alive for every access, including
 * access through slices, byte buffers, and frozen views.
 */
public class MemorySegmentBuf extends AbstractByteList implements Buf {

	private final MemorySegment segment;
	private final int size;

	// --- Layouts for Big-Endian (Buf Contract) ---
	private static final ValueLayout.OfShort SHORT_BE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
	private static final ValueLayout.OfInt INT_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
	private static final ValueLayout.OfLong LONG_BE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
	private static final ValueLayout.OfFloat FLOAT_BE = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
	private static final ValueLayout.OfDouble DOUBLE_BE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

	// --- Layouts for Little-Endian (Optimized LE methods) ---
	private static final ValueLayout.OfInt INT_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	// (Add others if Buf adds getLongLE, etc.)

	public MemorySegmentBuf(MemorySegment segment) {
		long byteSize = segment.byteSize();
		if (byteSize > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("MemorySegment too large for Buf: " + byteSize);
		}
		this.segment = segment;
		this.size = (int) byteSize;
	}

	/**
	 * Internal constructor for slicing/resizing without re-checking size bounds repeatedly
	 */
	private MemorySegmentBuf(MemorySegment segment, int size) {
		this.segment = segment;
		this.size = size;
	}

	// --- Core List Methods ---

	@Override
	public int size() {
		return size;
	}

	@Override
	public byte getByte(int index) {
		// FFM performs bounds checks, but we trust the user/FFM to throw IOOBE
		return segment.get(ValueLayout.JAVA_BYTE, index);
	}

	// --- Optimized Primitive Accessors (Zero-Copy) ---

	@Override
	public short getShort(int i) {
		return segment.get(SHORT_BE, i);
	}

	@Override
	public int getInt(int i) {
		return segment.get(INT_BE, i);
	}

	@Override
	public int getIntLE(int i) {
		return segment.get(INT_LE, i);
	}

	@Override
	public long getLong(int i) {
		return segment.get(LONG_BE, i);
	}

	@Override
	public float getFloat(int i) {
		return segment.get(FLOAT_BE, i);
	}

	@Override
	public double getDouble(int i) {
		return segment.get(DOUBLE_BE, i);
	}

	// --- Array / Heap Interop ---

	@Override
	public byte @NotNull [] asArray() {
		// Must copy to heap
		return segment.toArray(ValueLayout.JAVA_BYTE);
	}

	@Override
	public byte @Nullable [] asArrayStrict() {
		// Strictly adhere to contract: if it's not a byte[], return null.
		// This forces calling code to handle off-heap logic or explicitly copy.
		return null;
	}

	@Override
	public byte[] asUnboundedArray() {
		return asArray();
	}

	@Override
	public byte @Nullable [] asUnboundedArrayStrict() {
		return null;
	}

	@Override
	public ByteBuffer asHeapByteBuffer() {
		return segment.isNative() ? ByteBuffer.wrap(asArray()) : segment.asByteBuffer();
	}

	@Override
	public MemorySegment asMemorySegment() {
		return segment;
	}

	@Override
	public MemorySegment asMemorySegmentStrict() {
		return segment;
	}

	@Override
	public ByteBuffer asByteBuffer() {
		return segment.asByteBuffer();
	}

	@Override
	public byte[] getBackingByteArray() {
		throw new UnsupportedOperationException("MemorySegmentBuf is off-heap and has no backing array.");
	}

	@Override
	public byte[] getBackingByteArrayStrict() {
		return null;
	}

	@Override
	public int getBackingByteArrayOffset() {
		return 0;
	}

	@Override
	public int getBackingByteArrayLength() {
		return size;
	}

	@Override
	public int getBackingByteArrayFrom() {
		return 0;
	}

	@Override
	public int getBackingByteArrayTo() {
		return size;
	}

	// --- Mutability ---

	@Override
	public boolean isMutable() {
		// RocksDB memory passed to MergeOperators is typically const/read-only.
		return !segment.isReadOnly();
	}

	@Override
	public Buf freeze() {
		return segment.isReadOnly() ? this : new MemorySegmentBuf(segment.asReadOnly(), size);
	}

	@Override
	public byte set(int index, byte value) {
		ensureMutable();
		byte previous = segment.get(ValueLayout.JAVA_BYTE, index);
		segment.set(ValueLayout.JAVA_BYTE, index, value);
		return previous;
	}

	// --- Slicing & Copying ---

	@Override
	public Buf subList(int from, int to) {
		// Fast path check before delegation
		if (from == 0 && to == size) {
			return this;
		}
		return subListForced(from, to);
	}

	@Override
	public Buf subListForced(int from, int to) {
		Objects.checkFromToIndex(from, to, size);
		return new MemorySegmentBuf(segment.asSlice(from, to - from), to - from);
	}

	@Override
	public Buf copyOfRange(int from, int to) {
		Objects.checkFromToIndex(from, to, size);
		int len = to - from;
		if (len == 0) {
			return ByteListBuf.of();
		}

		byte[] copy = new byte[len];
		MemorySegment.copy(segment, from, MemorySegment.ofArray(copy), 0, len);
		return ByteListBuf.wrap(copy);
	}

	@Override
	public Buf copy() {
		return ByteListBuf.wrap(asArray());
	}

	// --- IO & Data Transfer ---

	@Override
	public void setBytesFromBuf(int offset, Buf source, int sourceOffset, int length) {
		ensureMutable();
		Objects.checkFromIndexSize(offset, length, size);
		Objects.checkFromIndexSize(sourceOffset, length, source.size());

		if (length == 0) {
			return;
		}

		MemorySegment sourceSegment = source.asMemorySegmentStrict();
		if (sourceSegment != null) {
			MemorySegment.copy(sourceSegment, sourceOffset, this.segment, offset, length);
		} else {
			byte[] srcArr = source.asArrayStrict();
			if (srcArr != null) {
				// Heap -> Native copy (Fast)
				int realSrcOff = source.getBackingByteArrayOffset() + sourceOffset;
				MemorySegment.copy(MemorySegment.ofArray(srcArr), realSrcOff, this.segment, offset, length);
			} else {
				// Buf -> Native copy (Fallback)
				// If source is slow, we might want to copy to temp array first for JNI bulk copy efficiency,
				// but for small writes, loop is fine.
				for (int i = 0; i < length; i++) {
					segment.set(ValueLayout.JAVA_BYTE, offset + i, source.getByte(sourceOffset + i));
				}
			}
		}
	}

	private void ensureMutable() {
		if (!isMutable()) {
			throw new UnsupportedOperationException("The buffer is immutable");
		}
	}

	@Override
	public SafeByteArrayInputStream binaryInputStream() {
		// Must copy to heap to use ByteArrayInputStream
		return new SafeByteArrayInputStream(asArray());
	}

	@Override
	public void writeTo(SafeDataOutput dataOutput) {
		if (size == 0) {
			return;
		}

		// Chunked copy to avoid massive heap allocations for huge segments
		final int CHUNK_SIZE = 64 * 1024; // 64KB chunks
		byte[] buffer = new byte[Math.min(size, CHUNK_SIZE)];

		int offset = 0;
		int remaining = size;

		MemorySegment heapSeg = MemorySegment.ofArray(buffer);

		while (remaining > 0) {
			int toRead = Math.min(remaining, buffer.length);
			MemorySegment.copy(segment, offset, heapSeg, 0, toRead);
			dataOutput.write(buffer, 0, toRead);
			offset += toRead;
			remaining -= toRead;
		}
	}

	@Override
	public SafeByteArrayOutputStream binaryOutputStream(int from, int to) {
		throw new UnsupportedOperationException("Cannot open OutputStream on read-only native memory");
	}

	// --- Comparison & Strings ---

	@Override
	public boolean equals(int aStartIndex, Buf b, int bStartIndex, int length) {
		if (!isValidRange(aStartIndex, length, size)
				|| !isValidRange(bStartIndex, length, b.size())) return false;
		if (length == 0) {
			return true;
		}

		MemorySegment otherSegment = b.asMemorySegmentStrict();
		if (otherSegment != null) {
			long mismatch = segment.asSlice(aStartIndex, length)
					.mismatch(otherSegment.asSlice(bStartIndex, length));
			return mismatch == -1;
		}

		// Fallback: Manual Loop (Fix for AbstractByteList compilation error)
		for (int i = 0; i < length; i++) {
			if (segment.get(ValueLayout.JAVA_BYTE, aStartIndex + i) != b.getByte(bStartIndex + i)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean equals(int aStartIndex, byte[] b, int bStartIndex, int length) {
		if (!isValidRange(aStartIndex, length, size)
				|| !isValidRange(bStartIndex, length, b.length)) return false;
		if (length == 0) {
			return true;
		}

		// Vectorized comparison
		long mismatch = segment
				.asSlice(aStartIndex, length)
				.mismatch(MemorySegment.ofArray(b).asSlice(bStartIndex, length));
		return mismatch == -1;
	}

	private static boolean isValidRange(int offset, int length, int size) {
		return offset >= 0 && length >= 0 && (long) offset + length <= size;
	}

	@Override
	public String getString(int i, int length, Charset charset) {
		if (length == 0) {
			return "";
		}
		// We must extract bytes to decode string
		byte[] tmp = new byte[length];
		MemorySegment.copy(segment, i, MemorySegment.ofArray(tmp), 0, length);
		return new String(tmp, charset);
	}

	@Override
	public String toString(Charset charset) {
		return getString(0, size, charset);
	}

	/**
	 * Vectorized {@code compareTo}. Finds the first differing byte using SIMD, then compares that specific byte.
	 */
	@Override
	public int compareTo(List<? extends Byte> l) {
		if (l == this) {
			return 0;
		}

		if (l instanceof Buf other) {
			int sizeComparison = Integer.compare(this.size, other.size());
			if (sizeComparison != 0) return sizeComparison;

			if (!(other instanceof MemorySegmentBuf memorySegmentBuf)) {
				for (int index = 0; index < size; index++) {
					int comparison = Integer.compare(Byte.toUnsignedInt(getByte(index)),
							Byte.toUnsignedInt(other.getByte(index)));
					if (comparison != 0) return comparison;
				}
				return 0;
			}

			long len = size;
			long mismatch = this.segment.mismatch(memorySegmentBuf.segment);

			if (mismatch == -1) {
				return 0;
			}

			if (mismatch < len) {
				int a = Byte.toUnsignedInt(this.getByte((int) mismatch));
				int b = Byte.toUnsignedInt(memorySegmentBuf.getByte((int) mismatch));
				return Integer.compare(a, b);
			}
			return 0;
		}

		return super.compareTo(l);
	}
}
