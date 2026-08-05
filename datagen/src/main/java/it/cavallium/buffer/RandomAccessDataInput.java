package it.cavallium.buffer;

import it.cavallium.stream.SafeDataInput;
import java.lang.foreign.MemorySegment;

/**
 * A bounded {@link SafeDataInput} whose backing storage can be revisited without copying it.
 *
 * <p>Positions and regions are relative to this input's bound region. Implementations are
 * thread-confined. A region cursor borrows the same storage and must be unbound before this
 * input is unbound.</p>
 */
public interface RandomAccessDataInput extends SafeDataInput {

	/** Returns the current cursor position relative to the beginning of the bound region. */
	int position();

	/** Returns the length of the bound region. */
	int length();

	/** Moves the cursor to an absolute position relative to the beginning of the bound region. */
	void position(int newPosition);

	/**
	 * Reserves and advances over exactly {@code byteLength} bytes with one region bounds check,
	 * returning the reserved span's start relative to this input.
	 *
	 * <p>The absolute getters below are intentionally unchecked against the bounded input region.
	 * Generated code must use them only with offsets inside a successfully reserved span. This lets
	 * a fixed field run pay one cursor check while retaining the backing storage's intrinsic safety.</p>
	 */
	int reserve(int byteLength);

	boolean getBooleanAt(int offset);

	byte getByteAt(int offset);

	int getUnsignedByteAt(int offset);

	short getShortAt(int offset);

	int getUnsignedShortAt(int offset);

	char getCharAt(int offset);

	int getIntAt(int offset);

	long getLongAt(int offset);

	long getInt52At(int offset);

	float getFloatAt(int offset);

	double getDoubleAt(int offset);

	/** Reads a primitive array payload after one input-region reservation. */
	void readBooleans(boolean[] destination, int offset, int length);

	/** Reads a primitive array payload after one input-region reservation. */
	void readBytes(byte[] destination, int offset, int length);

	/** Reads a primitive array payload after one input-region reservation. */
	void readShorts(short[] destination, int offset, int length);

	/** Reads a primitive array payload after one input-region reservation. */
	void readChars(char[] destination, int offset, int length);

	/** Reads a primitive array payload after one input-region reservation. */
	void readInts(int[] destination, int offset, int length);

	/** Reads a primitive array payload after one input-region reservation. */
	void readLongs(long[] destination, int offset, int length);

	/** Reads a primitive array payload after one input-region reservation. */
	void readFloats(float[] destination, int offset, int length);

	/** Reads a primitive array payload after one input-region reservation. */
	void readDoubles(double[] destination, int offset, int length);

	/** Validates and reserves the complete payload before allocating its returned array. */
	boolean[] readBooleanArray(int length);

	/** Validates and reserves the complete payload before allocating its returned array. */
	byte[] readByteArray(int length);

	/** Validates and reserves the complete payload before allocating its returned array. */
	short[] readShortArray(int length);

	/** Validates and reserves the complete payload before allocating its returned array. */
	char[] readCharArray(int length);

	/** Validates and reserves the complete payload before allocating its returned array. */
	int[] readIntArray(int length);

	/** Validates and reserves the complete payload before allocating its returned array. */
	long[] readLongArray(int length);

	/** Validates and reserves the complete payload before allocating its returned array. */
	float[] readFloatArray(int length);

	/** Validates and reserves the complete payload before allocating its returned array. */
	double[] readDoubleArray(int length);

	/**
	 * Advances by exactly {@code length} bytes with one bounds check.
	 *
	 * <p>Unlike {@link #skipBytes(int)}, this method never performs a partial skip: a negative
	 * length or a truncated region fails without changing the cursor position.</p>
	 */
	void skipExact(int length);

	/**
	 * Binds {@code target} to a subregion of this input without creating a slice or copying bytes.
	 */
	void bindRegion(BufDataCursor target, int offset, int length);

	/**
	 * Binds a child to a span that the caller has already reserved. The selected backing storage
	 * and decode budget are propagated directly, without slicing, copying, probing the source, or
	 * changing the parent position.
	 */
	void bindReservedRegion(BufDataCursor target, int offset, int length);

	/**
	 * Returns the directly bound heap storage, or {@code null} for native/fallback storage.
	 * The reference is valid only while this input remains bound and must never be retained.
	 */
	byte[] directHeapArray();

	/**
	 * Returns the directly bound memory segment, or {@code null} for heap/fallback storage.
	 * The reference is valid only while this input remains bound and must never be retained.
	 */
	MemorySegment directMemorySegment();

	/** Maps an input-relative offset to the direct heap/segment storage index. */
	long directStorageOffset(int relativeOffset);
}
