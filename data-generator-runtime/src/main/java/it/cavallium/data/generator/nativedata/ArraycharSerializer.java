package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.unimi.dsi.fastutil.chars.CharList;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArraycharSerializer implements DataSerializer<CharList> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull CharList data) throws IOException {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeChar(data.getChar(i));
		}
	}

	@NotNull
	@Override
	public CharList deserialize(DataInput dataInput) throws IOException {
		var data = new char[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readChar();
		}
		return CharList.of(data);
	}
}
