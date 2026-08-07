package it.cavallium.buffer;

import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.stream.SafeInputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/** Shared direct-storage implementation for one-shot and reusable {@link Buf} inputs. */
abstract class BufDataInputCore extends SafeInputStream implements RandomAccessDataInput {

	private static final ValueLayout.OfShort SHORT_BE =
			ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
	private static final ValueLayout.OfChar CHAR_BE =
			ValueLayout.JAVA_CHAR_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
	private static final ValueLayout.OfInt INT_BE =
			ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
	private static final ValueLayout.OfLong LONG_BE =
			ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
	private static final ValueLayout.OfFloat FLOAT_BE =
			ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
	private static final ValueLayout.OfDouble DOUBLE_BE =
			ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
	private static final VarHandle HEAP_SHORT_BE =
			MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
	private static final VarHandle HEAP_CHAR_BE =
			MethodHandles.byteArrayViewVarHandle(char[].class, ByteOrder.BIG_ENDIAN);
	private static final VarHandle HEAP_INT_BE =
			MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
	private static final VarHandle HEAP_LONG_BE =
			MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
	private static final VarHandle HEAP_FLOAT_BE =
			MethodHandles.byteArrayViewVarHandle(float[].class, ByteOrder.BIG_ENDIAN);
	private static final VarHandle HEAP_DOUBLE_BE =
			MethodHandles.byteArrayViewVarHandle(double[].class, ByteOrder.BIG_ENDIAN);

	private boolean bound;
	private final DecodeBudget ownedBudget;
	private DecodeBudget activeBudget;
	private Buf source;
	private byte[] heap;
	private MemorySegment segment;
	private Buf fallback;
	private StorageAccess activeStorage;
	private final StorageAccess heapStorage = new HeapStorage();
	private final StorageAccess segmentStorage = new SegmentStorage();
	private final StorageAccess fallbackStorage = new FallbackStorage();
	private int storageOffset;
	private int start;
	private int position;
	private int limit;
	private int mark;
	private byte[] stringScratch = new byte[0];

	/** Creates a cursor that may only borrow storage and budget from a bounded parent input. */
	protected BufDataInputCore() {
		this.ownedBudget = null;
	}

	protected BufDataInputCore(DecodeLimits limits) {
		this(new DecodeBudget(Objects.requireNonNull(limits, "limits")));
	}

	protected BufDataInputCore(DecodeBudget budget) {
		this.ownedBudget = Objects.requireNonNull(budget, "budget");
	}

	protected final void bindSource(Buf source, int offset, int length) {
		Objects.requireNonNull(source, "source");
		byte[] sourceHeap = source.getBackingByteArrayStrict();
		if (sourceHeap != null) {
			bindHeapSource(source, sourceHeap, source.getBackingByteArrayOffset(), offset, length);
			return;
		}
		MemorySegment sourceSegment = source.asMemorySegmentStrict();
		if (sourceSegment != null) {
			bindSegmentSource(source, sourceSegment, offset, length);
			return;
		}
		bindFallbackSource(source, offset, length);
	}

	final void bindHeapSource(Buf source, byte[] sourceHeap, int sourceHeapOffset, int offset, int length) {
		bindResolved(source, sourceHeap, null, null, heapStorage, sourceHeapOffset, offset, length);
	}

	final void bindSegmentSource(Buf source, MemorySegment sourceSegment, int offset, int length) {
		bindResolved(source, null, sourceSegment, null, segmentStorage, 0, offset, length);
	}

	final void bindFallbackSource(Buf source, int offset, int length) {
		bindResolved(source, null, null, source, fallbackStorage, 0, offset, length);
	}

