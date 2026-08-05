package it.cavallium.buffer;

import it.cavallium.stream.SafeByteArrayInputStream;
import java.lang.foreign.MemorySegment;

/** Forces the direct cursor's generic-Buf storage path in the optional Vector module tests. */
public final class VectorFallbackBuf extends ByteListBuf {

	public VectorFallbackBuf(byte[] data) {
		super(data);
	}

	@Override
	public byte[] getBackingByteArrayStrict() {
		return null;
	}

	@Override
	public MemorySegment asMemorySegmentStrict() {
		return null;
	}

	@Override
	public byte[] getBackingByteArray() {
		throw new AssertionError("Vector fallback requested heap storage");
	}

	@Override
	public byte[] asArray() {
		throw new AssertionError("Vector fallback copied the complete payload");
	}

	@Override
	public SafeByteArrayInputStream binaryInputStream() {
		throw new AssertionError("Vector fallback opened a payload stream");
	}
}
