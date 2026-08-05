package it.cavallium.datagen.benchmark;

import it.cavallium.buffer.RandomAccessDataInput;
import it.cavallium.datagen.FixedDataCodec;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.datagen.ReadSession;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;

/** Fixed custom codec whose warmed reserved path performs one direct absolute load. */
public final class BenchmarkFixedIntCodec implements FixedDataCodec<Integer> {

	@Override
	public int fixedSize() {
		return Integer.BYTES;
	}

	@Override
	public void serialize(SafeDataOutput output, Integer value) {
		output.writeInt(value);
	}

	@Override
	public Integer read(SafeDataInput input) {
		return input.readInt();
	}

	@Override
	public void skip(SafeDataInput input) {
		ProjectionReadSupport.skipBytes(input, Integer.BYTES);
	}

	@Override
	public ReadSession<Integer> newReadSession() {
		return new Session();
	}

	private static final class Session extends ReadSession<Integer> {

		@Override
		protected Integer decode(SafeDataInput input) {
			return input.readInt();
		}

		@Override
		protected void skipValue(SafeDataInput input) {
			ProjectionReadSupport.skipBytes(input, Integer.BYTES);
		}

		@Override
		protected Integer decodeReserved(RandomAccessDataInput input, int offset, int length) {
			if (length != Integer.BYTES) {
				throw new MalformedDataException("FixedInt reserved length must be 4, got " + length);
			}
			return input.getIntAt(offset);
		}

		@Override
		protected void clearTransientState() {
			// Direct absolute loads retain no transient source or result reference.
		}
	}
}
