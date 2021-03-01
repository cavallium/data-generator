package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class NullablefloatSerializer implements DataSerializer<Nullablefloat> {

	public static final NullablefloatSerializer INSTANCE = new NullablefloatSerializer();

	@Override
	public void serialize(DataOutput dataOutput, @NotNull Nullablefloat data) throws IOException {
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
	public Nullablefloat deserialize(DataInput dataInput) throws IOException {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullablefloat.empty();
		} else {
			return Nullablefloat.of(dataInput.readFloat());
		}
	}
}
