package it.cavallium.datagen.plugin;

import it.cavallium.datagen.FixedDataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;

/** Fixed-size codec with a deterministic failure sentinel for reader-reuse tests. */
public final class TortureExplodingIntCodec implements FixedDataCodec<Integer> {

	public static final int GOOD_VALUE = 0x1357_2468;
	public static final int FAILURE_VALUE = 0x8BAD_F00D;

	@Override
	public int fixedSize() {
		return Integer.BYTES;
	}

	@Override
	public void serialize(SafeDataOutput output, Integer data) {
		output.writeInt(data);
	}

	@Override
	public Integer read(SafeDataInput input) {
		int value = input.readInt();
		if (value == FAILURE_VALUE) {
			throw new IllegalStateException("deliberate adversarial custom-codec failure");
		}
		return value;
	}

	@Override
	public void skip(SafeDataInput input) {
		ProjectionReadSupport.skipBytes(input, Integer.BYTES);
	}
}
