package it.cavallium.stream;


import it.cavallium.buffer.IgnoreCoverage;
import org.jetbrains.annotations.NotNull;

/**
 * This class is the superclass of all classes that filter output
 * streams. These streams sit on top of an already existing output
 * stream (the <i>underlying</i> output stream) which it uses as its
 * basic sink of data, but possibly transforming the data along the
 * way or providing additional functionality.
 * <p>
 * The class {@code FilterOutputStream} itself simply overrides
 * all methods of {@code SafeOutputStream} with versions that pass
 * all requests to the underlying output stream. Subclasses of
 * {@code FilterOutputStream} may further override some of these
 * methods as well as provide additional methods and fields.
 *
 * @author  Jonathan Payne
 * @since   1.0
 */
public class SafeFilterOutputStream extends SafeOutputStream {
	/**
	 * The underlying output stream to be filtered.
	 */
	protected final SafeOutputStream out;

	/**
	 * Creates an output stream filter built on top of the specified
	 * underlying output stream.
	 *
	 * @param   out   the underlying output stream to be assigned to
	 *                the field {@code this.out} for later use, or
	 *                {@code null} if this instance is to be
	 *                created without an underlying stream.
	 */
	public SafeFilterOutputStream(SafeOutputStream out) {
		this.out = out;
	}

	/**
	 * Writes the specified {@code byte} to this output stream.
	 * <p>
	 * The {@code write} method of {@code FilterOutputStream}
	 * calls the {@code write} method of its underlying output stream,
	 * that is, it performs {@code out.write(b)}.
	 * <p>
	 * Implements the abstract {@code write} method of {@code SafeOutputStream}.
	 *
	 * @param      b   the {@code byte}.
	 */
	@Override
	@IgnoreCoverage
	public void write(int b) {
		out.write(b);
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
	@IgnoreCoverage
	@Override
	public void write(byte @NotNull [] b) {
		out.write(b);
	}

	/**
	 * Writes {@code len} bytes from the specified
	 * {@code byte} array starting at offset {@code off} to
	 * this output stream.
	 * <p>
	 * The {@code write} method of {@code FilterOutputStream}
	 * calls the {@code write} method of one argument on each
	 * {@code byte} to output.
	 * <p>
	 * Note that this method does not call the {@code write} method
	 * of its underlying output stream with the same arguments. Subclasses
	 * of {@code FilterOutputStream} should provide a more efficient
	 * implementation of this method.
	 *
	 * @param      b     the data.
	 * @param      off   the start offset in the data.
	 * @param      len   the number of bytes to write.
	 * @see        SafeFilterOutputStream#write(int)
	 */
	@IgnoreCoverage
	@Override
	public void write(byte[] b, int off, int len) {
		out.write(b, off, len);
	}

	/**
	 * Flushes this output stream and forces any buffered output bytes
	 * to be written out to the stream.
	 * <p>
	 * The {@code flush} method of {@code FilterOutputStream}
	 * calls the {@code flush} method of its underlying output stream.
	 *
	 * @see        SafeFilterOutputStream#out
	 */
	@IgnoreCoverage
	@Override
	public void flush() {
		out.flush();
	}

	/**
	 * Closes this output stream and releases any system resources
	 * associated with the stream.
	 * <p>
	 * When not already closed, the {@code close} method of {@code
	 * FilterOutputStream} calls its {@code flush} method, and then
	 * calls the {@code close} method of its underlying output stream.
	 *
	 * @see        SafeFilterOutputStream#flush()
	 * @see        SafeFilterOutputStream#out
	 */
	@IgnoreCoverage
	@Override
	public void close() {
		out.close();
	}
}
