package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class NullablecharSerializer implements DataSerializer<Nullablechar> {

	public static final NullablecharSerializer INSTANCE = new NullablecharSerializer();

	@Override
	public void serialize(DataOutput dataOutput, @NotNull Nullablechar data) throws IOException {
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
	public Nullablechar deserialize(DataInput dataInput) throws IOException {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullablechar.empty();
		} else {
			return Nullablechar.of(dataInput.readChar());
		}
	}
}
