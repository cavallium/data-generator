package it.cavallium.datagen.plugin;

import it.cavallium.datagen.DataContext;
import it.cavallium.datagen.DataUpgrader;
import it.cavallium.datagen.nativedata.Nullablelong;

@SuppressWarnings("rawtypes")
public final class TestIntToNullableLongUpgrader implements DataUpgrader {

	@Override
	public Object upgrade(DataContext context, Object data) {
		try {
			long messageId = (long) context.getClass().getMethod("messageId").invoke(context);
			return Nullablelong.of(((Number) data).longValue() + messageId);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
