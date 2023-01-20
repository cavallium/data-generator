package it.cavallium.data.generator.plugin;

public sealed interface ComputedTypeArray extends ComputedType permits ComputedTypeArrayNative,
		ComputedTypeArrayVersioned {

	ComputedType getBase();
}
