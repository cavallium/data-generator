package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;

public class StringSerializer implements DataSerializer<String> {

	public static final StringSerializer INSTANCE = new StringSerializer();

	@Override
	public void serialize(DataOutput dataOutput, @NotNull String data) throws IOException {
		byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
		dataOutput.writeInt(bytes.length);
		dataOutput.write(bytes);
	}

	@NotNull
	@Override
	public String deserialize(DataInput dataInput) throws IOException {
		byte[] bytes = new byte[dataInput.readInt()];
		dataInput.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
