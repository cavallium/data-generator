package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class NullablebooleanSerializer implements DataSerializer<Nullableboolean> {

	public static final NullablebooleanSerializer INSTANCE = new NullablebooleanSerializer();

	@Override
	public void serialize(DataOutput dataOutput, @NotNull Nullableboolean data) throws IOException {
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
	public Nullableboolean deserialize(DataInput dataInput) throws IOException {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullableboolean.empty();
		} else {
			return Nullableboolean.of(dataInput.readBoolean());
		}
	}
}
