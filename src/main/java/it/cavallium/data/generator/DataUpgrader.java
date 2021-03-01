package it.cavallium.data.generator;

import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public interface DataUpgrader<T, U> {

	@NotNull U upgrade(@NotNull T data) throws IOException;
}
