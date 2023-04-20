package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullablefloatSerializer implements DataSerializer<Nullablefloat> {

	public static final NullablefloatSerializer INSTANCE = new NullablefloatSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullablefloat data) {
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
	public Nullablefloat deserialize(SafeDataInput dataInput) {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullablefloat.empty();
		} else {
			return Nullablefloat.of(dataInput.readFloat());
		}
	}
}
