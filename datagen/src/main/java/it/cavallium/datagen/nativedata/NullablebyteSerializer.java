package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullablebyteSerializer implements DataCodec<Nullablebyte> {

	public static final NullablebyteSerializer INSTANCE = new NullablebyteSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullablebyte data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			byte dataContent = data.get();
			dataOutput.writeByte(dataContent);
		}
	}

	@NotNull
	@Override
	public Nullablebyte read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			var isPresent = dataInput.readBoolean();
			if (!isPresent) {
				return Nullablebyte.empty();
			} else {
				return Nullablebyte.of(dataInput.readByte());
			}
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			ProjectionReadSupport.skipNullableFixed(dataInput, 1);
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}
}
