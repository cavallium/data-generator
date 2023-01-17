package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.unimi.dsi.fastutil.bytes.ByteList;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class ArraybyteSerializer implements DataSerializer<ByteList> {

	@Override
	public void serialize(DataOutput dataOutput, @NotNull ByteList data) throws IOException {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeByte(data.getByte(i));
		}
	}

	@NotNull
	@Override
	public ByteList deserialize(DataInput dataInput) throws IOException {
		var data = new byte[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readByte();
		}
		return ByteList.of(data);
	}
}
