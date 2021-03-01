package it.cavallium.data.generator;

import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public interface DataInitializer<T> {

	@NotNull T initialize() throws IOException;
}
