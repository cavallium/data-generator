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
 * A Zero-Copy implementation of {@link Buf} backed by a native {@link MemorySegment}.
 * <p>
 * This class is designed for high-performance off-heap access, particularly for RocksDB Merge Operators. It allows
 * reading native memory directly without copying it to the Java Heap.
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
		// ByteBuffer.wrap requires a heap array. We must copy.
		// Note: We could return a DirectByteBuffer via segment.asByteBuffer(),
		// but the method name implies "Heap".
		return ByteBuffer.wrap(asArray());
	}

	@Override
	public byte[] getBackingByteArray() {
		throw new UnsupportedOperationException("MemorySegmentBuf is off-heap and has no backing array.");
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
		// MemorySegments are effectively frozen views usually.
		// If we wanted to enforce it, we could wrap in a read-only segment,
		// but for high-performance merge ops, we assume it's used correctly.
		return this;
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
		if (!isMutable()) {
			throw new UnsupportedOperationException("Immutable");
		}
		Objects.checkFromIndexSize(offset, length, size);
		// Source check is done by source getters/accessors

		if (length == 0) {
			return;
		}

		if (source instanceof MemorySegmentBuf msb) {
			MemorySegment.copy(msb.segment, sourceOffset, this.segment, offset, length);
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
		if (aStartIndex < 0 || bStartIndex < 0 || length < 0) {
			return false;
		}
		if (aStartIndex + length > size) {
			return false;
		}
		if (bStartIndex + length > b.size()) {
			return false;
		}
		if (length == 0) {
			return true;
		}

		// Optimization: Native vs Native Comparison (Vectorized)
		if (b instanceof MemorySegmentBuf msb) {
			// mismatch returns -1 if equal
			long mismatch = segment.asSlice(aStartIndex, length).mismatch(msb.segment.asSlice(bStartIndex, length));
			return mismatch == -1;
		}

		// Optimization: Native vs Array
		byte[] bArr = b.asArrayStrict();
		if (bArr != null) {
			int realOffset = b.getBackingByteArrayOffset() + bStartIndex;
			long mismatch = segment
					.asSlice(aStartIndex, length)
					.mismatch(MemorySegment.ofArray(bArr).asSlice(realOffset, length));
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
		if (aStartIndex < 0 || bStartIndex < 0 || length < 0) {
			return false;
		}
		if (aStartIndex + length > size) {
			return false;
		}
		if (bStartIndex + length > b.length) {
			return false;
		}
		if (length == 0) {
			return true;
		}

		// Vectorized comparison
		long mismatch = segment
				.asSlice(aStartIndex, length)
				.mismatch(MemorySegment.ofArray(b).asSlice(bStartIndex, length));
		return mismatch == -1;
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

		if (l instanceof MemorySegmentBuf other) {
			long len = Math.min(this.size, other.size);
			long mismatch = this.segment.mismatch(other.segment);

			if (mismatch == -1) {
				// Contents match, compare sizes
				return Integer.compare(this.size, other.size);
			}

			// Mismatch found within common length?
			if (mismatch < len) {
				int a = Byte.toUnsignedInt(this.getByte((int) mismatch));
				int b = Byte.toUnsignedInt(other.getByte((int) mismatch));
				return Integer.compare(a, b);
			}

			// One is a prefix of the other
			return Integer.compare(this.size, other.size);
		}

		return super.compareTo(l);
	}
}