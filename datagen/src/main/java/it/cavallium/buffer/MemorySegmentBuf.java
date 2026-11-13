package it.cavallium.buffer;

import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeByteArrayOutputStream;
import it.cavallium.stream.SafeDataOutput;
import it.unimi.dsi.fastutil.bytes.AbstractByteList;
import it.unimi.dsi.fastutil.bytes.ByteCollection;
import it.unimi.dsi.fastutil.bytes.BytePredicate;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
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
	/**
	 * Freeze state shared with every handle derived from this one, so that {@link #freeze()} freezes the
	 * storage rather than just the handle it was called on — the contract {@link Buf#freeze()} states and
	 * {@link ByteListBuf} implements. A {@code MemorySegmentBuf} built separately from the same
	 * {@link MemorySegment} has its own state: like a raw {@code byte[]} write to a frozen
	 * {@link ByteListBuf}, it is outside what {@code freeze()} can reach.
	 */
	private final FreezeState freezeState;

	private static final class FreezeState {
		// Write-once flag, read through every handle derived from this one. Volatile so that freezing on one
		// thread and mutating through the family on another has a happens-before edge; ByteListBuf's root flag
		// is not volatile, so this backing is the stricter of the two.
		private volatile boolean frozen;
	}

	// --- Layouts for Big-Endian (Buf Contract) ---
	private static final HexFormat HEX_FORMAT = HexFormat.of().withUpperCase();
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
		this.freezeState = new FreezeState();
	}

	/**
	 * Internal constructor for slicing/resizing without re-checking size bounds repeatedly. The slice shares
	 * the parent's freeze state: freezing either handle freezes both.
	 */
	private MemorySegmentBuf(MemorySegment segment, int size, FreezeState freezeState) {
		this.segment = segment;
		this.size = size;
		this.freezeState = freezeState;
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
		return !freezeState.frozen && !segment.isReadOnly();
	}

	@Override
	public Buf freeze() {
		freezeState.frozen = true;
		return this;
	}

	/**
	 * Resize request, rejected on frozen storage before anything else: like {@link ByteListBuf}, a
	 * {@code size(currentSize)} call on frozen storage is refused rather than quietly accepted as a no-op.
	 */
	@Override
	public void size(int size) {
		ensureMutable();
		super.size(size);
	}

	@Override
	public byte set(int index, byte value) {
		ensureMutable();
		byte previous = segment.get(ValueLayout.JAVA_BYTE, index);
		segment.set(ValueLayout.JAVA_BYTE, index, value);
		return previous;
	}

	// --- Mutation requests ---------------------------------------------------------------
	//
	// The routes below are inherited from fastutil and answer a mutation by looking for something to
	// change: a removal of an absent element, a removeAll whose argument matches nothing, a retainAll that
	// keeps everything, a filter that matches nothing. On a live storage each of those is a false with no
	// edit; this backing used to answer the same way after freeze, while the heap backing — which guards
	// every one of these, and refuses even a size(currentSize) request — answered "the buffer is
	// immutable". A frozen storage refuses the request rather than quietly accepting it as a no-op, on
	// either backing, which is what Buf#freeze() states.
	//
	// A request that does find something to remove was already rejected on this storage, because a
	// memory-segment buffer has a fixed size and its iterator's removal is unsupported; the guard only
	// makes the reason the freeze instead of the fixed size.

	@Override
	public boolean remove(Object key) {
		ensureMutable();
		return super.remove(key);
	}

	@Override
	public boolean rem(byte key) {
		ensureMutable();
		return super.rem(key);
	}

	@Override
	public boolean removeAll(@NotNull Collection<?> c) {
		ensureMutable();
		return super.removeAll(c);
	}

	@Override
	public boolean removeAll(@NotNull ByteCollection c) {
		ensureMutable();
		return super.removeAll(c);
	}

	@Override
	public boolean retainAll(@NotNull Collection<?> c) {
		ensureMutable();
		return super.retainAll(c);
	}

	@Override
	public boolean retainAll(@NotNull ByteCollection c) {
		ensureMutable();
		return super.retainAll(c);
	}

	@Override
	public boolean removeIf(@NotNull Predicate<? super Byte> filter) {
		ensureMutable();
		return super.removeIf(filter);
	}

	@Override
	public boolean removeIf(@NotNull BytePredicate filter) {
		ensureMutable();
		return super.removeIf(filter);
	}

	@Override
	public boolean removeIf(@NotNull IntPredicate filter) {
		ensureMutable();
		return super.removeIf(filter);
	}

	// The structural siblings: an empty range removed, an empty array added, an empty collection added, a
	// clear of a buffer that is already empty. fastutil answers every one of them without touching the
	// storage, so a frozen memory-segment buffer let them through as a success while the heap backing refused
	// them. Same rule as above: a frozen storage does not accept a request to change it.

	@Override
	public void clear() {
		ensureMutable();
		super.clear();
	}

	@Override
	public void removeElements(int from, int to) {
		ensureMutable();
		super.removeElements(from, to);
	}

	@Override
	public void addElements(int index, byte @NotNull [] a) {
		ensureMutable();
		super.addElements(index, a);
	}

	@Override
	public void addElements(int index, byte @NotNull [] a, int offset, int length) {
		ensureMutable();
		super.addElements(index, a, offset, length);
	}

	@Override
	public boolean addAll(@NotNull Collection<? extends Byte> c) {
		ensureMutable();
		return super.addAll(c);
	}

	@Override
	public boolean addAll(int index, @NotNull Collection<? extends Byte> c) {
		ensureMutable();
		return super.addAll(index, c);
	}

	@Override
	public boolean addAll(@NotNull ByteCollection c) {
		ensureMutable();
		return super.addAll(c);
	}

	@Override
	public boolean addAll(int index, @NotNull ByteCollection c) {
		ensureMutable();
		return super.addAll(index, c);
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
		return new MemorySegmentBuf(segment.asSlice(from, to - from), to - from, freezeState);
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

	/**
	 * Bulk write. The inherited implementation checks the range before it writes, so without this override a
	 * frozen storage would answer a too-long array with a bounds error; the freeze rejection is the more
	 * informative answer, and it is what the heap backing gives, so it is checked first here as well.
	 */
	@Override
	public void setElements(int index, byte[] a, int offset, int length) {
		ensureMutable();
		super.setElements(index, a, offset, length);
	}

	@Override
	public void setElements(byte[] a) {
		ensureMutable();
		super.setElements(a);
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
		// Bounds of the content: the copy below is bounded by the segment, and a slice's segment reaches to
		// the end of the storage behind it, so without this an empty read at an index past the end answered
		// "" instead of a bad index. The heap backing and a heap view both reject it.
		Objects.checkFromIndexSize(i, length, size);
		if (length == 0) {
			return "";
		}
		// We must extract bytes to decode string
		byte[] tmp = new byte[length];
		MemorySegment.copy(segment, i, MemorySegment.ofArray(tmp), 0, length);
		return new String(tmp, charset);
	}

	/**
	 * The same upper-case hex dump the heap backing prints. {@code toString()} is what reaches a log line or a
	 * comparison failure, and this class used to inherit fastutil's list dump, so the same three bytes printed
	 * as {@code 010203} from one backing and {@code "[1, 2, 3]"} from the other.
	 */
	@Override
	public String toString() {
		return HEX_FORMAT.formatHex(asArray());
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
