package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class NullableStringSerializer implements DataSerializer<NullableString> {

	public static final NullableStringSerializer INSTANCE = new NullableStringSerializer();

	@Override
	public void serialize(DataOutput dataOutput, @NotNull NullableString data) throws IOException {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			String dataContent = data.get();
			dataOutput.writeUTF(dataContent);
		}
	}

	@NotNull
	@Override
	public NullableString deserialize(DataInput dataInput) throws IOException {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return NullableString.empty();
		} else {
			return NullableString.of(dataInput.readUTF());
		}
	}
}
