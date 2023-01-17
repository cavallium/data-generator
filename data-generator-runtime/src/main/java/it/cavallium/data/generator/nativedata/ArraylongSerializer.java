package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.unimi.dsi.fastutil.longs.LongList;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArraylongSerializer implements DataSerializer<LongList> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull LongList data) throws IOException {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeLong(data.getLong(i));
		}
	}

	@NotNull
	@Override
	public LongList deserialize(DataInput dataInput) throws IOException {
		var data = new long[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readLong();
		}
		return LongList.of(data);
	}
}
