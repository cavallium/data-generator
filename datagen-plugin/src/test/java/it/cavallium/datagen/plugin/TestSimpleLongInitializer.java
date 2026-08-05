package it.cavallium.datagen.plugin;

import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataInitializer;

public final class TestSimpleLongInitializer implements DataInitializer<DataContextNone, Long> {

	@Override
	public Long initialize(DataContextNone context) {
		return 123L;
	}
}
