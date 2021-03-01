package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArraybooleanSerializer implements DataSerializer<boolean[]> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull boolean[] data) throws IOException {
		dataOutput.writeInt(data.length);
		for (int i = 0; i < data.length; i++) {
			dataOutput.writeBoolean(data[i]);
		}
	}

	@NotNull
	@Override
	public boolean[] deserialize(DataInput dataInput) throws IOException {
		var data = new boolean[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readBoolean();
		}
		return data;
	}
}
