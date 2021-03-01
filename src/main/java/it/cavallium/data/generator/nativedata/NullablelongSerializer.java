package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class NullablelongSerializer implements DataSerializer<Nullablelong> {

	public static final NullablelongSerializer INSTANCE = new NullablelongSerializer();

	@Override
	public void serialize(DataOutput dataOutput, @NotNull Nullablelong data) throws IOException {
		if (data.isEmpty()) {
			dataOutput.writeBoolean(false);
		} else {
			dataOutput.writeBoolean(true);
			long dataContent = data.get();
			dataOutput.writeLong(dataContent);
		}
	}

	@NotNull
	@Override
	public Nullablelong deserialize(DataInput dataInput) throws IOException {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullablelong.empty();
		} else {
			return Nullablelong.of(dataInput.readLong());
		}
	}
}
