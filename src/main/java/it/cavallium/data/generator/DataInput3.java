package it.cavallium.data.generator;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

public class DataInput3 extends DataInputStream {

	private final byte[] buffer;

	/**
	 * Creates a DataInputStream that uses the specified underlying InputStream.
	 *
	 * @param in the specified input stream
	 */
	public DataInput3(byte[] in) {
		super(new ByteArrayInputStream(in));
		this.buffer = in;
	}

	public byte[] asBytes() {
		return buffer;
	}
}
