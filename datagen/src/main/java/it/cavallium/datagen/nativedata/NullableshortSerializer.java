package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullableshortSerializer implements DataSerializer<Nullableshort> {

	public static final NullableshortSerializer INSTANCE = new NullableshortSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullableshort data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			short dataContent = data.get();
			dataOutput.writeShort(dataContent);
		}
	}

	@NotNull
	@Override
	public Nullableshort deserialize(SafeDataInput dataInput) {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullableshort.empty();
		} else {
			return Nullableshort.of(dataInput.readShort());
		}
	}
}
