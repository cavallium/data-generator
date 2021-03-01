package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class NullablebyteSerializer implements DataSerializer<Nullablebyte> {

	public static final NullablebyteSerializer INSTANCE = new NullablebyteSerializer();

	@Override
	public void serialize(DataOutput dataOutput, @NotNull Nullablebyte data) throws IOException {
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
	public Nullablebyte deserialize(DataInput dataInput) throws IOException {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullablebyte.empty();
		} else {
			return Nullablebyte.of(dataInput.readByte());
		}
	}
}
