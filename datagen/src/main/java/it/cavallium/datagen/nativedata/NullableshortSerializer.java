package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullableshortSerializer implements DataCodec<Nullableshort> {

	public static final NullableshortSerializer INSTANCE = new NullableshortSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullableshort data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			short dataContent = data.get();
			dataOutput.writeShort(dataContent);
		}
	}

	@NotNull
	@Override
	public Nullableshort read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			var isPresent = dataInput.readBoolean();
			if (!isPresent) {
				return Nullableshort.empty();
			} else {
				return Nullableshort.of(dataInput.readShort());
			}
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			ProjectionReadSupport.skipNullableFixed(dataInput, 2);
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}
}
