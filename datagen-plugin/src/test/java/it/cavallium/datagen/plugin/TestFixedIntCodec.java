package it.cavallium.datagen.plugin;

import it.cavallium.datagen.FixedDataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;

public final class TestFixedIntCodec implements FixedDataCodec<Integer> {

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
		return input.readInt();
	}

	@Override
	public void skip(SafeDataInput input) {
		ProjectionReadSupport.skipBytes(input, Integer.BYTES);
	}
}
