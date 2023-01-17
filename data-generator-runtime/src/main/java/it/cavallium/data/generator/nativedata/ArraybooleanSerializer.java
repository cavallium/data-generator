package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.unimi.dsi.fastutil.booleans.BooleanList;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArraybooleanSerializer implements DataSerializer<BooleanList> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull BooleanList data) throws IOException {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeBoolean(data.getBoolean(i));
		}
	}

	@NotNull
	@Override
	public BooleanList deserialize(DataInput dataInput) throws IOException {
		var data = new boolean[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readBoolean();
		}
		return BooleanList.of(data);
	}
}
