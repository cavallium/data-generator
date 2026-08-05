package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class ArrayintSerializer implements DataCodec<int[]> {

	private static final int[] EMPTY = new int[0];
	public static int[] emptyArray() { return EMPTY; }

	@Override
	public void serialize(SafeDataOutput dataOutput, int @NotNull [] data) {
		dataOutput.writeInt(data.length);
		for (int value : data) {
			dataOutput.writeInt(value);
		}
	}

	@NotNull
	@Override
	public int[] read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int size = ProjectionReadSupport.readLength(dataInput);
			if (size == 0) return EMPTY;
			return ProjectionReadSupport.readIntArray(dataInput, size);
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			ProjectionReadSupport.skipFixedArray(dataInput, 4);
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}
}
