package it.cavallium.datagen;

import org.jetbrains.annotations.NotNull;

public interface DataInitializer<C extends DataContext, T> {

	@NotNull T initialize(C context);
}
