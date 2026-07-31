package it.cavallium.datagen.plugin;

import it.cavallium.datagen.DataSkipper;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.stream.SafeDataInput;

public final class TestStringSkipper implements DataSkipper {

	@Override
	public void skip(SafeDataInput input) {
		ProjectionReadSupport.skipBytes(input, input.readInt());
	}
}
