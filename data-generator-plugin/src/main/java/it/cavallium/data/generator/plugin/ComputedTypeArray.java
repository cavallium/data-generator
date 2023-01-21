package it.cavallium.data.generator.plugin;

public sealed interface ComputedTypeArray extends ComputedType permits ComputedTypeArrayFixed, ComputedTypeArrayNative,
		ComputedTypeArrayVersioned {

	ComputedType getBase();
}
