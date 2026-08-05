package it.cavallium.datagen.plugin;

import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataUpgrader;

public final class TestSimpleIntToLongUpgrader implements DataUpgrader<DataContextNone, Integer, Long> {

	@Override
	public Long upgrade(DataContextNone context, Integer oldData) {
		return oldData.longValue() + 1000L;
	}
}
