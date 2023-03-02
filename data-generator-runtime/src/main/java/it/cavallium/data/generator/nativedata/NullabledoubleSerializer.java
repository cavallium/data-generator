package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullabledoubleSerializer implements DataSerializer<Nullabledouble> {

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
	public Nullabledouble deserialize(SafeDataInput dataInput) {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullabledouble.empty();
		} else {
			return Nullabledouble.of(dataInput.readDouble());
		}
	}
}
