package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.datagen.ValueTooLargeException;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class ArrayBinaryStringSerializer implements DataCodec<BinaryString[]> {

	private static final BinaryString[] EMPTY = new BinaryString[0];
	public static BinaryString[] emptyArray() { return EMPTY; }

	@Override
	public void serialize(SafeDataOutput dataOutput, BinaryString @NotNull [] data) {
		for (BinaryString item : data) {
			if (item.sizeBytes() > 0xffff) {
				throw new ValueTooLargeException("BinaryString array element too long for unsigned-short prefix: "
						+ item.sizeBytes() + " bytes");
			}
		}
		dataOutput.writeInt(data.length);
		for (BinaryString item : data) {
			dataOutput.writeShort(item.sizeBytes());
			dataOutput.write(item.data());
		}
	}

	@NotNull
	@Override
	public BinaryString[] read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int size = ProjectionReadSupport.readLength(dataInput);
			ProjectionReadSupport.prepareArrayAllocation(dataInput, size, Short.BYTES);
			if (size == 0) return EMPTY;
			var data = new BinaryString[size];
			for (int i = 0; i < data.length; i++) {
				var len = dataInput.readUnsignedShort();
				ProjectionReadSupport.preparePayload(dataInput, len);
				byte[] stringData = new byte[len];
				dataInput.readFully(stringData);
				data[i] = new BinaryString(stringData);
			}
			return data;
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int size = ProjectionReadSupport.readLength(dataInput);
			ProjectionReadSupport.prepareArrayAllocation(dataInput, size, Short.BYTES);
			for (int i = 0; i < size; i++) {
				ProjectionReadSupport.skipPayload(dataInput, dataInput.readUnsignedShort());
			}
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}
}
