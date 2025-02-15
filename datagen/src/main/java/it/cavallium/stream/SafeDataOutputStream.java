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

import it.cavallium.buffer.IgnoreCoverage;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;

/**
 * A data output stream lets an application write primitive Java data
 * types to an output stream in a portable way. An application can
 * then use a data input stream to read the data back in.
 *
 * @author  unascribed
 * @see     java.io.DataInputStream
 * @since   1.0
 */
public class SafeDataOutputStream extends SafeFilterOutputStream implements SafeDataOutput {
	/**
	 * The number of bytes written to the data output stream so far.
	 * If this counter overflows, it will be wrapped to Integer.MAX_VALUE.
	 */
	protected int written;

	/**
	 * Creates a new data output stream to write data to the specified
	 * underlying output stream. The counter {@code written} is
	 * set to zero.
	 *
	 * @param   out   the underlying output stream, to be saved for later
	 *                use.
	 * @see     SafeFilterOutputStream#out
	 */
	public SafeDataOutputStream(SafeOutputStream out) {
		super(out);
	}

	/**
	 * Increases the written counter by the specified value
	 * until it reaches Integer.MAX_VALUE.
	 */
	protected void incCount(int value) {
		written = Math.addExact(written, value);
	}
	/**
	 * Decreases the written counter by the specified value
	 * until it reaches 0.
	 */
	protected void decCount(int value) {
		written = Math.subtractExact(written, value);
	}

	/**
	 * Writes the specified byte (the low eight bits of the argument
	 * {@code b}) to the underlying output stream. If no exception
	 * is thrown, the counter {@code written} is incremented by
	 * {@code 1}.
	 * <p>
	 * Implements the {@code write} method of {@code OutputStream}.
	 *
	 * @param      b   the {@code byte} to be written.
	 * @see        SafeFilterOutputStream#out
	 */
	public void write(int b) {
		out.write(b);
		incCount(1);
	}

	/**
	 * Writes {@code b.length} bytes to this output stream.
	 * <p>
	 * The {@code write} method of {@code FilterOutputStream}
	 * calls its {@code write} method of three arguments with the
	 * arguments {@code b}, {@code 0}, and
	 * {@code b.length}.
	 * <p>
	 * Note that this method does not call the one-argument
	 * {@code write} method of its underlying output stream with
	 * the single argument {@code b}.
	 *
	 * @param      b   the data to be written.
	 * @see        SafeFilterOutputStream#write(byte[], int, int)
	 */
	@Override
	public void write(byte @NotNull [] b) {
		out.write(b);
		incCount(b.length);
	}

	/**
	 * Writes {@code len} bytes from the specified byte array
	 * starting at offset {@code off} to the underlying output stream.
	 * If no exception is thrown, the counter {@code written} is
	 * incremented by {@code len}.
	 *
	 * @param      b     the data.
	 * @param      off   the start offset in the data.
	 * @param      len   the number of bytes to write.
	 * @see        SafeFilterOutputStream#out
	 */
	public void write(byte[] b, int off, int len)
	{
		out.write(b, off, len);
		incCount(len);
	}

	/**
	 * Flushes this data output stream. This forces any buffered output
	 * bytes to be written out to the stream.
	 * <p>
	 * The {@code flush} method of {@code SafeDataOutputStream}
	 * calls the {@code flush} method of its underlying output stream.
	 *
	 * @see        SafeFilterOutputStream#out
	 * @see        java.io.OutputStream#flush()
	 */
	@IgnoreCoverage
	public void flush() {
		out.flush();
	}

	/**
	 * Writes a {@code boolean} to the underlying output stream as
	 * a 1-byte value. The value {@code true} is written out as the
	 * value {@code (byte)1}; the value {@code false} is
	 * written out as the value {@code (byte)0}. If no exception is
	 * thrown, the counter {@code written} is incremented by
	 * {@code 1}.
	 *
	 * @param      v   a {@code boolean} value to be written.
	 * @see        SafeFilterOutputStream#out
	 */
	public final void writeBoolean(boolean v) {
		out.write(v ? 1 : 0);
		incCount(1);
	}

