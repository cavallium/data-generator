package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class NullabledoubleSerializer implements DataSerializer<Nullabledouble> {

	public static final NullabledoubleSerializer INSTANCE = new NullabledoubleSerializer();

	@Override
	public void serialize(DataOutput dataOutput, @NotNull Nullabledouble data) throws IOException {
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
	public Nullabledouble deserialize(DataInput dataInput) throws IOException {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullabledouble.empty();
		} else {
			return Nullabledouble.of(dataInput.readDouble());
		}
	}
}
