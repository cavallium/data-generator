package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullablecharSerializer implements DataCodec<Nullablechar> {

	public static final NullablecharSerializer INSTANCE = new NullablecharSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullablechar data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			char dataContent = data.get();
			dataOutput.writeChar(dataContent);
		}
	}

	@NotNull
	@Override
	public Nullablechar read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			var isPresent = dataInput.readBoolean();
			if (!isPresent) {
				return Nullablechar.empty();
			} else {
				return Nullablechar.of(dataInput.readChar());
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
