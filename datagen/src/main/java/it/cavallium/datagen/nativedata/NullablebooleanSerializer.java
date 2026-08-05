package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullablebooleanSerializer implements DataCodec<Nullableboolean> {

	public static final NullablebooleanSerializer INSTANCE = new NullablebooleanSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullableboolean data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			boolean dataContent = data.get();
			dataOutput.writeBoolean(dataContent);
		}
	}

	@NotNull
	@Override
	public Nullableboolean read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			var isPresent = dataInput.readBoolean();
			if (!isPresent) {
				return Nullableboolean.empty();
			} else {
				return Nullableboolean.of(dataInput.readBoolean());
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
