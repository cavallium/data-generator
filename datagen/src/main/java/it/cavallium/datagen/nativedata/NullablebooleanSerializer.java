package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullablebooleanSerializer implements DataSerializer<Nullableboolean> {

	public static final NullablebooleanSerializer INSTANCE = new NullablebooleanSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullableboolean data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			boolean dataContent = data.get();
			dataOutput.writeBoolean(dataContent);
		}
	}

	@NotNull
	@Override
	public Nullableboolean deserialize(SafeDataInput dataInput) {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullableboolean.empty();
		} else {
			return Nullableboolean.of(dataInput.readBoolean());
		}
	}
}
