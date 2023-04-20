package it.cavallium.datagen;

import org.jetbrains.annotations.NotNull;

public interface DataInitializer<T> {

	@NotNull T initialize();
}
