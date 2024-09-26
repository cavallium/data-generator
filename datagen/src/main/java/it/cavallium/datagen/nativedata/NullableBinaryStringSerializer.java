package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

public class NullableBinaryStringSerializer implements DataSerializer<NullableBinaryString> {

	public static final NullableBinaryStringSerializer INSTANCE = new NullableBinaryStringSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull NullableBinaryString data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			BinaryString dataContent = data.get();
			dataOutput.writeShort(dataContent.sizeBytes());
			dataOutput.write(dataContent.data());
		}
	}

	@NotNull
	@Override
	public NullableBinaryString deserialize(SafeDataInput dataInput) {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return NullableBinaryString.empty();
		} else {
			var size = dataInput.readUnsignedShort();
			var data = new byte[size];
			dataInput.readFully(data);
			return NullableBinaryString.of(new BinaryString(data));
		}
	}
}
