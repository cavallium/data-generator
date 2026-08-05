package it.cavallium.datagen.benchmark;

import it.cavallium.datagen.DataContext;
import it.cavallium.datagen.DataInitializer;

/** Object-path fallback that must stay cold in random-access benchmark lanes. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class BenchmarkOpaqueInitializer implements DataInitializer {

	@Override
	public Object initialize(DataContext context) {
		throw new AssertionError("declarative benchmark entered the object initializer fallback");
	}
}
