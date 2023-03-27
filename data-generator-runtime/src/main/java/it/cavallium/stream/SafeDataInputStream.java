/*
 * Copyright (c) 1994, 2019, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package it.cavallium.stream;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;

public class SafeDataInputStream extends SafeFilterInputStream implements SafeDataInput {

	/**
	 * Creates a DataInputStream that uses the specified
	 * underlying InputStream.
	 *
	 * @param  in   the specified input stream
	 */
	public SafeDataInputStream(SafeInputStream in) {
		super(in);
	}

	/**
	 * working arrays initialized on demand by readUTF
	 */
	private byte[] bytearr;
	private char[] chararr;
	private CharsetDecoder utfdec;

	@Override
	public final int read(byte[] b) {
		return in.read(b, 0, b.length);
	}

	@Override
	public final int read(byte[] b, int off, int len) {
		return in.read(b, off, len);
	}

	/**
	 * See the general contract of the {@code readFully}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @param   b   the buffer into which the data is read.
	 * @throws  NullPointerException if {@code b} is {@code null}.
	 * @see     SafeFilterInputStream#in
	 */
	@Override
	public final void readFully(byte @NotNull [] b) {
		readFully(b, 0, b.length);
	}

	/**
	 * See the general contract of the {@code readFully}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @param      b     the buffer into which the data is read.
	 * @param      off   the start offset in the data array {@code b}.
	 * @param      len   the number of bytes to read.
	 * @throws     NullPointerException if {@code b} is {@code null}.
	 * @throws     IndexOutOfBoundsException if {@code off} is negative,
	 *             {@code len} is negative, or {@code len} is greater than
	 *             {@code b.length - off}.
	 * @see        SafeFilterInputStream#in
	 */
	@Override
	public final void readFully(byte @NotNull [] b, int off, int len) {
		if (len < 0)
			throw new IndexOutOfBoundsException();
		int n = 0;
		while (n < len) {
			int count = in.read(b, off + n, len - n);
			if (count < 0)
				throw new IndexOutOfBoundsException();
			n += count;
		}
	}

	/**
	 * See the general contract of the {@code skipBytes}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes for this operation are read from the contained
	 * input stream.
	 *
	 * @param      n   the number of bytes to be skipped.
	 * @return     the actual number of bytes skipped.
	 */
	@Override
	public final int skipBytes(int n) {
		int total = 0;
		int cur;

		while ((total<n) && ((cur = (int) in.skip(n-total)) > 0)) {
			total += cur;
		}

		return total;
	}

	/**
	 * See the general contract of the {@code readBoolean}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes for this operation are read from the contained
	 * input stream.
	 *
	 * @return     the {@code boolean} value read.
	 * @see        SafeFilterInputStream#in
	 */
	@Override
	public final boolean readBoolean() {
		int ch = in.read();
		if (ch < 0)
			throw new IndexOutOfBoundsException();
		return (ch != 0);
	}

	/**
	 * See the general contract of the {@code readByte}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @return     the next byte of this input stream as a signed 8-bit
	 *             {@code byte}.
	 * @see        SafeFilterInputStream#in
	 */
	@Override
	public final byte readByte() {
		int ch = in.read();
		if (ch < 0)
			throw new IndexOutOfBoundsException();
		return (byte)(ch);
	}

	/**
	 * See the general contract of the {@code readUnsignedByte}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @return     the next byte of this input stream, interpreted as an
	 *             unsigned 8-bit number.
	 * @see         SafeFilterInputStream#in
	 */
	@Override
	public final int readUnsignedByte() {
		int ch = in.read();
		if (ch < 0)
			throw new IndexOutOfBoundsException();
		return ch;
	}

	/**
	 * See the general contract of the {@code readShort}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @return     the next two bytes of this input stream, interpreted as a
	 *             signed 16-bit number.
	 * @see        SafeFilterInputStream#in
	 */
	@Override
	public final short readShort() {
		int ch1 = in.read();
		int ch2 = in.read();
		if ((ch1 | ch2) < 0)
			throw new IndexOutOfBoundsException();
		return (short)((ch1 << 8) + (ch2));
	}

	/**
	 * See the general contract of the {@code readUnsignedShort}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @return     the next two bytes of this input stream, interpreted as an
	 *             unsigned 16-bit integer.
	 * @see        SafeFilterInputStream#in
	 */
	@Override
	public final int readUnsignedShort() {
		int ch1 = in.read();
		int ch2 = in.read();
		if ((ch1 | ch2) < 0)
			throw new IndexOutOfBoundsException();
		return (ch1 << 8) + (ch2);
	}

	/**
	 * See the general contract of the {@code readChar}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @return     the next two bytes of this input stream, interpreted as a
	 *             {@code char}.
	 * @see        SafeFilterInputStream#in
	 */
	@Override
	public final char readChar() {
		int ch1 = in.read();
		int ch2 = in.read();
		if ((ch1 | ch2) < 0)
			throw new IndexOutOfBoundsException();
		return (char)((ch1 << 8) + (ch2));
	}

	/**
	 * See the general contract of the {@code readInt}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @return     the next four bytes of this input stream, interpreted as an
	 *             {@code int}.
	 * @see        SafeFilterInputStream#in
	 */
	@Override
	public final int readInt() {
		int ch1 = in.read();
		int ch2 = in.read();
		int ch3 = in.read();
		int ch4 = in.read();
		if ((ch1 | ch2 | ch3 | ch4) < 0)
			throw new IndexOutOfBoundsException();
		return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4));
	}

	private final byte[] readBuffer = new byte[8];

	/**
	 * See the general contract of the {@code readLong}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @return     the next eight bytes of this input stream, interpreted as a
	 *             {@code long}.
	 * @see        SafeFilterInputStream#in
	 */
	@Override
	public final long readLong() {
		readFully(readBuffer, 0, 8);
		return (((long)readBuffer[0] << 56) +
				((long)(readBuffer[1] & 255) << 48) +
				((long)(readBuffer[2] & 255) << 40) +
				((long)(readBuffer[3] & 255) << 32) +
				((long)(readBuffer[4] & 255) << 24) +
				((readBuffer[5] & 255) << 16) +
				((readBuffer[6] & 255) <<  8) +
				((readBuffer[7] & 255)));
	}

	@Override
	public final long readInt52() {
		readFully(readBuffer, 0, 7);
		return ((long) (readBuffer[0] & 0xf) << 48)
				+ ((long) (readBuffer[1] & 0xff) << 40)
				+ ((long) (readBuffer[2] & 0xff) << 32)
				+ ((long) (readBuffer[3] & 0xff) << 24)
				+ ((long) (readBuffer[4] & 0xff) << 16)
				+ ((long) (readBuffer[5] & 0xff) << 8)
				+ (long) (readBuffer[6] & 0xff);
	}

	/**
	 * See the general contract of the {@code readFloat}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @return     the next four bytes of this input stream, interpreted as a
	 *             {@code float}.
	 * @see        SafeDataInputStream#readInt()
	 * @see        Float#intBitsToFloat(int)
	 */
	@Override
	public final float readFloat() {
		return Float.intBitsToFloat(readInt());
	}

	/**
	 * See the general contract of the {@code readDouble}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @return     the next eight bytes of this input stream, interpreted as a
	 *             {@code double}.
	 * @see        SafeDataInputStream#readLong()
	 * @see        Double#longBitsToDouble(long)
	 */
	@Override
	public final double readDouble() {
		return Double.longBitsToDouble(readLong());
	}

	private char[] lineBuffer;

	/**
	 * See the general contract of the {@code readLine}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @deprecated This method does not properly convert bytes to characters.
	 * As of JDK&nbsp;1.1, the preferred way to read lines of text is via the
	 * {@code BufferedReader.readLine()} method.  Programs that use the
	 * {@code DataInputStream} class to read lines can be converted to use
	 * the {@code BufferedReader} class by replacing code of the form:
	 * <blockquote><pre>
	 *     DataInputStream d =&nbsp;new&nbsp;DataInputStream(in);
	 * </pre></blockquote>
	 * with:
	 * <blockquote><pre>
	 *     BufferedReader d
	 *          =&nbsp;new&nbsp;BufferedReader(new&nbsp;InputStreamReader(in));
	 * </pre></blockquote>
	 *
	 * @return     the next line of text from this input stream.
	 * @see        java.io.BufferedReader#readLine()
	 * @see        SafeFilterInputStream#in
	 */
	@Override
	@Deprecated
	public final String readLine() {
		char[] buf = lineBuffer;

		if (buf == null) {
			buf = lineBuffer = new char[128];
		}

		int room = buf.length;
		int offset = 0;
		int c;

		loop:   while (true) {
			switch (c = in.read()) {
				case -1:
				case '\n':
					break loop;

				case '\r':
					int c2 = in.read();
					if ((c2 != '\n') && (c2 != -1)) {
						if (!(in instanceof SafePushbackInputStream)) {
							this.in = new SafePushbackInputStream(in);
						}
						((SafePushbackInputStream)in).unread(c2);
					}
					break loop;

				default:
					if (--room < 0) {
						buf = new char[offset + 128];
						room = buf.length - offset - 1;
						System.arraycopy(lineBuffer, 0, buf, 0, offset);
						lineBuffer = buf;
					}
					buf[offset++] = (char) c;
					break;
			}
		}
		if ((c == -1) && (offset == 0)) {
			return null;
		}
		return String.copyValueOf(buf, 0, offset);
	}

	/**
	 * See the general contract of the {@code readUTF}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @return     a Unicode string.
	 * @see        SafeDataInputStream#readUTF(SafeDataInputStream)
	 */
	@Override
	public final @NotNull String readUTF() {
		return readUTF(this);
	}

	/**
	 * Reads from the
	 * stream {@code in} a representation
	 * of a Unicode  character string encoded in
	 * <a href="DataInput.html#modified-utf-8">modified UTF-8</a> format;
	 * this string of characters is then returned as a {@code String}.
	 * The details of the modified UTF-8 representation
	 * are  exactly the same as for the {@code readUTF}
	 * method of {@code DataInput}.
	 *
	 * @param      in   a data input stream.
	 * @return     a Unicode string.
	 * @see        SafeDataInputStream#readUnsignedShort()
	 */
	public static String readUTF(SafeDataInputStream in) {
		if (in.bytearr == null) in.bytearr = new byte[80];
		if (in.chararr == null) in.chararr = new char[80];
		if (in.utfdec == null) in.utfdec = StandardCharsets.UTF_8.newDecoder()
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.onMalformedInput(CodingErrorAction.REPORT);
		int utflen = in.readUnsignedShort();
		var data = new byte[utflen];
		in.readFully(data);
		try {
			return in.utfdec.reset().decode(ByteBuffer.wrap(data)).toString();
		} catch (CharacterCodingException e) {
			throw new IllegalArgumentException("malformed input string", e);
		}
	}
}
