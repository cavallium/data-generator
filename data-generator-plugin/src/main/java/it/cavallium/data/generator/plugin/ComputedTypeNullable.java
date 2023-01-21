package it.cavallium.data.generator.plugin;

public sealed interface ComputedTypeNullable extends ComputedType permits ComputedTypeNullableFixed,
		ComputedTypeNullableNative, ComputedTypeNullableVersioned {

	ComputedType getBase();
}
