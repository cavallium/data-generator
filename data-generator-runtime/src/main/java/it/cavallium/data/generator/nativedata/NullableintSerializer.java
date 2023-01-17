package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class NullableintSerializer implements DataSerializer<Nullableint> {

	public static final NullableintSerializer INSTANCE = new NullableintSerializer();

	@Override
	public void serialize(DataOutput dataOutput, @NotNull Nullableint data) throws IOException {
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
	public Nullableint deserialize(DataInput dataInput) throws IOException {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullableint.empty();
		} else {
			return Nullableint.of(dataInput.readInt());
		}
	}
}
