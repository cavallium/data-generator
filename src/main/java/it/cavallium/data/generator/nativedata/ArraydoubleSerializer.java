package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArraydoubleSerializer implements DataSerializer<double[]> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull double[] data) throws IOException {
		dataOutput.writeInt(data.length);
		for (int i = 0; i < data.length; i++) {
			dataOutput.writeDouble(data[i]);
		}
	}

	@NotNull
	@Override
	public double[] deserialize(DataInput dataInput) throws IOException {
		var data = new double[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readDouble();
		}
		return data;
	}
}