	private void bindResolved(Buf source,
			byte[] sourceHeap,
			MemorySegment sourceSegment,
			Buf sourceFallback,
			StorageAccess sourceStorage,
			int sourceHeapOffset,
			int offset,
			int length) {
		if (bound) {
			throw new IllegalStateException("Input is already bound");
		}
		if (ownedBudget == null) {
			throw new IllegalStateException("Borrowed cursor must be bound to a parent input region");
		}
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(sourceStorage, "sourceStorage");
		Objects.checkFromIndexSize(offset, length, source.size());

		this.source = source;
		activeBudget = ownedBudget;
		heap = sourceHeap;
		segment = sourceSegment;
		fallback = sourceFallback;
		activeStorage = sourceStorage;
		storageOffset = sourceHeapOffset;
		start = offset;
		position = offset;
		limit = offset + length;
		mark = offset;
		bound = true;
	}

	final void bindResolvedFrom(BufDataInputCore parent, int offset, int length, boolean validateRegion) {
		if (bound) {
			throw new IllegalStateException("Input is already bound");
		}
		parent.ensureBound();
		if (validateRegion) {
			Objects.checkFromIndexSize(offset, length, parent.limit - parent.start);
		}
		this.source = parent.source;
		this.heap = parent.heap;
		this.segment = parent.segment;
		this.fallback = parent.fallback;
		this.activeStorage = parent.activeStorage == parent.heapStorage ? heapStorage
				: parent.activeStorage == parent.segmentStorage ? segmentStorage : fallbackStorage;
		this.activeBudget = parent.activeBudget;
		this.storageOffset = parent.storageOffset;
		this.start = parent.start + offset;
		this.position = this.start;
		this.limit = this.start + length;
		this.mark = this.start;
		this.bound = true;
	}

	protected final void unbindSource() {
		bound = false;
		activeBudget = null;
		source = null;
		heap = null;
		segment = null;
		fallback = null;
		activeStorage = null;
		storageOffset = 0;
		start = 0;
		position = 0;
		limit = 0;
		mark = 0;
	}

	protected final boolean isSourceBound() {
		return bound;
	}

	protected StorageAccess storageAccess() {
		return activeStorage;
	}

	protected final StorageAccess heapStorageAccess() {
		return heapStorage;
	}

	protected final StorageAccess segmentStorageAccess() {
		return segmentStorage;
	}

	protected final StorageAccess fallbackStorageAccess() {
		return fallbackStorage;
	}

	protected final boolean isHeapStorage() {
		return activeStorage == heapStorage;
	}

	protected final boolean isSegmentStorage() {
		return activeStorage == segmentStorage;
	}

	protected final boolean isFallbackStorage() {
		return activeStorage == fallbackStorage;
	}

	protected final int remainingBytes() {
		ensureBound();
		return limit - position;
	}

	@Override
	public final DecodeBudget decodeBudget() {
		ensureBound();
		return activeBudget;
	}

	@Override
	public final long remainingBytesIfKnown() {
		return remainingBytes();
	}

	@Override
	public final int position() {
		ensureBound();
		return position - start;
	}

	@Override
	public final int length() {
		ensureBound();
		return limit - start;
	}

	@Override
	public final void position(int newPosition) {
		ensureBound();
		if (newPosition < 0 || newPosition > limit - start) {
			throw new IndexOutOfBoundsException("Position " + newPosition + " outside [0, "
					+ (limit - start) + "]");
		}
		position = start + newPosition;
	}

	@Override
	public final int reserve(int byteLength) {
		return take(byteLength) - start;
	}

	@Override
	public final boolean getBooleanAt(int offset) {
		return getByteAt(offset) != 0;
	}

	@Override
	public final byte getByteAt(int offset) {
		return getByte(start + offset);
	}

	@Override
	public final int getUnsignedByteAt(int offset) {
		return Byte.toUnsignedInt(getByteAt(offset));
	}

	@Override
	public final short getShortAt(int offset) {
		return getShort(start + offset);
	}

	@Override
	public final int getUnsignedShortAt(int offset) {
		return Short.toUnsignedInt(getShortAt(offset));
	}

	@Override
	public final char getCharAt(int offset) {
		return storageAccess().getChar(start + offset);
	}

