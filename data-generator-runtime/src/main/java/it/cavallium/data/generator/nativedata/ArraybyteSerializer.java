package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import it.unimi.dsi.fastutil.bytes.ByteList;
import org.jetbrains.annotations.NotNull;

public class ArraybyteSerializer implements DataSerializer<ByteList> {

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull ByteList data) {
		dataOutput.writeInt(data.size());
		for (int i = 0; i < data.size(); i++) {
			dataOutput.writeByte(data.getByte(i));
		}
	}

	@NotNull
	@Override
	public ByteList deserialize(SafeDataInput dataInput) {
		var data = new byte[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			data[i] = dataInput.readByte();
		}
		return ByteList.of(data);
	}
}
