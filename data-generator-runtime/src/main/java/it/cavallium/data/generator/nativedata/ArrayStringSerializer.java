package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public class ArrayStringSerializer implements DataSerializer<List<String>> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull List<String> data) throws IOException {
		dataOutput.writeInt(data.size());
		for (String item : data) {
			dataOutput.writeUTF(item);
		}
	}

	@NotNull
	@Override
	public List<String> deserialize(DataInput dataInput) throws IOException {
		var data = new String[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readUTF();
		}
		return List.of(data);
	}
}
