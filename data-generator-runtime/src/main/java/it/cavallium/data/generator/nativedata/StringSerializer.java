package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;

public class StringSerializer implements DataSerializer<String> {

	public static final StringSerializer INSTANCE = new StringSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull String data) {
		dataOutput.writeMediumText(data, StandardCharsets.UTF_8);
	}

	@NotNull
	@Override
	public String deserialize(SafeDataInput dataInput) {
		return dataInput.readMediumText(StandardCharsets.UTF_8);
	}
}
