package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

public class NullableStringSerializer implements DataSerializer<NullableString> {

	public static final NullableStringSerializer INSTANCE = new NullableStringSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull NullableString data) {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			String dataContent = data.get();
			dataOutput.writeShortText(dataContent, StandardCharsets.UTF_8);
		}
	}

	@NotNull
	@Override
	public NullableString deserialize(SafeDataInput dataInput) {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return NullableString.empty();
		} else {
			return NullableString.of(dataInput.readShortText(StandardCharsets.UTF_8));
		}
	}
}