	@Override
	public final int getIntAt(int offset) {
		return getInt(start + offset);
	}

	@Override
	public final long getLongAt(int offset) {
		return getLong(start + offset);
	}

	@Override
	public final long getInt52At(int offset) {
		return getInt52(start + offset);
	}

	@Override
	public final float getFloatAt(int offset) {
		return storageAccess().getFloat(start + offset);
	}

	@Override
	public final double getDoubleAt(int offset) {
		return storageAccess().getDouble(start + offset);
	}

	@Override
	public final void readBooleans(boolean[] destination, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, destination.length);
		int index = take(length);
		copyBooleans(index, destination, offset, length);
	}

	@Override
	public final void readBytes(byte[] destination, int offset, int length) {
		readFully(destination, offset, length);
	}

	@Override
	public final void readShorts(short[] destination, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, destination.length);
		int index = take(arrayByteLength(length, Short.BYTES));
		copyShorts(index, destination, offset, length);
	}

	@Override
	public final void readChars(char[] destination, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, destination.length);
		int index = take(arrayByteLength(length, Character.BYTES));
		copyChars(index, destination, offset, length);
	}

	@Override
	public final void readInts(int[] destination, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, destination.length);
		int index = take(arrayByteLength(length, Integer.BYTES));
		copyInts(index, destination, offset, length);
	}

	@Override
	public final void readLongs(long[] destination, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, destination.length);
		int index = take(arrayByteLength(length, Long.BYTES));
		copyLongs(index, destination, offset, length);
	}

	@Override
	public final void readFloats(float[] destination, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, destination.length);
		int index = take(arrayByteLength(length, Float.BYTES));
		copyFloats(index, destination, offset, length);
	}

	@Override
	public final void readDoubles(double[] destination, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, destination.length);
		int index = take(arrayByteLength(length, Double.BYTES));
		copyDoubles(index, destination, offset, length);
	}

	@Override
	public final boolean[] readBooleanArray(int length) {
		requireAvailable(length);
		activeBudget.claimArrayElements(length);
		boolean[] result = new boolean[length];
		int index = reserveAbsolute(length);
		copyBooleans(index, result, 0, length);
		return result;
	}

	@Override
	public final byte[] readByteArray(int length) {
		requireAvailable(length);
		activeBudget.claimArrayElements(length);
		byte[] result = new byte[length];
		int index = reserveAbsolute(length);
		copyToArray(index, result, 0, length);
		return result;
	}

	@Override
	public final short[] readShortArray(int length) {
		int byteLength = arrayByteLength(length, Short.BYTES);
		requireAvailable(byteLength);
		activeBudget.claimArrayElements(length);
		short[] result = new short[length];
		int index = reserveAbsolute(byteLength);
		copyShorts(index, result, 0, length);
		return result;
	}

	@Override
	public final char[] readCharArray(int length) {
		int byteLength = arrayByteLength(length, Character.BYTES);
		requireAvailable(byteLength);
		activeBudget.claimArrayElements(length);
		char[] result = new char[length];
		int index = reserveAbsolute(byteLength);
		copyChars(index, result, 0, length);
		return result;
	}

	@Override
	public final int[] readIntArray(int length) {
		int byteLength = arrayByteLength(length, Integer.BYTES);
		requireAvailable(byteLength);
		activeBudget.claimArrayElements(length);
		int[] result = new int[length];
		int index = reserveAbsolute(byteLength);
		copyInts(index, result, 0, length);
		return result;
	}

	@Override
	public final long[] readLongArray(int length) {
		int byteLength = arrayByteLength(length, Long.BYTES);
		requireAvailable(byteLength);
		activeBudget.claimArrayElements(length);
		long[] result = new long[length];
		int index = reserveAbsolute(byteLength);
		copyLongs(index, result, 0, length);
		return result;
	}

	@Override
	public final float[] readFloatArray(int length) {
		int byteLength = arrayByteLength(length, Float.BYTES);
		requireAvailable(byteLength);
		activeBudget.claimArrayElements(length);
		float[] result = new float[length];
		int index = reserveAbsolute(byteLength);
		copyFloats(index, result, 0, length);
		return result;
	}

	@Override
	public final double[] readDoubleArray(int length) {
		int byteLength = arrayByteLength(length, Double.BYTES);
		requireAvailable(byteLength);
		activeBudget.claimArrayElements(length);
		double[] result = new double[length];
		int index = reserveAbsolute(byteLength);
		copyDoubles(index, result, 0, length);
		return result;
	}

	@Override
	public final void skipExact(int length) {
		reserve(length);
	}

	@Override
	public final void bindRegion(BufDataCursor target, int offset, int length) {
		ensureBound();
		Objects.requireNonNull(target, "target");
		target.bindResolvedFrom(this, offset, length, true);
		target.markBound();
	}

	@Override
	public final void bindReservedRegion(BufDataCursor target, int offset, int length) {
		ensureBound();
		Objects.requireNonNull(target, "target");
		target.bindResolvedFrom(this, offset, length, false);
		target.markBound();
	}

	@Override
	public final byte[] directHeapArray() {
		ensureBound();
		return isHeapStorage() ? heap : null;
	}

	@Override
	public final MemorySegment directMemorySegment() {
		ensureBound();
		return isSegmentStorage() ? segment : null;
	}

	@Override
	public final long directStorageOffset(int relativeOffset) {
		ensureBound();
		if (relativeOffset < 0 || relativeOffset > limit - start) {
			throw new IndexOutOfBoundsException("Offset " + relativeOffset + " outside [0, "
					+ (limit - start) + "]");
		}
		if (isHeapStorage()) return (long) storageOffset + start + relativeOffset;
		if (isSegmentStorage()) return start + relativeOffset;
		throw new IllegalStateException("Fallback storage has no direct offset");
	}

	@Override
	public final int read() {
		ensureBound();
		if (position == limit) {
			return -1;
		}
		return Byte.toUnsignedInt(getByte(position++));
	}

	@Override
	public final int read(byte @NotNull [] bytes) {
		return read(bytes, 0, bytes.length);
	}

	@Override
	public final int read(byte @NotNull [] bytes, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, bytes.length);
		ensureBound();
		if (length == 0) {
			return 0;
		}
		int available = limit - position;
		if (available == 0) {
			return -1;
		}
		int count = Math.min(length, available);
		copyToArray(position, bytes, offset, count);
		position += count;
		return count;
	}

	@Override
	public final void readFully(byte @NotNull [] bytes) {
		readFully(bytes, 0, bytes.length);
	}

	@Override
	public final void readFully(byte @NotNull [] bytes, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, bytes.length);
		int index = take(length);
		copyToArray(index, bytes, offset, length);
	}

	@Override
	public final void readFully(ByteBuffer destination) {
		readFully(destination, destination.remaining());
	}

	@Override
	public final void readFully(ByteBuffer destination, int length) {
		Objects.requireNonNull(destination, "destination");
		if (length < 0 || length > destination.remaining()) {
			throw new IndexOutOfBoundsException();
		}
		requireAvailable(length);
		if (length == 0) {
			return;
		}
		if (destination.isReadOnly()) throw new ReadOnlyBufferException();
		int index = take(length);
		storageAccess().copyToBuffer(index, destination, length);
	}

	@Override
	public final byte[] readAllBytes() {
		int length = remainingBytes();
		activeBudget.claimPayloadBytes(length);
		byte[] result = new byte[length];
		readFully(result);
		return result;
	}

	@Override
	public final byte[] readNBytes(int length) {
		if (length < 0) {
			throw new IllegalArgumentException("len < 0");
		}
		int count = Math.min(length, remainingBytes());
		activeBudget.claimPayloadBytes(count);
		byte[] result = new byte[count];
		readFully(result);
		return result;
	}

	@Override
	public final int readNBytes(byte[] bytes, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, bytes.length);
		int count = Math.min(length, remainingBytes());
		if (count != 0) {
			readFully(bytes, offset, count);
		}
		return count;
	}

	@Override
	public final long skip(long count) {
		ensureBound();
		if (count <= 0) {
			return 0;
		}
		int skipped = (int) Math.min(count, limit - position);
		position += skipped;
		return skipped;
	}

	@Override
	public final int skipBytes(int count) {
		return (int) skip(count);
	}

	@Override
	public final int available() {
		return remainingBytes();
	}

	@Override
	public final boolean readBoolean() {
		return readByte() != 0;
	}

	@Override
	public final byte readByte() {
		return getByte(take(Byte.BYTES));
	}

	@Override
	public final int readUnsignedByte() {
		return Byte.toUnsignedInt(readByte());
	}

	@Override
	public final short readShort() {
		return getShort(take(Short.BYTES));
	}

	@Override
	public final int readUnsignedShort() {
		return Short.toUnsignedInt(readShort());
	}

	@Override
	public final char readChar() {
		return (char) readUnsignedShort();
	}

	@Override
	public final int readInt() {
		return getInt(take(Integer.BYTES));
	}

	@Override
	public final long readLong() {
		return getLong(take(Long.BYTES));
	}

	@Override
	public final long readInt52() {
		int index = take(7);
		return ((long) getByte(index) & 0x0fL) << 48
				| ((long) getByte(index + 1) & 0xffL) << 40
				| ((long) getByte(index + 2) & 0xffL) << 32
				| ((long) getByte(index + 3) & 0xffL) << 24
				| ((long) getByte(index + 4) & 0xffL) << 16
				| ((long) getByte(index + 5) & 0xffL) << 8
				| ((long) getByte(index + 6) & 0xffL);
	}

	@Override
	public final float readFloat() {
		return Float.intBitsToFloat(readInt());
	}

	@Override
	public final double readDouble() {
		return Double.longBitsToDouble(readLong());
	}

	@Override
	@Deprecated
	public final String readLine() {
		throw new UnsupportedOperationException();
	}

	@Override
	public final @NotNull String readString(int length, Charset charset) {
		Objects.requireNonNull(charset, "charset");
		requireAvailable(length);
		activeBudget.claimPayloadBytes(length);
		int index = take(length);
		return storageAccess().readString(index, length, charset);
	}

	@Override
	public final boolean markSupported() {
		return true;
	}

	@Override
	public final void mark(int readLimit) {
		if (readLimit < 0) {
			throw new IllegalArgumentException();
		}
		ensureBound();
		mark = position;
	}

	@Override
	public final void reset() {
		ensureBound();
		position = mark;
	}

	private void requireAvailable(int length) {
		ensureBound();
		if (length < 0 || length > limit - position) {
			throw new MalformedDataException(length < 0
					? "Negative byte length: " + length
					: "Truncated input: need " + length + " bytes, have " + (limit - position));
		}
	}

	private int take(int length) {
		requireAvailable(length);
		int index = position;
		position += length;
		return index;
	}

	private int reserveAbsolute(int length) {
		return start + reserve(length);
	}

	private byte getByte(int index) {
		return storageAccess().getByte(index);
	}

	private short getShort(int index) {
		return storageAccess().getShort(index);
	}

	private int getInt(int index) {
		return storageAccess().getInt(index);
	}

	private long getLong(int index) {
		return storageAccess().getLong(index);
	}

	private long getInt52(int index) {
		return ((long) getByte(index) & 0x0fL) << 48
				| ((long) getByte(index + 1) & 0xffL) << 40
				| ((long) getByte(index + 2) & 0xffL) << 32
				| ((long) getByte(index + 3) & 0xffL) << 24
				| ((long) getByte(index + 4) & 0xffL) << 16
				| ((long) getByte(index + 5) & 0xffL) << 8
				| ((long) getByte(index + 6) & 0xffL);
	}

	private void copyBooleans(int index, boolean[] destination, int offset, int length) {
		storageAccess().copyBooleans(index, destination, offset, length);
	}

	private void copyShorts(int index, short[] destination, int offset, int length) {
		storageAccess().copyShorts(index, destination, offset, length);
	}

	private void copyChars(int index, char[] destination, int offset, int length) {
		storageAccess().copyChars(index, destination, offset, length);
	}

	private void copyInts(int index, int[] destination, int offset, int length) {
		storageAccess().copyInts(index, destination, offset, length);
	}

	private void copyLongs(int index, long[] destination, int offset, int length) {
		storageAccess().copyLongs(index, destination, offset, length);
	}

	private void copyFloats(int index, float[] destination, int offset, int length) {
		storageAccess().copyFloats(index, destination, offset, length);
	}

	private void copyDoubles(int index, double[] destination, int offset, int length) {
		storageAccess().copyDoubles(index, destination, offset, length);
	}

	private void copyToArray(int index, byte[] destination, int offset, int length) {
		storageAccess().copyToArray(index, destination, offset, length);
	}

	/**
	 * Storage operations selected when the cursor is bound. Generic cursors perform one stable
	 * interface dispatch per operation; storage-specific cursor subclasses override
	 * {@link #storageAccess()} with a constant implementation so C2 can inline the concrete heap,
	 * segment, or fallback operation without testing the storage kind on every field.
	 */
	protected interface StorageAccess {

		byte getByte(int index);

		short getShort(int index);

		char getChar(int index);

		int getInt(int index);

		long getLong(int index);

		float getFloat(int index);

		double getDouble(int index);

		void copyBooleans(int index, boolean[] destination, int offset, int length);

		void copyShorts(int index, short[] destination, int offset, int length);

		void copyChars(int index, char[] destination, int offset, int length);

		void copyInts(int index, int[] destination, int offset, int length);

		void copyLongs(int index, long[] destination, int offset, int length);

		void copyFloats(int index, float[] destination, int offset, int length);

		void copyDoubles(int index, double[] destination, int offset, int length);

		void copyToArray(int index, byte[] destination, int offset, int length);

		void copyToBuffer(int index, ByteBuffer destination, int length);

		String readString(int index, int length, Charset charset);
	}

	private final class HeapStorage implements StorageAccess {

		@Override
		public byte getByte(int index) {
			return heap[storageOffset + index];
		}

		@Override
		public short getShort(int index) {
			return (short) HEAP_SHORT_BE.get(heap, storageOffset + index);
		}

		@Override
		public char getChar(int index) {
			return (char) HEAP_CHAR_BE.get(heap, storageOffset + index);
		}

		@Override
		public int getInt(int index) {
			return (int) HEAP_INT_BE.get(heap, storageOffset + index);
		}

		@Override
		public long getLong(int index) {
			return (long) HEAP_LONG_BE.get(heap, storageOffset + index);
		}

		@Override
		public float getFloat(int index) {
			return (float) HEAP_FLOAT_BE.get(heap, storageOffset + index);
		}

		@Override
		public double getDouble(int index) {
			return (double) HEAP_DOUBLE_BE.get(heap, storageOffset + index);
		}

		@Override
		public void copyBooleans(int index, boolean[] destination, int offset, int length) {
			int source = storageOffset + index;
			for (int i = 0; i < length; i++) {
				destination[offset + i] = heap[source + i] != 0;
			}
		}

		@Override
		public void copyShorts(int index, short[] destination, int offset, int length) {
			int source = storageOffset + index;
			for (int i = 0; i < length; i++, source += Short.BYTES) {
				destination[offset + i] = (short) HEAP_SHORT_BE.get(heap, source);
			}
		}

		@Override
		public void copyChars(int index, char[] destination, int offset, int length) {
			int source = storageOffset + index;
			for (int i = 0; i < length; i++, source += Character.BYTES) {
				destination[offset + i] = (char) HEAP_CHAR_BE.get(heap, source);
			}
		}

		@Override
		public void copyInts(int index, int[] destination, int offset, int length) {
			int source = storageOffset + index;
			for (int i = 0; i < length; i++, source += Integer.BYTES) {
				destination[offset + i] = (int) HEAP_INT_BE.get(heap, source);
			}
		}

		@Override
		public void copyLongs(int index, long[] destination, int offset, int length) {
			int source = storageOffset + index;
			for (int i = 0; i < length; i++, source += Long.BYTES) {
				destination[offset + i] = (long) HEAP_LONG_BE.get(heap, source);
			}
		}

		@Override
		public void copyFloats(int index, float[] destination, int offset, int length) {
			int source = storageOffset + index;
			for (int i = 0; i < length; i++, source += Float.BYTES) {
				destination[offset + i] = (float) HEAP_FLOAT_BE.get(heap, source);
			}
		}

		@Override
		public void copyDoubles(int index, double[] destination, int offset, int length) {
			int source = storageOffset + index;
			for (int i = 0; i < length; i++, source += Double.BYTES) {
				destination[offset + i] = (double) HEAP_DOUBLE_BE.get(heap, source);
			}
		}

		@Override
		public void copyToArray(int index, byte[] destination, int offset, int length) {
			System.arraycopy(heap, storageOffset + index, destination, offset, length);
		}

		@Override
		public void copyToBuffer(int index, ByteBuffer destination, int length) {
			destination.put(heap, storageOffset + index, length);
		}

		@Override
		public String readString(int index, int length, Charset charset) {
			return new String(heap, storageOffset + index, length, charset);
		}
	}

	private final class SegmentStorage implements StorageAccess {

		@Override
		public byte getByte(int index) {
			return segment.get(ValueLayout.JAVA_BYTE, index);
		}

		@Override
		public short getShort(int index) {
			return segment.get(SHORT_BE, index);
		}

		@Override
		public char getChar(int index) {
			return segment.get(CHAR_BE, index);
		}

		@Override
		public int getInt(int index) {
			return segment.get(INT_BE, index);
		}

		@Override
		public long getLong(int index) {
			return segment.get(LONG_BE, index);
		}

		@Override
		public float getFloat(int index) {
			return segment.get(FLOAT_BE, index);
		}

		@Override
		public double getDouble(int index) {
			return segment.get(DOUBLE_BE, index);
		}

		@Override
		public void copyBooleans(int index, boolean[] destination, int offset, int length) {
			for (int i = 0; i < length; i++) {
				destination[offset + i] = segment.get(ValueLayout.JAVA_BYTE, index + i) != 0;
			}
		}

		@Override
		public void copyShorts(int index, short[] destination, int offset, int length) {
			MemorySegment.copy(segment, SHORT_BE, index, destination, offset, length);
		}

		@Override
		public void copyChars(int index, char[] destination, int offset, int length) {
			MemorySegment.copy(segment, CHAR_BE, index, destination, offset, length);
		}

		@Override
		public void copyInts(int index, int[] destination, int offset, int length) {
			MemorySegment.copy(segment, INT_BE, index, destination, offset, length);
		}

		@Override
		public void copyLongs(int index, long[] destination, int offset, int length) {
			MemorySegment.copy(segment, LONG_BE, index, destination, offset, length);
		}

		@Override
		public void copyFloats(int index, float[] destination, int offset, int length) {
			MemorySegment.copy(segment, FLOAT_BE, index, destination, offset, length);
		}

		@Override
		public void copyDoubles(int index, double[] destination, int offset, int length) {
			MemorySegment.copy(segment, DOUBLE_BE, index, destination, offset, length);
		}

		@Override
		public void copyToArray(int index, byte[] destination, int offset, int length) {
			MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, index, destination, offset, length);
		}

		@Override
		public void copyToBuffer(int index, ByteBuffer destination, int length) {
			MemorySegment target = MemorySegment.ofBuffer(destination);
			MemorySegment.copy(segment, index, target, 0, length);
			destination.position(destination.position() + length);
		}

		@Override
		public String readString(int index, int length, Charset charset) {
			if (length == 0) {
				return "";
			}
			byte[] scratch = ensureStringScratch(length);
			copyToArray(index, scratch, 0, length);
			return new String(scratch, 0, length, charset);
		}
	}

	private final class FallbackStorage implements StorageAccess {

		@Override
		public byte getByte(int index) {
			return fallback.getByte(index);
		}

		@Override
		public short getShort(int index) {
			return fallback.getShort(index);
		}

		@Override
		public char getChar(int index) {
			return fallback.getChar(index);
		}

		@Override
		public int getInt(int index) {
			return fallback.getInt(index);
		}

		@Override
		public long getLong(int index) {
			return fallback.getLong(index);
		}

		@Override
		public float getFloat(int index) {
			return fallback.getFloat(index);
		}

		@Override
		public double getDouble(int index) {
			return fallback.getDouble(index);
		}

		@Override
		public void copyBooleans(int index, boolean[] destination, int offset, int length) {
			for (int i = 0; i < length; i++) {
				destination[offset + i] = fallback.getByte(index + i) != 0;
			}
		}

		@Override
		public void copyShorts(int index, short[] destination, int offset, int length) {
			for (int i = 0; i < length; i++, index += Short.BYTES) {
				destination[offset + i] = fallback.getShort(index);
			}
		}

		@Override
		public void copyChars(int index, char[] destination, int offset, int length) {
			for (int i = 0; i < length; i++, index += Character.BYTES) {
				destination[offset + i] = fallback.getChar(index);
			}
		}

		@Override
		public void copyInts(int index, int[] destination, int offset, int length) {
			for (int i = 0; i < length; i++, index += Integer.BYTES) {
				destination[offset + i] = fallback.getInt(index);
			}
		}

		@Override
		public void copyLongs(int index, long[] destination, int offset, int length) {
			for (int i = 0; i < length; i++, index += Long.BYTES) {
				destination[offset + i] = fallback.getLong(index);
			}
		}

		@Override
		public void copyFloats(int index, float[] destination, int offset, int length) {
			for (int i = 0; i < length; i++, index += Float.BYTES) {
				destination[offset + i] = fallback.getFloat(index);
			}
		}

		@Override
		public void copyDoubles(int index, double[] destination, int offset, int length) {
			for (int i = 0; i < length; i++, index += Double.BYTES) {
				destination[offset + i] = fallback.getDouble(index);
			}
		}

		@Override
		public void copyToArray(int index, byte[] destination, int offset, int length) {
			for (int i = 0; i < length; i++) {
				destination[offset + i] = fallback.getByte(index + i);
			}
		}

		@Override
		public void copyToBuffer(int index, ByteBuffer destination, int length) {
			for (int i = 0; i < length; i++) {
				destination.put(fallback.getByte(index + i));
			}
		}

		@Override
		public String readString(int index, int length, Charset charset) {
			if (length == 0) {
				return "";
			}
			byte[] scratch = ensureStringScratch(length);
			copyToArray(index, scratch, 0, length);
			return new String(scratch, 0, length, charset);
		}
	}

	private byte[] ensureStringScratch(int length) {
		if (stringScratch.length < length) {
			int grown = Math.max(length, Math.max(32, stringScratch.length << 1));
			stringScratch = Arrays.copyOf(stringScratch, grown);
		}
		return stringScratch;
	}

	private static int arrayByteLength(int length, int elementSize) {
		if (length < 0) {
			throw new MalformedDataException("Negative array length: " + length);
		}
		try {
			return Math.multiplyExact(length, elementSize);
		} catch (ArithmeticException exception) {
			throw new MalformedDataException("Array serialized size overflow: " + length
					+ " * " + elementSize, exception);
		}
	}

	private void ensureBound() {
		if (!bound) {
			throw new IllegalStateException("Input is not bound");
		}
	}
}
