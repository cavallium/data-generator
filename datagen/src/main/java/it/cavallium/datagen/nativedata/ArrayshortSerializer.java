package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import it.unimi.dsi.fastutil.shorts.ShortList;
import org.jetbrains.annotations.NotNull;

public class ArrayshortSerializer implements DataSerializer<ShortList> {

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull ShortList data) {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeShort(data.getShort(i));
		}
	}

	@NotNull
	@Override
	public ShortList deserialize(SafeDataInput dataInput) {
		var data = new short[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readShort();
		}
		return ShortList.of(data);
	}
}
