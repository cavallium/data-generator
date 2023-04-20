package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import it.unimi.dsi.fastutil.floats.FloatList;
import org.jetbrains.annotations.NotNull;

public class ArrayfloatSerializer implements DataSerializer<FloatList> {

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull FloatList data) {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeFloat(data.getFloat(i));
		}
	}

	@NotNull
	@Override
	public FloatList deserialize(SafeDataInput dataInput) {
		var data = new float[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readFloat();
		}
		return FloatList.of(data);
	}
}
