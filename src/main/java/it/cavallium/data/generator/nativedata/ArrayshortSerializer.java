package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArrayshortSerializer implements DataSerializer<ShortList> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull ShortList data) throws IOException {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeShort(data.getShort(i));
		}
	}

	@NotNull
	@Override
	public ShortList deserialize(DataInput dataInput) throws IOException {
		var data = new short[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readShort();
		}
		return ShortList.of(data);
	}
}
