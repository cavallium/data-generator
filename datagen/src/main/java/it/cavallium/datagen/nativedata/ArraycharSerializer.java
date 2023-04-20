package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import it.unimi.dsi.fastutil.chars.CharList;
import org.jetbrains.annotations.NotNull;

public class ArraycharSerializer implements DataSerializer<CharList> {

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull CharList data) {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeChar(data.getChar(i));
		}
	}

	@NotNull
	@Override
	public CharList deserialize(SafeDataInput dataInput) {
		var data = new char[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readChar();
		}
		return CharList.of(data);
	}
}
