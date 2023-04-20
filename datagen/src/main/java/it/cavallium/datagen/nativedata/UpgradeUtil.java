package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataUpgrader;
import java.util.List;

public class UpgradeUtil {
	@SuppressWarnings("unchecked")
	public static <A, B> List<B> upgradeArray(List<A> from, DataUpgrader<A, B> upgrader) {
		Object[] array;
		if (from.getClass() == ImmutableWrappedArrayList.class
				&& ((ImmutableWrappedArrayList<?>) from).a.getClass() == Object[].class) {
			array = ((ImmutableWrappedArrayList<?>) from).a;
		} else {
			array = from.toArray();
		}
		for (int i = 0; i < array.length; i++) {
			array[i] = (B) upgrader.upgrade((A) array[i]);
		}
		return (ImmutableWrappedArrayList<B>) new ImmutableWrappedArrayList<>(array);
	}

	public static <A, B> B upgradeNullable(A nullableValue, DataUpgrader<A, B> upgrader) {
		if (nullableValue == null) {
			return null;
		} else {
			return upgrader.upgrade(nullableValue);
		}
	}
}
