package it.cavallium.data.generator;

import org.jetbrains.annotations.NotNull;

public interface DataInitializer<T> {

	@NotNull T initialize();
}
