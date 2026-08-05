package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullableintSerializer implements DataCodec<Nullableint> {

	public static final NullableintSerializer INSTANCE = new NullableintSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullableint data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			int dataContent = data.get();
			dataOutput.writeInt(dataContent);
		}
	}

	@NotNull
	@Override
	public Nullableint read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			var isPresent = dataInput.readBoolean();
			if (!isPresent) {
				return Nullableint.empty();
			} else {
				return Nullableint.of(dataInput.readInt());
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
