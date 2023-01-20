package it.cavallium.data.generator.plugin;

public sealed interface ComputedTypeNullable extends ComputedType permits ComputedTypeNullableNative,
		ComputedTypeNullableVersioned {

	ComputedType getBase();
}
