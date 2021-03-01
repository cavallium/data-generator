package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArraylongSerializer implements DataSerializer<long[]> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull long[] data) throws IOException {
		dataOutput.writeInt(data.length);
		for (int i = 0; i < data.length; i++) {
			dataOutput.writeLong(data[i]);
		}
	}

	@NotNull
	@Override
	public long[] deserialize(DataInput dataInput) throws IOException {
		var data = new long[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readLong();
		}
		return data;
	}
}
