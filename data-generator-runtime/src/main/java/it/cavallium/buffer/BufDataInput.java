package it.cavallium.buffer;

import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeDataInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;


public class BufDataInput extends SafeDataInputStream {

	/**
	 * Creates a DataInputStream that uses the specified underlying InputStream.
	 *
	 * @param in the specified input stream
	 */
	private BufDataInput(@NotNull SafeByteArrayInputStream in) {
		super(in);
	}

	public static BufDataInput create(Buf byteList) {
		return new BufDataInput(byteList.binaryInputStream());
	}

	@Deprecated
	@Override
	public void close() {
	}

	@Override
	public void mark(int readlimit) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void reset() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean markSupported() {
		return false;
	}

	@Override
	public @NotNull String readUTF() {
		var length = this.readUnsignedShort();
		this.skipNBytes(length);
		return this.in.readString(length, StandardCharsets.UTF_8);
	}

	@Override
	public String readString(int length, Charset charset) {
		return in.readString(length, charset);
	}
}
