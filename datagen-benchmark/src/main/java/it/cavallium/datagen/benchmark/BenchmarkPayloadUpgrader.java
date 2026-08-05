package it.cavallium.datagen.benchmark;

import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataUpgrader;

/** Object-path fallback used as the materializing comparison in the generated benchmark. */
public final class BenchmarkPayloadUpgrader implements DataUpgrader<DataContextNone,
		it.cavallium.datagen.benchmark.fixture.v0.data.Payload,
		it.cavallium.datagen.benchmark.fixture.v1.data.Payload> {

	@Override
	public it.cavallium.datagen.benchmark.fixture.v1.data.Payload upgrade(DataContextNone context,
			it.cavallium.datagen.benchmark.fixture.v0.data.Payload value) {
		var items = new it.cavallium.datagen.benchmark.fixture.v1.data.Item[value.itemsSize()];
		for (int i = 0; i < items.length; i++) {
			items[i] = it.cavallium.datagen.benchmark.fixture.v1.data.Item.of(
					value.items(i).value(), 17L);
		}
		return it.cavallium.datagen.benchmark.fixture.v1.data.Payload.unsafeOfOwned(items, value.label());
	}
}
