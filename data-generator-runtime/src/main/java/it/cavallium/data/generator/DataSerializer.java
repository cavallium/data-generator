package it.cavallium.data.generator;

import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public interface DataSerializer<T> {

	void serialize(SafeDataOutput dataOutput, @NotNull T data);

	@NotNull T deserialize(SafeDataInput dataInput);
}
