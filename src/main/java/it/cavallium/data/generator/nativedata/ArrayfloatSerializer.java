package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArrayfloatSerializer implements DataSerializer<float[]> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull float[] data) throws IOException {
		dataOutput.writeInt(data.length);
		for (int i = 0; i < data.length; i++) {
			dataOutput.writeFloat(data[i]);
		}
	}

	@NotNull
	@Override
	public float[] deserialize(DataInput dataInput) throws IOException {
		var data = new float[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readFloat();
		}
		return data;
	}
}
