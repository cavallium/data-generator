package it.cavallium.datagen.plugin;

import it.cavallium.datagen.DataContext;
import it.cavallium.datagen.DataInitializer;
import it.cavallium.datagen.nativedata.Nullablelong;

@SuppressWarnings("rawtypes")
public final class TestNullableLongInitializer implements DataInitializer {

	@Override
	public Object initialize(DataContext context) {
		try {
			return Nullablelong.of((long) context.getClass().getMethod("messageId").invoke(context) + 7L);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
