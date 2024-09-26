package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class BinaryStringSerializer implements DataSerializer<BinaryString> {

	public static final BinaryStringSerializer INSTANCE = new BinaryStringSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull BinaryString data) {
		dataOutput.writeInt(data.sizeBytes());
		dataOutput.write(data.data());
	}

	@NotNull
	@Override
	public BinaryString deserialize(SafeDataInput dataInput) {
		var size = dataInput.readInt();
		byte[] bytes = new byte[size];
		dataInput.readFully(bytes);
		return new BinaryString(bytes);
	}
}
