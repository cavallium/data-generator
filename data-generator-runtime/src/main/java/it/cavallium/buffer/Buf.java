package it.cavallium.buffer;

import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeByteArrayOutputStream;
import it.cavallium.stream.SafeDataOutput;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.bytes.ByteList;
import java.nio.charset.Charset;
import java.util.RandomAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface Buf extends ByteList, RandomAccess {
	static Buf wrap(ByteList bytes) {
		if (bytes instanceof Buf buf) {
			return buf;
		} else if (bytes instanceof ByteArrayList byteArrayList) {
			return ByteListBuf.wrap(byteArrayList.elements(), byteArrayList.size());
		} else {
			return ByteListBuf.wrap(bytes.toByteArray());
		}
	}
	static Buf wrap(ByteList bytes, int from, int to) {
		if (bytes instanceof Buf buf) {
			return buf.subList(from, to);
		} else if (bytes instanceof ByteArrayList byteArrayList) {
			return ByteListBuf.wrap(byteArrayList.elements(), byteArrayList.size()).subList(from, to);
		} else {
			return ByteListBuf.wrap(bytes.toByteArray()).subList(from, to);
		}
	}

	static Buf wrap(byte[] bytes) {
		return ByteListBuf.wrap(bytes);
	}

	static Buf wrap(byte[] bytes, int from, int to) {
		return ByteListBuf.wrap(bytes, to).subList(from, to);
	}

	static Buf create(int initialCapacity) {
		return new ByteListBuf(initialCapacity);
	}

	static Buf copyOf(byte[] original) {
		return new ByteListBuf(original);
	}

	static Buf create() {
		return new ByteListBuf();
	}

	static Buf wrap(byte[] array, int length) {
		return ByteListBuf.wrap(array, length);
	}

	static Buf createZeroes(int length) {
		return ByteListBuf.wrap(new byte[length], length);
	}

	/**
	 * Get this element as an array, converting it if needed
	 */
	byte @NotNull[] asArray();

	/**
	 * Get this element as an array, only if it's already an array, otherwise return null
	 */
	byte @Nullable[] asArrayStrict();

	/**
	 * Get this element as an array with equal or bigger size, converting it if needed
	 * The returned array may be bigger than expected!
	 */
	byte[] asUnboundedArray();

	/**
	 * Get this element as an array with equal or bigger size, only if it's already an array, otherwise return null
	 * The returned array may be bigger than expected!
	 */
	byte @Nullable[] asUnboundedArrayStrict();

	boolean isMutable();

	Buf freeze();

	@Override
	Buf subList(int from, int to);

	Buf copyOfRange(int from, int to);

	Buf copy();

	SafeByteArrayInputStream binaryInputStream();

	void writeTo(SafeDataOutput dataOutput);

	/**
	 * @param i byte offset
	 */
	default long getLong(int i) {
		byte b1 = getByte(i);
		byte b2 = getByte(i + 1);
		byte b3 = getByte(i + 2);
		byte b4 = getByte(i + 3);
		byte b5 = getByte(i + 4);
		byte b6 = getByte(i + 5);
		byte b7 = getByte(i + 6);
		byte b8 = getByte(i + 7);
		return (b1 & 0xFFL) << 56
				| (b2 & 0xFFL) << 48
				| (b3 & 0xFFL) << 40
				| (b4 & 0xFFL) << 32
				| (b5 & 0xFFL) << 24
				| (b6 & 0xFFL) << 16
				| (b7 & 0xFFL) << 8
				| (b8 & 0xFFL);
	}

	/**
	 * @param i byte offset
	 */
	default int getInt(int i) {
		byte b1 = getByte(i);
		byte b2 = getByte(i + 1);
		byte b3 = getByte(i + 2);
		byte b4 = getByte(i + 3);
		return b1 << 24 | (b2 & 0xFF) << 16 | (b3 & 0xFF) << 8 | (b4 & 0xFF);
	}

	/**
	 * @param i byte offset
	 */
	default float getFloat(int i) {
		return Float.intBitsToFloat(getInt(i));
	}

	/**
	 * @param i byte offset
	 */
	default double getDouble(int i) {
		return Double.longBitsToDouble(getLong(i));
	}

	/**
	 * @param i byte offset
	 */
	default boolean getBoolean(int i) {
		return getByte(i) != 0;
	}

	/**
	 * @param i byte offset
	 */
	default void setBoolean(int i, boolean val) {
		set(i, val ? (byte) 1 : 0);
	}

	/**
	 * @param i byte offset
	 */
	default void setByte(int i, byte val) {
		set(i, val);
	}

	/**
	 * @param i byte offset
	 */
	default void setInt(int i, int val) {
		set(i, (byte) (val >> 24));
		set(i + 1, (byte) (val >> 16));
		set(i + 2, (byte) (val >> 8));
		set(i + 3, (byte) val);
	}

	/**
	 * @param i byte offset
	 */
	default void setLong(int i, long val) {
		set(i, (byte) (val >> 56));
		set(i + 1, (byte) (val >> 48));
		set(i + 2, (byte) (val >> 40));
		set(i + 3, (byte) (val >> 32));
		set(i + 4, (byte) (val >> 24));
		set(i + 5, (byte) (val >> 16));
		set(i + 6, (byte) (val >> 8));
		set(i + 7, (byte) val);
	}

	/**
	 * @param i byte offset
	 */
	default void setFloat(int i, float val) {
		setInt(i, Float.floatToRawIntBits(val));
	}

	/**
	 * @param i byte offset
	 */
	default void setDouble(int i, double val) {
		setLong(i, Double.doubleToRawLongBits(val));
	}

	default SafeByteArrayOutputStream binaryOutputStream() {
		return binaryOutputStream(0, size());
	}

	default SafeByteArrayOutputStream binaryOutputStream(int from) {
		return binaryOutputStream(from, size());
	}

	SafeByteArrayOutputStream binaryOutputStream(int from, int to);

	boolean equals(int aStartIndex, Buf b, int bStartIndex, int length);

	boolean equals(int aStartIndex, byte[] b, int bStartIndex, int length);

	String toString(Charset charset);
}
