package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public class ArrayInt52Serializer implements DataSerializer< List<Int52>> {

	@Override
	public void serialize(SafeDataOutput dataOutput, List<Int52> data) {
		dataOutput.writeInt(data.size());
		for (Int52 item : data) {
			Int52Serializer.INSTANCE.serialize(dataOutput, item);
		}
	}

	@NotNull
	@Override
	public List<Int52> deserialize(SafeDataInput dataInput) {
		var data = new Int52[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = Int52Serializer.INSTANCE.deserialize(dataInput);
		}
		return List.of(data);
	}
}
