package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataContext;
import it.cavallium.datagen.DataContextNone;
import it.cavallium.datagen.DataUpgrader;
import it.cavallium.datagen.DataUpgraderSimple;

import java.lang.reflect.Array;

public class UpgradeUtil {
	public static <A, B> B[] upgradeArray(A[] from, Class<? extends B[]> targetArrayType,
			DataUpgraderSimple<A, B> upgrader) {
		return upgradeArray(DataContextNone.INSTANCE, from, targetArrayType, null, upgrader);
	}

	public static <A, B> B[] upgradeArray(A[] from, Class<? extends B[]> targetArrayType,
			B[] emptyTarget, DataUpgraderSimple<A, B> upgrader) {
		return upgradeArray(DataContextNone.INSTANCE, from, targetArrayType, emptyTarget, upgrader);
	}

	public static <C extends DataContext, A, B> B[] upgradeArray(C context, A[] from,
			Class<? extends B[]> targetArrayType, DataUpgrader<C, A, B> upgrader) {
		return upgradeArray(context, from, targetArrayType, null, upgrader);
	}

	public static <C extends DataContext, A, B> B[] upgradeArray(C context, A[] from,
			Class<? extends B[]> targetArrayType, B[] emptyTarget, DataUpgrader<C, A, B> upgrader) {
		if (from.length == 0 && emptyTarget != null) {
			return emptyTarget;
		}
		@SuppressWarnings("unchecked")
		B[] result = (B[]) Array.newInstance(targetArrayType.getComponentType(), from.length);
		for (int i = 0; i < from.length; i++) {
			result[i] = upgrader.upgrade(context, from[i]);
		}
		return result;
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
