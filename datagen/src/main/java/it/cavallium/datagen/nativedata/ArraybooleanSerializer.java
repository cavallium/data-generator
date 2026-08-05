package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class ArraybooleanSerializer implements DataCodec<boolean[]> {

	private static final boolean[] EMPTY = new boolean[0];
	public static boolean[] emptyArray() { return EMPTY; }

	@Override
	public void serialize(SafeDataOutput dataOutput, boolean @NotNull [] data) {
		dataOutput.writeInt(data.length);
		for (boolean value : data) {
			dataOutput.writeBoolean(value);
		}
	}

	@NotNull
	@Override
	public boolean[] read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int size = ProjectionReadSupport.readLength(dataInput);
			if (size == 0) return EMPTY;
			return ProjectionReadSupport.readBooleanArray(dataInput, size);
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
