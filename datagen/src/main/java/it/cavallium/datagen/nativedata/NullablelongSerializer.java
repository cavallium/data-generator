package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullablelongSerializer implements DataCodec<Nullablelong> {

	public static final NullablelongSerializer INSTANCE = new NullablelongSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullablelong data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			long dataContent = data.get();
			dataOutput.writeLong(dataContent);
		}
	}

	@NotNull
	@Override
	public Nullablelong read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			var isPresent = dataInput.readBoolean();
			if (!isPresent) {
				return Nullablelong.empty();
			} else {
				return Nullablelong.of(dataInput.readLong());
			}
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			ProjectionReadSupport.skipNullableFixed(dataInput, 8);
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}
}
