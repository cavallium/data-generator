package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataUpgrader;
import java.util.List;

public class UpgradeUtil {
	@SuppressWarnings("unchecked")
	public static <A, B> List<B> upgradeArray(List<A> from, DataUpgrader<A, B> upgrader) {
		Object[] array;
		if (from instanceof ImmutableWrappedArrayList<A> immutableWrappedArrayList) {
			array = immutableWrappedArrayList.a;
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
