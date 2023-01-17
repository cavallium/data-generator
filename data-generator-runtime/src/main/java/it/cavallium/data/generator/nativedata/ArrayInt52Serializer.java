package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public class ArrayInt52Serializer implements DataSerializer< List<Int52>> {

	@Override
	public void serialize(DataOutput dataOutput, List<Int52> data) throws IOException {
		dataOutput.writeInt(data.size());
		for (Int52 datum : data) {
			Int52Serializer.INSTANCE.serialize(dataOutput, datum);
		}
	}

	@NotNull
	@Override
	public List<Int52> deserialize(DataInput dataInput) throws IOException {
		var data = new Int52[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = Int52Serializer.INSTANCE.deserialize(dataInput);
		}
		return List.of(data);
	}
}
