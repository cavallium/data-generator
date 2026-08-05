package it.cavallium.datagen.benchmark;

import it.cavallium.datagen.benchmark.fixture.v0.upgraders.OptimizedRootUpgrader.ReadInputPayload;
import it.cavallium.datagen.benchmark.fixture.v0.upgraders.OptimizedRootUpgrader.ReadUpgraderPayload;

/** Requests the generator's fused source-to-current value without hand-decoding the wire format. */
public final class BenchmarkPayloadReadUpgrader implements ReadUpgraderPayload {

	@Override
	public it.cavallium.datagen.benchmark.fixture.current.data.Payload upgrade(ReadInputPayload input) {
		return input.currentValue();
	}
}
