package it.cavallium.datagen;

import org.jetbrains.annotations.NotNull;

public abstract class DataInitializerSimple<T> implements DataInitializer<DataContextNone, T> {

	@Override
	public final @NotNull T initialize(DataContextNone context) {
		return initialize();
	}

	public abstract @NotNull T initialize();
}
