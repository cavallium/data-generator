package it.cavallium.datagen;

import org.jetbrains.annotations.NotNull;

public interface DataUpgrader<T, U> {

	@NotNull U upgrade(@NotNull T data);
}
