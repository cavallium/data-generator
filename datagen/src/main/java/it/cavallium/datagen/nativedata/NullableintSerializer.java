package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullableintSerializer implements DataSerializer<Nullableint> {

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
	public Nullableint deserialize(SafeDataInput dataInput) {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullableint.empty();
		} else {
			return Nullableint.of(dataInput.readInt());
		}
	}
}
