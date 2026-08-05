package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;

import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;

public class ArrayStringSerializer implements DataCodec<String[]> {

	private static final String[] EMPTY = new String[0];
	public static String[] emptyArray() { return EMPTY; }

	@Override
	public void serialize(SafeDataOutput dataOutput, String @NotNull [] data) {
		dataOutput.writeInt(data.length);
		for (String item : data) {
			dataOutput.writeShortText(item, StandardCharsets.UTF_8);
		}
	}

	@NotNull
	@Override
	public String[] read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int size = ProjectionReadSupport.readLength(dataInput);
			ProjectionReadSupport.prepareArrayAllocation(dataInput, size, Short.BYTES);
			if (size == 0) return EMPTY;
			var data = new String[size];
			for (int i = 0; i < data.length; i++) {
				data[i] = dataInput.readShortText(StandardCharsets.UTF_8);
			}
			return data;
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int size = ProjectionReadSupport.readLength(dataInput);
			ProjectionReadSupport.prepareArrayAllocation(dataInput, size, Short.BYTES);
			for (int i = 0; i < size; i++) {
				ProjectionReadSupport.skipPayload(dataInput, dataInput.readUnsignedShort());
			}
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}
}
