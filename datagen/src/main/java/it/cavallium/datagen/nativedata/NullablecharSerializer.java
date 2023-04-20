package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullablecharSerializer implements DataSerializer<Nullablechar> {

	public static final NullablecharSerializer INSTANCE = new NullablecharSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullablechar data) {
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
	public Nullablechar deserialize(SafeDataInput dataInput) {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullablechar.empty();
		} else {
			return Nullablechar.of(dataInput.readChar());
		}
	}
}