	/**
	 * Writes out a {@code byte} to the underlying output stream as
	 * a 1-byte value. If no exception is thrown, the counter
	 * {@code written} is incremented by {@code 1}.
	 *
	 * @param      v   a {@code byte} value to be written.
	 * @see        SafeFilterOutputStream#out
	 */
	public final void writeByte(int v) {
		this.write(v);
	}

	/**
	 * Writes a {@code short} to the underlying output stream as two
	 * bytes, high byte first. If no exception is thrown, the counter
	 * {@code written} is incremented by {@code 2}.
	 *
	 * @param      v   a {@code short} to be written.
	 * @see        SafeFilterOutputStream#out
	 */
	public final void writeShort(int v) {
		out.write((v >>> 8) & 0xFF);
		out.write((v) & 0xFF);
		incCount(2);
	}

	/**
	 * Writes a {@code char} to the underlying output stream as a
	 * 2-byte value, high byte first. If no exception is thrown, the
	 * counter {@code written} is incremented by {@code 2}.
	 *
	 * @param      v   a {@code char} value to be written.
	 * @see        SafeFilterOutputStream#out
	 */
	public final void writeChar(int v) {
		out.write((v >>> 8) & 0xFF);
		out.write((v) & 0xFF);
		incCount(2);
	}

	/**
	 * Writes an {@code int} to the underlying output stream as four
	 * bytes, high byte first. If no exception is thrown, the counter
	 * {@code written} is incremented by {@code 4}.
	 *
	 * @param      v   an {@code int} to be written.
	 * @see        SafeFilterOutputStream#out
	 */
	public final void writeInt(int v) {
		out.write((v >>> 24) & 0xFF);
		out.write((v >>> 16) & 0xFF);
		out.write((v >>>  8) & 0xFF);
		out.write((v) & 0xFF);
		incCount(4);
	}

	private final byte[] writeBuffer = new byte[8];

	/**
	 * Writes a {@code long} to the underlying output stream as eight
	 * bytes, high byte first. In no exception is thrown, the counter
	 * {@code written} is incremented by {@code 8}.
	 *
	 * @param      v   a {@code long} to be written.
	 * @see        SafeFilterOutputStream#out
	 */
	public final void writeLong(long v) {
		writeBuffer[0] = (byte)(v >>> 56);
		writeBuffer[1] = (byte)(v >>> 48);
		writeBuffer[2] = (byte)(v >>> 40);
		writeBuffer[3] = (byte)(v >>> 32);
		writeBuffer[4] = (byte)(v >>> 24);
		writeBuffer[5] = (byte)(v >>> 16);
		writeBuffer[6] = (byte)(v >>>  8);
		writeBuffer[7] = (byte)(v);
		out.write(writeBuffer, 0, 8);
		incCount(8);
	}

	/**
	 * Writes a {@code Int52} to the underlying output stream as seven
	 * bytes, high byte first. In no exception is thrown, the counter
	 * {@code written} is incremented by {@code 7}.
	 *
	 * @param      v   a {@code Int52} to be written.
	 * @see        SafeFilterOutputStream#out
	 */
	public final void writeInt52(long v) {
		writeBuffer[0] = (byte)(v >> 48 & 0xf);
		writeBuffer[1] = (byte)(v >> 40 & 0xff);
		writeBuffer[2] = (byte)(v >> 32 & 0xff);
		writeBuffer[3] = (byte)(v >> 24 & 0xff);
		writeBuffer[4] = (byte)(v >> 16 & 0xff);
		writeBuffer[5] = (byte)(v >> 8 & 0xff);
		writeBuffer[6] = (byte)(v & 0xff);
		out.write(writeBuffer, 0, 7);
		incCount(7);
	}

	/**
	 * Converts the float argument to an {@code int} using the
	 * {@code floatToIntBits} method in class {@code Float},
	 * and then writes that {@code int} value to the underlying
	 * output stream as a 4-byte quantity, high byte first. If no
	 * exception is thrown, the counter {@code written} is
	 * incremented by {@code 4}.
	 *
	 * @param      v   a {@code float} value to be written.
	 * @see        SafeFilterOutputStream#out
	 * @see        Float#floatToIntBits(float)
	 */
	public final void writeFloat(float v) {
		writeInt(Float.floatToIntBits(v));
	}

