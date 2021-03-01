package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArrayintSerializer implements DataSerializer<int[]> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull int[] data) throws IOException {
		dataOutput.writeInt(data.length);
		for (int i = 0; i < data.length; i++) {
			dataOutput.writeInt(data[i]);
		}
	}

	@NotNull
	@Override
	public int[] deserialize(DataInput dataInput) throws IOException {
		var data = new int[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readInt();
		}
		return data;
	}
}
