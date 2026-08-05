package it.cavallium.datagen.plugin;

import it.cavallium.datagen.DataContext;
import it.cavallium.datagen.DataUpgrader;

/** Object-path counterpart of {@link TortureTransforms#widenWithAnchor(int, long)}. */
@SuppressWarnings("rawtypes")
public final class TortureContextIntToLongUpgrader implements DataUpgrader {

	@Override
	public Object upgrade(DataContext context, Object value) {
		try {
			long anchor = (long) context.getClass().getMethod("anchor").invoke(context);
			return TortureTransforms.widenWithAnchor(((Number) value).intValue(), anchor);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
