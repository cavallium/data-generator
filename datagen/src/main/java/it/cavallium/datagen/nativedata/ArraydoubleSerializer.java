package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import org.jetbrains.annotations.NotNull;

public class ArraydoubleSerializer implements DataSerializer<DoubleList> {

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull DoubleList data) {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeDouble(data.getDouble(i));
		}
	}

	@NotNull
	@Override
	public DoubleList deserialize(SafeDataInput dataInput) {
		var data = new double[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readDouble();
		}
		return DoubleList.of(data);
	}
}
