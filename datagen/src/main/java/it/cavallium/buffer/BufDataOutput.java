package it.cavallium.buffer;

import static java.util.Objects.checkFromToIndex;

import it.cavallium.stream.SafeByteArrayOutputStream;
import it.cavallium.stream.SafeDataOutput;
import it.cavallium.stream.SafeDataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;

public class BufDataOutput implements SafeDataOutput {

	private final SafeByteArrayOutputStream buf;
	private final SafeDataOutputStream dOut;
	private final int limit;

	public BufDataOutput(SafeByteArrayOutputStream buf) {
		this.buf = buf;
		this.dOut = new SafeDataOutputStream(buf);
		limit = Integer.MAX_VALUE;
	}

	public BufDataOutput(SafeByteArrayOutputStream buf, int maxSize) {
		this.buf = buf;
		this.dOut = new SafeDataOutputStream(buf);
		this.limit = maxSize;
	}

	public static BufDataOutput createLimited(int maxSize, int hint) {
		if (hint >= 0) {
			if (maxSize < 0 || maxSize == Integer.MAX_VALUE) {
				return create(hint);
			} else {
				return new BufDataOutput(new SafeByteArrayOutputStream(Math.min(maxSize, hint)), maxSize);
			}
		} else {
			return createLimited(maxSize);
		}
	}

	public static BufDataOutput createLimited(int maxSize) {
		if (maxSize < 0 || maxSize == Integer.MAX_VALUE) {
			return create();
		} else {
			return new BufDataOutput(new SafeByteArrayOutputStream(maxSize), maxSize);
		}
	}

	public static BufDataOutput create() {
		return new BufDataOutput(new SafeByteArrayOutputStream());
	}

	public static BufDataOutput create(int hint) {
		if (hint >= 0) {
			return new BufDataOutput(new SafeByteArrayOutputStream(hint));
		} else {
			return create();
		}
	}

	@IgnoreCoverage
	@Deprecated(forRemoval = true)
	public static BufDataOutput wrap(Buf buf, int from, int to) {
		checkFromToIndex(from, to, buf.size());
		if (buf.isEmpty()) {
			return createLimited(0);
		} else {
			return new BufDataOutput(buf.binaryOutputStream(from), to - from);
		}
	}

	@IgnoreCoverage
	@Deprecated(forRemoval = true)
	public static BufDataOutput wrap(Buf buf) {
		if (buf.isEmpty()) {
			return createLimited(0);
		} else {
			return new BufDataOutput(buf.binaryOutputStream(), buf.size());
		}
	}

	private IllegalStateException unreachable(IOException ex) {
		return new IllegalStateException(ex);
	}

	@Override
	public void write(int b) {
		// Fast inlined checkOutOfBounds
		if (dOut.size() >= limit) {
			throw new IndexOutOfBoundsException(limit);
		}

		dOut.write(b);
	}

	private void checkOutOfBounds(int delta) {
		if (dOut.size() + delta > limit) {
			throw new IndexOutOfBoundsException(limit);
		}
	}

	@Override
	public void write(byte @NotNull [] b) {
		checkOutOfBounds(b.length);
		dOut.write(b);
	}

	@Override
	public void write(byte @NotNull [] b, int off, int len) {
		checkOutOfBounds(Math.max(0, Math.min(b.length - off, len)));
		dOut.write(b, off, len);
	}

	@Override
	public void writeBoolean(boolean v) {
		// Fast inlined checkOutOfBounds
		if (dOut.size() >= limit) {
			throw new IndexOutOfBoundsException(limit);
		}

		dOut.writeBoolean(v);
	}

	@Override
	public void writeByte(int v) {
		this.write(v);
	}

	@Override
	public void writeShort(int v) {
		checkOutOfBounds(Short.BYTES);
		dOut.writeShort(v);
	}

	@Override
	public void writeChar(int v) {
		checkOutOfBounds(Character.BYTES);
		dOut.writeChar(v);
	}

	@Override
	public void writeInt(int v) {
		checkOutOfBounds(Integer.BYTES);
		dOut.writeInt(v);
	}

