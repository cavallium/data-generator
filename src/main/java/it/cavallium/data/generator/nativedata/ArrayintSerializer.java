package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.unimi.dsi.fastutil.ints.IntList;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArrayintSerializer implements DataSerializer<IntList> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull IntList data) throws IOException {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeInt(data.getInt(i));
		}
	}

	@NotNull
	@Override
	public IntList deserialize(DataInput dataInput) throws IOException {
		var data = new int[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readInt();
		}
		return IntList.of(data);
	}
}
