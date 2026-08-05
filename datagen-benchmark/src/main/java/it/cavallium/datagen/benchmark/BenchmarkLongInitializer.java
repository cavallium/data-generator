package it.cavallium.datagen.benchmark;

import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataInitializer;

public final class BenchmarkLongInitializer implements DataInitializer<DataContextNone, Long> {

	@Override
	public Long initialize(DataContextNone context) {
		return 17L;
	}
}
