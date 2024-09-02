package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataContext;
import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataUpgrader;
import it.cavallium.datagen.DataUpgraderSimple;

import java.util.List;

public class UpgradeUtil {
	public static <A, B> List<B> upgradeArray(List<A> from, DataUpgraderSimple<A, B> upgrader) {
		return upgradeArray(DataContextNone.INSTANCE, from, upgrader);
	}

	@SuppressWarnings("unchecked")
	public static <C extends DataContext, A, B> List<B> upgradeArray(C context, List<A> from, DataUpgrader<C, A, B> upgrader) {
		Object[] array;
		if (from.getClass() == ImmutableWrappedArrayList.class
				&& ((ImmutableWrappedArrayList<?>) from).a.getClass() == Object[].class) {
			array = ((ImmutableWrappedArrayList<?>) from).a;
		} else {
			array = from.toArray();
		}
		for (int i = 0; i < array.length; i++) {
			array[i] = (B) upgrader.upgrade(context, (A) array[i]);
		}
		return (ImmutableWrappedArrayList<B>) ImmutableWrappedArrayList.of(array);
	}

	public static <A, B> B upgradeNullable(A nullableValue, DataUpgraderSimple<A, B> upgrader) {
		return upgradeNullable(DataContextNone.INSTANCE, nullableValue, upgrader);
	}

	public static <C extends DataContext, A, B> B upgradeNullable(C context, A nullableValue, DataUpgrader<C, A, B> upgrader) {
		if (nullableValue == null) {
			return null;
		} else {
			return upgrader.upgrade(context, nullableValue);
		}
	}
}
