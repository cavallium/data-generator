package it.cavallium.stream;

import it.cavallium.buffer.IgnoreCoverage;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

class NullOutputStream extends SafeOutputStream {

	private volatile boolean closed;

	@IgnoreCoverage
	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("Stream closed");
		}
	}

	@IgnoreCoverage
	@Override
	public void write(int b) {
		ensureOpen();
	}

	@IgnoreCoverage
	@Override
	public void write(byte @NotNull [] b, int off, int len) {
		Objects.checkFromIndexSize(off, len, b.length);
		ensureOpen();
	}

	@IgnoreCoverage
	@Override
	public void close() {
		closed = true;
	}
}
