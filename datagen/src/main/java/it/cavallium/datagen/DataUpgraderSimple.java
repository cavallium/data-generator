package it.cavallium.datagen;

import org.jetbrains.annotations.NotNull;

public abstract class DataUpgraderSimple<T, U> implements DataUpgrader<DataContextNone, T, U> {

	@Override
	public final @NotNull U upgrade(@NotNull DataContextNone context, @NotNull T data) {
		return this.upgrade(data);
	}

	public abstract @NotNull U upgrade(@NotNull T data);
}
