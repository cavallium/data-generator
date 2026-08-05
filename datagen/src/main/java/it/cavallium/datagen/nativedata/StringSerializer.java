package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;

public class StringSerializer implements DataCodec<String> {

	public static final StringSerializer INSTANCE = new StringSerializer();

	@Override
	public void serialize(SafeDataOutput dataOutput, @NotNull String data) {
		dataOutput.writeMediumText(data, StandardCharsets.UTF_8);
	}

	@NotNull
	@Override
	public String read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterRoot();
		try {
			return dataInput.readMediumText(StandardCharsets.UTF_8);
		} finally {
			dataInput.decodeBudget().exitRoot();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterRoot();
		try {
			ProjectionReadSupport.skipPayload(dataInput, ProjectionReadSupport.readLength(dataInput));
		} finally {
			dataInput.decodeBudget().exitRoot();
		}
	}
}
