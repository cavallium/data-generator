package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class ArrayfloatSerializer implements DataCodec<float[]> {

	private static final float[] EMPTY = new float[0];
	public static float[] emptyArray() { return EMPTY; }

	@Override
	public void serialize(SafeDataOutput dataOutput, float @NotNull [] data) {
		dataOutput.writeInt(data.length);
		for (float value : data) {
			dataOutput.writeFloat(value);
		}
	}

	@NotNull
	@Override
	public float[] read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int size = ProjectionReadSupport.readLength(dataInput);
			if (size == 0) return EMPTY;
			return ProjectionReadSupport.readFloatArray(dataInput, size);
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
