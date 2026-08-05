package it.cavallium.datagen.plugin;

import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataUpgrader;

/** Materialized fallback matching the declarative primitive-array map. */
public final class TortureIntArrayToLongArrayUpgrader
		implements DataUpgrader<DataContextNone, int[], long[]> {

	@Override
	public long[] upgrade(DataContextNone context, int[] values) {
		long[] result = new long[values.length];
		for (int i = 0; i < values.length; i++) result[i] = TortureTransforms.widen(values[i]);
		return result;
	}
}
