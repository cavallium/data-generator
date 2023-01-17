package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class NullableshortSerializer implements DataSerializer<Nullableshort> {

	public static final NullableshortSerializer INSTANCE = new NullableshortSerializer();

	@Override
	public void serialize(DataOutput dataOutput, @NotNull Nullableshort data) throws IOException {
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
	public Nullableshort deserialize(DataInput dataInput) throws IOException {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullableshort.empty();
		} else {
			return Nullableshort.of(dataInput.readShort());
		}
	}
}
