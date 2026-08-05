package it.cavallium.datagen.benchmark;

import it.cavallium.datagen.DataContext;
import it.cavallium.datagen.DataUpgrader;

/** Object-path fallback that must stay cold in random-access benchmark lanes. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class BenchmarkOpaqueUpgrader implements DataUpgrader {

	@Override
	public Object upgrade(DataContext context, Object value) {
		throw new AssertionError("declarative or typed-view benchmark entered the object fallback");
	}
}
