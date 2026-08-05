package it.cavallium.datagen.benchmark;

import it.cavallium.datagen.benchmark.fixture.v0.upgraders.ContextOptimizedRootUpgrader.ReadInputPlaceholder;
import it.cavallium.datagen.benchmark.fixture.v0.upgraders.ContextOptimizedRootUpgrader.ReadUpgraderPlaceholder;

/** Returns a used context directly in its current structural representation. */
public final class BenchmarkContextPayloadReadUpgrader implements ReadUpgraderPlaceholder {

	@Override
	public it.cavallium.datagen.benchmark.fixture.current.data.Payload upgrade(ReadInputPlaceholder input) {
		return input.currentContextPayload();
	}
}
