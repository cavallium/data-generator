package it.cavallium.datagen.benchmark;

import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataUpgrader;
import it.cavallium.datagen.benchmark.fixture.v0.upgraders.ContextOpaqueRootUpgrader.ContextPlaceholder;

/** Materializing context comparison for {@link BenchmarkContextPayloadReadUpgrader}. */
public final class BenchmarkOpaqueContextPayloadUpgrader implements DataUpgrader<ContextPlaceholder,
		Integer, it.cavallium.datagen.benchmark.fixture.v1.data.Payload> {

	private static final BenchmarkPayloadUpgrader PAYLOAD_UPGRADER = new BenchmarkPayloadUpgrader();

	@Override
	public it.cavallium.datagen.benchmark.fixture.v1.data.Payload upgrade(ContextPlaceholder context,
			Integer ignored) {
		return PAYLOAD_UPGRADER.upgrade(DataContextNone.INSTANCE, context.payload());
	}
}
