package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullablebyteSerializer implements DataSerializer<Nullablebyte> {

	public static final NullablebyteSerializer INSTANCE = new NullablebyteSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullablebyte data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			byte dataContent = data.get();
			dataOutput.writeByte(dataContent);
		}
	}

	@NotNull
	@Override
	public Nullablebyte deserialize(SafeDataInput dataInput) {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullablebyte.empty();
		} else {
			return Nullablebyte.of(dataInput.readByte());
		}
	}
}
