package it.cavallium.datagen.plugin;

import it.cavallium.datagen.DataContext;
import it.cavallium.datagen.DataInitializer;

/** Object-path counterpart of {@link TortureTransforms#initialize(long)}. */
@SuppressWarnings("rawtypes")
public final class TortureContextLongInitializer implements DataInitializer {

	@Override
	public Object initialize(DataContext context) {
		return TortureTransforms.initialize(anchor(context));
	}

	private static long anchor(DataContext context) {
		try {
			return (long) context.getClass().getMethod("anchor").invoke(context);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
