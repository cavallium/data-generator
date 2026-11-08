package it.cavallium.datagen.plugin.fuzz;

import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataInitializer;
import it.cavallium.datagen.DataUpgrader;

/**
 * Instance-field locations referenced by fuzz schemas ({@code initializerInstance} /
 * {@code upgraderInstance}): the generator emits {@code FuzzInstanceLocations.LONG} as the
 * initializer/upgrader expression, so these must be live constants of the right functional type.
 */
public final class FuzzInstanceLocations {

	public static final DataInitializer<DataContextNone, Long> LONG = context -> 41L;

	public static final DataUpgrader<DataContextNone, Integer, Long> INT_TO_LONG =
			(context, oldData) -> oldData.longValue() + 1000L;

	private FuzzInstanceLocations() {
	}
}