	@Override
	public void writeLong(long v) {
		checkOutOfBounds(Long.BYTES);
		dOut.writeLong(v);
	}

	public void writeInt52(long v) {
		checkOutOfBounds(7);
		dOut.writeInt52(v);
	}

	@Override
	public void writeFloat(float v) {
		checkOutOfBounds(Float.BYTES);
		dOut.writeFloat(v);
	}

	@Override
	public void writeDouble(double v) {
		checkOutOfBounds(Double.BYTES);
		dOut.writeDouble(v);
	}

	public void ensureWritable(int size) {
		dOut.flush();
		buf.ensureWritable(size);
	}

	@Override
	public void writeBytes(@NotNull String s) {
		checkOutOfBounds(s.length() * Byte.BYTES);
		dOut.writeBytes(s);
	}

	// todo: check
	public void writeBytes(Buf deserialized) {
		checkOutOfBounds(deserialized.size());
		deserialized.writeTo(dOut);
	}

	public void writeBytes(byte[] b, int off, int len) {
		write(b, off, len);
	}

	@Override
	public void writeChars(@NotNull String s) {
		checkOutOfBounds(Character.BYTES * s.length());
		dOut.writeChars(s);
	}

	private static String tooLongMsg(String s, int bits32) {
		int slen = s.length();
		String head = s.substring(0, 8);
		String tail = s.substring(slen - 8, slen);
		// handle int overflow with max 3x expansion
		long actualLength = (long)slen + Integer.toUnsignedLong(bits32 - slen);
		return "encoded string (" + head + "..." + tail + ") too long: "
				+ actualLength + " bytes";
	}

	@Deprecated
	@Override
	public void writeUTF(@NotNull String str) {
		writeShortText(str, StandardCharsets.UTF_8);
	}

	@Override
	public void writeShortText(String s, Charset charset) {
		if (charset == StandardCharsets.UTF_8) {
			var beforeWrite = this.buf.position();
			writeShort(0);
			ZeroAllocationEncoder.INSTANCE.encodeTo(s, this);
			var afterWrite = this.buf.position();
			this.buf.position(beforeWrite);
			var len = Math.toIntExact(afterWrite - beforeWrite - Short.BYTES);
			if (len > Short.MAX_VALUE) {
				throw new IndexOutOfBoundsException("String too long: " + len + " bytes");
			}
			this.writeShort(len);
			this.buf.position(afterWrite);
		} else {
			var out = s.getBytes(charset);
			if (out.length > Short.MAX_VALUE) {
				throw new IndexOutOfBoundsException("String too long: " + out.length + " bytes");
			}
			checkOutOfBounds(Short.BYTES + out.length);
			dOut.writeShort(out.length);
			dOut.write(out);
		}
	}

	@Override
	public void writeMediumText(String s, Charset charset) {
		if (charset == StandardCharsets.UTF_8) {
			var beforeWrite = this.buf.position();
			writeInt(0);
			ZeroAllocationEncoder.INSTANCE.encodeTo(s, this);
			var afterWrite = this.buf.position();
			this.buf.position(beforeWrite);
			this.writeInt(Math.toIntExact(afterWrite - beforeWrite - Integer.BYTES));
			this.buf.position(afterWrite);
		} else {
			var out = s.getBytes(charset);
			checkOutOfBounds(Integer.BYTES + out.length);
			dOut.writeInt(out.length);
			dOut.write(out);
		}
	}

	public Buf asList() {
		dOut.flush();
		return Buf.wrap(this.buf.array, this.buf.length);
	}

	public Buf toList() {
		dOut.flush();
		return Buf.wrap(Arrays.copyOf(this.buf.array, this.buf.length));
	}

	@Override
	public String toString() {
		return dOut.toString();
	}

	@Override
	public int hashCode() {
		return dOut.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		BufDataOutput that = (BufDataOutput) o;

		return Objects.equals(dOut, that.dOut);
	}

	public int size() {
		return dOut.size();
	}
}
