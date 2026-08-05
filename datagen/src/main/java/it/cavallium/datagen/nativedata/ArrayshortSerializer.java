package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class ArrayshortSerializer implements DataCodec<short[]> {

	private static final short[] EMPTY = new short[0];
	public static short[] emptyArray() { return EMPTY; }

	@Override
	public void serialize(SafeDataOutput dataOutput, short @NotNull [] data) {
		dataOutput.writeInt(data.length);
		for (short value : data) {
			dataOutput.writeShort(value);
		}
	}

	@NotNull
	@Override
	public short[] read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int size = ProjectionReadSupport.readLength(dataInput);
			if (size == 0) return EMPTY;
			return ProjectionReadSupport.readShortArray(dataInput, size);
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			ProjectionReadSupport.skipFixedArray(dataInput, 2);
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}
}
