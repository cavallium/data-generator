package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class NullableInt52Serializer implements DataSerializer<NullableInt52> {

	public static final NullableInt52Serializer INSTANCE = new NullableInt52Serializer();

	@Override
	public void serialize(DataOutput dataOutput, @NotNull NullableInt52 data) throws IOException {
		// 0b10000000 = empty, 0b00000000 = with value
		if (data.isEmpty()) {
			dataOutput.writeByte(0b10000000);
		} else {
			dataOutput.write(Int52Serializer.toByteArray(data.get().getValue()));
		}
	}

	@NotNull
	@Override
	public NullableInt52 deserialize(DataInput dataInput) throws IOException {
		// 0b10000000 = empty, 0b00000000 = with value
		byte firstByteAndIsPresent = dataInput.readByte();
		if ((firstByteAndIsPresent & 0b10000000) != 0) {
			return NullableInt52.empty();
		} else {
			byte[] secondPart = new byte[7];
			secondPart[0] = (byte) (firstByteAndIsPresent & 0b00001111);
			dataInput.readFully(secondPart, 1, secondPart.length - 1);
			return NullableInt52.of(Int52.fromLong(Int52Serializer.fromByteArray(secondPart)));
		}
	}
}
