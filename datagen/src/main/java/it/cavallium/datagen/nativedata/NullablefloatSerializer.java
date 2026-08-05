package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullablefloatSerializer implements DataCodec<Nullablefloat> {

	public static final NullablefloatSerializer INSTANCE = new NullablefloatSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullablefloat data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			float dataContent = data.get();
			dataOutput.writeFloat(dataContent);
		}
	}

	@NotNull
	@Override
	public Nullablefloat read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			var isPresent = dataInput.readBoolean();
			if (!isPresent) {
				return Nullablefloat.empty();
			} else {
				return Nullablefloat.of(dataInput.readFloat());
			}
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			ProjectionReadSupport.skipNullableFixed(dataInput, 4);
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}
}
