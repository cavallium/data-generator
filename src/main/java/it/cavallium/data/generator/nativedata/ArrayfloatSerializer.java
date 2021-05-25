package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.unimi.dsi.fastutil.floats.FloatList;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArrayfloatSerializer implements DataSerializer<FloatList> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull FloatList data) throws IOException {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeFloat(data.getFloat(i));
		}
	}

	@NotNull
	@Override
	public FloatList deserialize(DataInput dataInput) throws IOException {
		var data = new float[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readFloat();
		}
		return FloatList.of(data);
	}
}
