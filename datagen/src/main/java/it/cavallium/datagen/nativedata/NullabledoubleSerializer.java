package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullabledoubleSerializer implements DataCodec<Nullabledouble> {

	public static final NullabledoubleSerializer INSTANCE = new NullabledoubleSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullabledouble data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			double dataContent = data.get();
			dataOutput.writeDouble(dataContent);
		}
	}

	@NotNull
	@Override
	public Nullabledouble read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			var isPresent = dataInput.readBoolean();
			if (!isPresent) {
				return Nullabledouble.empty();
			} else {
				return Nullabledouble.of(dataInput.readDouble());
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
