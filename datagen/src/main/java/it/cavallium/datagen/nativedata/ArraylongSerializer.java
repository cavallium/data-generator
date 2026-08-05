package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class ArraylongSerializer implements DataCodec<long[]> {

	private static final long[] EMPTY = new long[0];
	public static long[] emptyArray() { return EMPTY; }

	@Override
	public void serialize(SafeDataOutput dataOutput, long @NotNull [] data) {
		dataOutput.writeInt(data.length);
		for (long value : data) {
			dataOutput.writeLong(value);
		}
	}

	@NotNull
	@Override
	public long[] read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int size = ProjectionReadSupport.readLength(dataInput);
			if (size == 0) return EMPTY;
			return ProjectionReadSupport.readLongArray(dataInput, size);
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			ProjectionReadSupport.skipFixedArray(dataInput, 8);
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}
}
