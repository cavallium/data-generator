package it.cavallium.buffer;

import it.cavallium.stream.SafeDataInput;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * A reusable, thread-confined {@link SafeDataInput} over a bounded region of a {@link Buf}.
 *
 * <p>The cursor reads the buffer's existing heap or native storage directly. Binding does not
 * create a stream, slice, byte array, or byte-buffer view. Callers must always {@link #unbind()}
 * after an operation so the cursor does not extend the source buffer's lifetime.</p>
 */
public final class BufDataCursor implements SafeDataInput {

	private Buf source;
	private int position;
	private int limit;

	/**
	 * Binds this cursor to {@code [offset, offset + length)} in {@code source}.
	 *
	 * @throws IllegalStateException if this cursor is already bound
	 * @throws IndexOutOfBoundsException if the requested region is outside the source
	 */
	public void bind(Buf source, int offset, int length) {
		if (this.source != null) {
			throw new IllegalStateException("Cursor is already bound");
		}
		Objects.requireNonNull(source, "source");
		Objects.checkFromIndexSize(offset, length, source.size());
		this.source = source;
		this.position = offset;
		this.limit = offset + length;
	}

	/** Clears the bound source and resets this cursor for reuse. */
	public void unbind() {
		source = null;
		position = 0;
		limit = 0;
	}

	@VisibleForTesting
	boolean isBound() {
		return source != null;
	}

	public int remaining() {
		ensureBound();
		return limit - position;
	}

	@Override
	public int read(byte @NotNull [] bytes) {
		return read(bytes, 0, bytes.length);
	}

	@Override
	public int read(byte @NotNull [] bytes, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, bytes.length);
		ensureBound();
		if (length == 0) {
			return 0;
		}
		int available = limit - position;
		if (available == 0) {
			return -1;
		}
		int read = Math.min(length, available);
		copyTo(bytes, offset, read);
		return read;
	}

	@Override
	public void readFully(byte @NotNull [] bytes) {
		readFully(bytes, 0, bytes.length);
	}

	@Override
	public void readFully(byte @NotNull [] bytes, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, bytes.length);
		ensureAvailable(length);
		copyTo(bytes, offset, length);
	}

	@Override
	public void readFully(ByteBuffer destination) {
		readFully(destination, destination.remaining());
	}

	@Override
	public void readFully(ByteBuffer destination, int length) {
		Objects.requireNonNull(destination, "destination");
		if (length < 0 || length > destination.remaining()) {
			throw new IndexOutOfBoundsException();
		}
		ensureAvailable(length);
		for (int i = 0; i < length; i++) {
			destination.put(source.getByte(position++));
		}
	}

	@Override
	public int skipBytes(int count) {
		ensureBound();
		if (count <= 0) {
			return 0;
		}
		int skipped = Math.min(count, limit - position);
		position += skipped;
		return skipped;
	}

	@Override
	public boolean readBoolean() {
		return readByte() != 0;
	}

	@Override
	public byte readByte() {
		int index = take(Byte.BYTES);
		return source.getByte(index);
	}

	@Override
	public int readUnsignedByte() {
		return Byte.toUnsignedInt(readByte());
	}

	@Override
	public short readShort() {
		int index = take(Short.BYTES);
		return source.getShort(index);
	}

	@Override
	public int readUnsignedShort() {
		return Short.toUnsignedInt(readShort());
	}

	@Override
	public char readChar() {
		int index = take(Character.BYTES);
		return source.getChar(index);
	}

	@Override
	public int readInt() {
		int index = take(Integer.BYTES);
		return source.getInt(index);
	}

	@Override
	public long readLong() {
		int index = take(Long.BYTES);
		return source.getLong(index);
	}

	@Override
	public long readInt52() {
		int index = take(7);
		return ((long) source.getByte(index) & 0x0fL) << 48
				| ((long) source.getByte(index + 1) & 0xffL) << 40
				| ((long) source.getByte(index + 2) & 0xffL) << 32
				| ((long) source.getByte(index + 3) & 0xffL) << 24
				| ((long) source.getByte(index + 4) & 0xffL) << 16
				| ((long) source.getByte(index + 5) & 0xffL) << 8
				| ((long) source.getByte(index + 6) & 0xffL);
	}

	@Override
	public float readFloat() {
		int index = take(Float.BYTES);
		return source.getFloat(index);
	}

	@Override
	public double readDouble() {
		int index = take(Double.BYTES);
		return source.getDouble(index);
	}

	@Override
	@Deprecated
	public String readLine() {
		throw new UnsupportedOperationException();
	}

	@Override
	public @NotNull String readString(int length, Charset charset) {
		Objects.requireNonNull(charset, "charset");
		int index = take(length);
		return source.getString(index, length, charset);
	}

	/** Closing a cursor is equivalent to unbinding it; the cursor remains reusable. */
	@Override
	public void close() {
		unbind();
	}

	private int take(int length) {
		ensureAvailable(length);
		int index = position;
		position += length;
		return index;
	}

	private void ensureAvailable(int length) {
		ensureBound();
		if (length < 0 || length > limit - position) {
			throw new IndexOutOfBoundsException();
		}
	}

	private void ensureBound() {
		if (source == null) {
			throw new IllegalStateException("Cursor is not bound");
		}
	}

	private void copyTo(byte[] destination, int offset, int length) {
		for (int i = 0; i < length; i++) {
			destination[offset + i] = source.getByte(position + i);
		}
		position += length;
	}
}
