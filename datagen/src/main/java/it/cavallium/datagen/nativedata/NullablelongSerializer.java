package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class NullablelongSerializer implements DataSerializer<Nullablelong> {

	public static final NullablelongSerializer INSTANCE = new NullablelongSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull Nullablelong data) {
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
	public Nullablelong deserialize(SafeDataInput dataInput) {
		var isPresent = dataInput.readBoolean();
		if (!isPresent) {
			return Nullablelong.empty();
		} else {
			return Nullablelong.of(dataInput.readLong());
		}
	}
}