	/**
	 * Converts the double argument to a {@code long} using the
	 * {@code doubleToLongBits} method in class {@code Double},
	 * and then writes that {@code long} value to the underlying
	 * output stream as an 8-byte quantity, high byte first. If no
	 * exception is thrown, the counter {@code written} is
	 * incremented by {@code 8}.
	 *
	 * @param      v   a {@code double} value to be written.
	 * @see        SafeFilterOutputStream#out
	 * @see        Double#doubleToLongBits(double)
	 */
	public final void writeDouble(double v) {
		writeLong(Double.doubleToLongBits(v));
	}

	/**
	 * Writes out the string to the underlying output stream as a
	 * sequence of bytes. Each character in the string is written out, in
	 * sequence, by discarding its high eight bits. If no exception is
	 * thrown, the counter {@code written} is incremented by the
	 * length of {@code s}.
	 *
	 * @param      s   a string of bytes to be written.
	 * @see        SafeFilterOutputStream#out
	 */
	@Deprecated
	@IgnoreCoverage
	public final void writeBytes(String s) {
		int len = s.length();
		for (int i = 0 ; i < len ; i++) {
			out.write((byte)s.charAt(i));
		}
		incCount(len);
	}

	/**
	 * Writes a string to the underlying output stream as a sequence of
	 * characters. Each character is written to the data output stream as
	 * if by the {@code writeChar} method. If no exception is
	 * thrown, the counter {@code written} is incremented by twice
	 * the length of {@code s}.
	 *
	 * @param      s   a {@code String} value to be written.
	 * @see        SafeDataOutputStream#writeChar(int)
	 * @see        SafeFilterOutputStream#out
	 */
	@Deprecated
	@IgnoreCoverage
	public final void writeChars(String s) {
		int len = s.length();
		for (int i = 0 ; i < len ; i++) {
			int v = s.charAt(i);
			out.write((v >>> 8) & 0xFF);
			out.write((v) & 0xFF);
		}
		incCount(len * 2);
	}

	/**
	 * Writes a string to the underlying output stream using
	 * <a href="DataInput.html#modified-utf-8">modified UTF-8</a>
	 * encoding in a machine-independent manner.
	 * <p>
	 * First, two bytes are written to the output stream as if by the
	 * {@code writeShort} method giving the number of bytes to
	 * follow. This value is the number of bytes actually written out,
	 * not the length of the string. Following the length, each character
	 * of the string is output, in sequence, using the modified UTF-8 encoding
	 * for the character. If no exception is thrown, the counter
	 * {@code written} is incremented by the total number of
	 * bytes written to the output stream. This will be at least two
	 * plus the length of {@code str}, and at most two plus
	 * thrice the length of {@code str}.
	 *
	 * @param      str   a string to be written.
	 * @see        #writeChars(String)
	 */
	@IgnoreCoverage
	@Deprecated
	public final void writeUTF(String str) {
		writeShortText(str, StandardCharsets.UTF_8);
	}

	@Override
	public void writeShortText(String s, Charset charset) {
		var outString = s.getBytes(charset);
		if (outString.length > Short.MAX_VALUE) {
			throw new IndexOutOfBoundsException("String too long: " + outString.length + " bytes");
		}
		var v = outString.length;
		out.write((v >>> 8) & 0xFF);
		out.write((v) & 0xFF);
		out.write(outString);
		incCount(Short.BYTES + outString.length);
	}

	@Override
	public void writeMediumText(String s, Charset charset) {
		var outString = s.getBytes(charset);
		var v = outString.length;
		out.write((v >>> 24) & 0xFF);
		out.write((v >>> 16) & 0xFF);
		out.write((v >>>  8) & 0xFF);
		out.write((v) & 0xFF);
		out.write(outString);
		incCount(Integer.BYTES + outString.length);
	}

	/**
	 * Returns the current value of the counter {@code written},
	 * the number of bytes written to this data output stream so far.
	 * If the counter overflows, it will be wrapped to Integer.MAX_VALUE.
	 *
	 * @return  the value of the {@code written} field.
	 * @see     SafeDataOutputStream#written
	 */
	public final int size() {
		return written;
	}
}
