package it.cavallium.datagen.plugin;

import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataUpgrader;
import it.cavallium.datagen.nativedata.Nullableint;
import it.cavallium.datagen.nativedata.Nullablelong;

/** Materialized fallback matching the declarative nullable primitive map. */
public final class TortureNullableIntToLongUpgrader
		implements DataUpgrader<DataContextNone, Nullableint, Nullablelong> {

	@Override
	public Nullablelong upgrade(DataContextNone context, Nullableint value) {
		return value.isPresent()
				? Nullablelong.of(TortureTransforms.widen(value.get()))
				: Nullablelong.empty();
	}
}
