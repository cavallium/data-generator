package it.cavallium.datagen;

import org.jetbrains.annotations.NotNull;

public interface DataUpgrader<C extends DataContext, T, U> {

	@NotNull U upgrade(@NotNull C context, @NotNull T data);
}
