package it.cavallium.datagen.plugin;

import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataUpgrader;

/** Deliberately opaque custom-value boundary used by the adversarial history. */
public final class TortureStringUpgrader implements DataUpgrader<DataContextNone, String, String> {

	@Override
	public String upgrade(DataContextNone context, String value) {
		return value + "#opaque";
	}
}
