package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArraycharSerializer implements DataSerializer<char[]> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull char[] data) throws IOException {
		dataOutput.writeInt(data.length);
		for (int i = 0; i < data.length; i++) {
			dataOutput.writeChar(data[i]);
		}
	}

	@NotNull
	@Override
	public char[] deserialize(DataInput dataInput) throws IOException {
		var data = new char[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readChar();
		}
		return data;
	}
}
