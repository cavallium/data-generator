package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArrayInt52Serializer implements DataSerializer<Int52[]> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull Int52[] data) throws IOException {
		dataOutput.writeInt(data.length);
		for (int i = 0; i < data.length; i++) {
			Int52Serializer.INSTANCE.serialize(dataOutput, data[i]);
		}
	}

	@NotNull
	@Override
	public Int52[] deserialize(DataInput dataInput) throws IOException {
		var data = new Int52[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = Int52Serializer.INSTANCE.deserialize(dataInput);
		}
		return data;
	}
}
