package it.cavallium.datagen.plugin;

import it.cavallium.datagen.DataContext;
import it.cavallium.datagen.DataInitializer;

@SuppressWarnings("rawtypes")
public final class TestContextLongInitializer implements DataInitializer {

	@Override
	public Object initialize(DataContext context) {
		try {
			return (long) context.getClass().getMethod("messageId").invoke(context) + 5L;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
