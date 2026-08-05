package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class ArraycharSerializer implements DataCodec<char[]> {

	private static final char[] EMPTY = new char[0];
	public static char[] emptyArray() { return EMPTY; }

	@Override
	public void serialize(SafeDataOutput dataOutput, char @NotNull [] data) {
		dataOutput.writeInt(data.length);
		for (char value : data) {
			dataOutput.writeChar(value);
		}
	}

	@NotNull
	@Override
	public char[] read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int size = ProjectionReadSupport.readLength(dataInput);
			if (size == 0) return EMPTY;
			return ProjectionReadSupport.readCharArray(dataInput, size);
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
