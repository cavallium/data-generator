package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullableInt52Serializer implements DataSerializer<NullableInt52> {

	public static final NullableInt52Serializer INSTANCE = new NullableInt52Serializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull NullableInt52 data) {
		// 0b10000000 = empty, 0b00000000 = with value
		if (data.isEmpty()) {
			dataOutput.writeByte(0b10000000);
		} else {
			dataOutput.write(Int52Serializer.toByteArray(data.get().getValue()));
		}
	}

	@NotNull
	@Override
	public NullableInt52 deserialize(SafeDataInput dataInput) {
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
