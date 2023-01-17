package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArraydoubleSerializer implements DataSerializer<DoubleList> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull DoubleList data) throws IOException {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeDouble(data.getDouble(i));
		}
	}

	@NotNull
	@Override
	public DoubleList deserialize(DataInput dataInput) throws IOException {
		var data = new double[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readDouble();
		}
		return DoubleList.of(data);
	}
}
