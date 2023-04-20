package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import it.unimi.dsi.fastutil.ints.IntList;
import org.jetbrains.annotations.NotNull;

public class ArrayintSerializer implements DataSerializer<IntList> {

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull IntList data) {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeInt(data.getInt(i));
		}
	}

	@NotNull
	@Override
	public IntList deserialize(SafeDataInput dataInput) {
		var data = new int[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readInt();
		}
		return IntList.of(data);
	}
}
