package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import it.unimi.dsi.fastutil.longs.LongList;
import org.jetbrains.annotations.NotNull;

public class ArraylongSerializer implements DataSerializer<LongList> {

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull LongList data) {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeLong(data.getLong(i));
		}
	}

	@NotNull
	@Override
	public LongList deserialize(SafeDataInput dataInput) {
		var data = new long[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readLong();
		}
		return LongList.of(data);
	}
}
