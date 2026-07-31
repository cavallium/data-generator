package it.cavallium.datagen;

import it.cavallium.stream.SafeDataInput;

/** Skips exactly one serialized value from an input without materializing it. */
@FunctionalInterface
public interface DataSkipper {

	void skip(SafeDataInput input);
}
