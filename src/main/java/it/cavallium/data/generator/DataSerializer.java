package it.cavallium.data.generator;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public interface DataSerializer<T> {

	void serialize(DataOutput dataOutput, @NotNull T data) throws IOException;

	@NotNull T deserialize(DataInput dataInput) throws IOException;
}
