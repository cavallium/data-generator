package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public class ArrayStringSerializer implements DataSerializer<List<String>> {

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull List<String> data) {
		dataOutput.writeInt(data.size());
		for (String item : data) {
			dataOutput.writeShortText(item, StandardCharsets.UTF_8);
		}
	}

	@NotNull
	@Override
	public List<String> deserialize(SafeDataInput dataInput) {
		var data = new String[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readShortText(StandardCharsets.UTF_8);
		}
		return List.of(data);
	}
}
