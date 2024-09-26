package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ArrayBinaryStringSerializer implements DataSerializer<List<BinaryString>> {

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull List<BinaryString> data) {
		dataOutput.writeInt(data.size());
		for (BinaryString item : data) {
			dataOutput.writeShort(item.sizeBytes());
			dataOutput.write(item.data());
		}
	}

	@NotNull
	@Override
	public List<BinaryString> deserialize(SafeDataInput dataInput) {
		var data = new BinaryString[dataInput.readInt()];
		for (int i = 0; i < data.length; i++) {
			var len = dataInput.readUnsignedShort();
			byte[] stringData = new byte[len];
			dataInput.readFully(stringData);
			data[i] = new BinaryString(stringData);
		}
		return List.of(data);
	}
}
