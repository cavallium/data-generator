package it.cavallium.stream;

import java.io.Closeable;
import java.io.DataInput;
import java.nio.charset.Charset;

import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;

/**
 * A data input stream lets an application read primitive Java data
 * types from an underlying input stream in a machine-independent
 * way. An application uses a data output stream to write data that
 * can later be read by a data input stream.
 * <p>
 * DataInputStream is not necessarily safe for multithreaded access.
 * Thread safety is optional and is the responsibility of users of
 * methods in this class.
 *
 * @author  Arthur van Hoff
 * @see     java.io.DataOutputStream
 * @since   1.0
 */
public interface SafeDataInput extends Closeable, DataInput {

	/**
	 * Reads some number of bytes from the contained input stream and
	 * stores them into the buffer array {@code b}. The number of
	 * bytes actually read is returned as an integer. This method blocks
	 * until input data is available, end of file is detected, or an
	 * exception is thrown.
	 *
	 * <p>If {@code b} is null, a {@code NullPointerException} is
	 * thrown. If the length of {@code b} is zero, then no bytes are
	 * read and {@code 0} is returned; otherwise, there is an attempt
	 * to read at least one byte. If no byte is available because the
	 * stream is at end of file, the value {@code -1} is returned;
	 * otherwise, at least one byte is read and stored into {@code b}.
	 *
	 * <p>The first byte read is stored into element {@code b[0]}, the
	 * next one into {@code b[1]}, and so on. The number of bytes read
	 * is, at most, equal to the length of {@code b}. Let {@code k}
	 * be the number of bytes actually read; these bytes will be stored in
	 * elements {@code b[0]} through {@code b[k-1]}, leaving
	 * elements {@code b[k]} through {@code b[b.length-1]}
	 * unaffected.
	 *
	 * <p>The {@code read(b)} method has the same effect as:
	 * <blockquote><pre>
	 * read(b, 0, b.length)
	 * </pre></blockquote>
	 *
	 * @param      b   the buffer into which the data is read.
	 * @return     the total number of bytes read into the buffer, or
	 *             {@code -1} if there is no more data because the end
	 *             of the stream has been reached.
	 * @see        SafeFilterInputStream#in
	 * @see        java.io.InputStream#read(byte[], int, int)
	 */
	int read(byte[] b);

	/**
	 * Reads up to {@code len} bytes of data from the contained
	 * input stream into an array of bytes.  An attempt is made to read
	 * as many as {@code len} bytes, but a smaller number may be read,
	 * possibly zero. The number of bytes actually read is returned as an
	 * integer.
	 *
	 * <p> This method blocks until input data is available, end of file is
	 * detected, or an exception is thrown.
	 *
	 * <p> If {@code len} is zero, then no bytes are read and
	 * {@code 0} is returned; otherwise, there is an attempt to read at
	 * least one byte. If no byte is available because the stream is at end of
	 * file, the value {@code -1} is returned; otherwise, at least one
	 * byte is read and stored into {@code b}.
	 *
	 * <p> The first byte read is stored into element {@code b[off]}, the
	 * next one into {@code b[off+1]}, and so on. The number of bytes read
	 * is, at most, equal to {@code len}. Let <i>k</i> be the number of
	 * bytes actually read; these bytes will be stored in elements
	 * {@code b[off]} through {@code b[off+}<i>k</i>{@code -1]},
	 * leaving elements {@code b[off+}<i>k</i>{@code ]} through
	 * {@code b[off+len-1]} unaffected.
	 *
	 * <p> In every case, elements {@code b[0]} through
	 * {@code b[off]} and elements {@code b[off+len]} through
	 * {@code b[b.length-1]} are unaffected.
	 *
	 * @param      b     the buffer into which the data is read.
	 * @param      off the start offset in the destination array {@code b}
	 * @param      len   the maximum number of bytes read.
	 * @return     the total number of bytes read into the buffer, or
	 *             {@code -1} if there is no more data because the end
	 *             of the stream has been reached.
	 * @throws     NullPointerException If {@code b} is {@code null}.
	 * @throws     IndexOutOfBoundsException If {@code off} is negative,
	 *             {@code len} is negative, or {@code len} is greater than
	 *             {@code b.length - off}
	 * @see        SafeFilterInputStream#in
	 * @see        java.io.InputStream#read(byte[], int, int)
	 */
	int read(byte[] b, int off, int len);

	void readFully(byte @NotNull [] b);

	void readFully(byte @NotNull [] b, int off, int len);

	int skipBytes(int n);

	boolean readBoolean();

	byte readByte();

	int readUnsignedByte();

	short readShort();

	int readUnsignedShort();

	char readChar();

	int readInt();

	long readLong();

	/**
	 * See the general contract of the {@code readInt52}
	 * method of {@code DataInput}.
	 * <p>
	 * Bytes
	 * for this operation are read from the contained
	 * input stream.
	 *
	 * @return     the next seven bytes of this input stream, interpreted as a
	 *             {@code Int52}.
	 * @see        SafeFilterInputStream#in
	 */
	long readInt52();

	float readFloat();

	double readDouble();

	@Deprecated
	String readLine();

	@Deprecated
	default @NotNull String readUTF() {
		return readShortText(StandardCharsets.UTF_8);
	}

	default @NotNull String readShortText(Charset charset) {
		var length = this.readUnsignedShort();
		return this.readString(length, charset);
	}

	default @NotNull String readMediumText(Charset charset) {
		var length = this.readInt();
		return this.readString(length, charset);
	}

	@NotNull String readString(int len, Charset charset);
}
