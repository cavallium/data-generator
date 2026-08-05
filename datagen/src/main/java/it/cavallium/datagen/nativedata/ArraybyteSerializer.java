package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class ArraybyteSerializer implements DataCodec<byte[]> {

	private static final byte[] EMPTY = new byte[0];
	public static byte[] emptyArray() { return EMPTY; }

	@Override
	public void serialize(SafeDataOutput dataOutput, byte @NotNull [] data) {
		dataOutput.writeInt(data.length);
		dataOutput.write(data);
	}

	@NotNull
	@Override
	public byte[] read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int size = ProjectionReadSupport.readLength(dataInput);
			if (size == 0) return EMPTY;
			return ProjectionReadSupport.readByteArray(dataInput, size);
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			ProjectionReadSupport.skipFixedArray(dataInput, 1);
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}
}
