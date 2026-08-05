package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.datagen.ValueTooLargeException;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class BinaryStringSerializer implements DataCodec<BinaryString> {

	public static final BinaryStringSerializer INSTANCE = new BinaryStringSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull BinaryString data) {
		dataOutput.writeInt(data.sizeBytes());
		dataOutput.write(data.data());
	}

	@NotNull
	@Override
	public BinaryString read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterRoot();
		try {
			var size = ProjectionReadSupport.readLength(dataInput);
			ProjectionReadSupport.preparePayload(dataInput, size);
			byte[] bytes = new byte[size];
			dataInput.readFully(bytes);
			return new BinaryString(bytes);
		} finally {
			dataInput.decodeBudget().exitRoot();
		}
	}

	public static void writeShort(SafeDataOutput output, BinaryString value) {
		int size = validateShort(value);
		output.writeShort(size);
		output.write(value.data());
	}

	public static int validateShort(BinaryString value) {
		int size = value.sizeBytes();
		if (size > 0xffff) {
			throw new ValueTooLargeException("BinaryString too long for unsigned-short prefix: "
					+ size + " bytes");
		}
		return size;
	}

	public static BinaryString readShort(SafeDataInput input) {
		int size = input.readUnsignedShort();
		ProjectionReadSupport.preparePayload(input, size);
		byte[] bytes = new byte[size];
		input.readFully(bytes);
		return new BinaryString(bytes);
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterRoot();
		try {
			ProjectionReadSupport.skipPayload(dataInput, ProjectionReadSupport.readLength(dataInput));
		} finally {
			dataInput.decodeBudget().exitRoot();
		}
	}
}
