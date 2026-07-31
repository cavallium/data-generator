package it.cavallium.datagen.plugin;

import it.cavallium.datagen.DataContext;
import it.cavallium.datagen.DataUpgrader;

@SuppressWarnings("rawtypes")
public final class TestContextIntToLongUpgrader implements DataUpgrader {

	@Override
	public Object upgrade(DataContext context, Object data) {
		try {
			long messageId = (long) context.getClass().getMethod("messageId").invoke(context);
			return ((Number) data).longValue() + messageId;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
