package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import it.unimi.dsi.fastutil.booleans.BooleanList;
import org.jetbrains.annotations.NotNull;

public class ArraybooleanSerializer implements DataSerializer<BooleanList> {

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull BooleanList data) {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeBoolean(data.getBoolean(i));
		}
	}

	@NotNull
	@Override
	public BooleanList deserialize(SafeDataInput dataInput) {
		var data = new boolean[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readBoolean();
		}
		return BooleanList.of(data);
	}
}
