package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullableInt52Serializer implements DataCodec<NullableInt52> {

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
	public NullableInt52 read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			// 0b10000000 = empty, 0b00000000 = with value
			byte firstByteAndIsPresent = dataInput.readByte();
			if ((firstByteAndIsPresent & 0b10000000) != 0) {
				return NullableInt52.empty();
			} else {
				return NullableInt52.of(Int52Serializer.readValue(firstByteAndIsPresent, dataInput));
			}
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int first = dataInput.readUnsignedByte();
			if ((first & 0x80) == 0) {
				ProjectionReadSupport.skipBytes(dataInput, 6);
			}
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}
}
