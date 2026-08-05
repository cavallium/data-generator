package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.datagen.ValueTooLargeException;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

public class NullableBinaryStringSerializer implements DataCodec<NullableBinaryString> {

	public static final NullableBinaryStringSerializer INSTANCE = new NullableBinaryStringSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull NullableBinaryString data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			BinaryString dataContent = data.get();
			int size = dataContent.sizeBytes();
			if (size > 0xffff) {
				throw new ValueTooLargeException("BinaryString too long for unsigned-short prefix: "
						+ size + " bytes");
			}
			dataOutput.writeBoolean(true);
			dataOutput.writeShort(size);
			dataOutput.write(dataContent.data());
		}
	}

	@NotNull
	@Override
	public NullableBinaryString read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			var isPresent = dataInput.readBoolean();
			if (!isPresent) {
				return NullableBinaryString.empty();
			} else {
				var size = dataInput.readUnsignedShort();
				ProjectionReadSupport.preparePayload(dataInput, size);
				var data = new byte[size];
				dataInput.readFully(data);
				return NullableBinaryString.of(new BinaryString(data));
			}
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			if (dataInput.readBoolean()) {
				ProjectionReadSupport.skipPayload(dataInput, dataInput.readUnsignedShort());
			}
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}
}
