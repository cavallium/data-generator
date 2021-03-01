package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArrayStringSerializer implements DataSerializer<String[]> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull String[] data) throws IOException {
		dataOutput.writeInt(data.length);
		for (int i = 0; i < data.length; i++) {
			dataOutput.writeUTF(data[i]);
		}
	}

	@NotNull
	@Override
	public String[] deserialize(DataInput dataInput) throws IOException {
		var data = new String[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readUTF();
		}
		return data;
	}
}
