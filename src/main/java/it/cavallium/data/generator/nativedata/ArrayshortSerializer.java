package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArrayshortSerializer implements DataSerializer<short[]> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull short[] data) throws IOException {
		dataOutput.writeInt(data.length);
		for (int i = 0; i < data.length; i++) {
			dataOutput.writeShort(data[i]);
		}
	}

	@NotNull
	@Override
	public short[] deserialize(DataInput dataInput) throws IOException {
		var data = new short[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readShort();
		}
		return data;
	}
}
