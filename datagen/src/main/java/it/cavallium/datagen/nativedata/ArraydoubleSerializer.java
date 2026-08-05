package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class ArraydoubleSerializer implements DataCodec<double[]> {

	private static final double[] EMPTY = new double[0];
	public static double[] emptyArray() { return EMPTY; }

	@Override
	public void serialize(SafeDataOutput dataOutput, double @NotNull [] data) {
		dataOutput.writeInt(data.length);
		for (double value : data) {
			dataOutput.writeDouble(value);
		}
	}

	@NotNull
	@Override
	public double[] read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int size = ProjectionReadSupport.readLength(dataInput);
			if (size == 0) return EMPTY;
			return ProjectionReadSupport.readDoubleArray(dataInput, size);
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
