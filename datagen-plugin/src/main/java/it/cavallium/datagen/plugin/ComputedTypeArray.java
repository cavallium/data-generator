package it.cavallium.datagen.plugin;

import com.squareup.javapoet.ClassName;

public sealed interface ComputedTypeArray extends ComputedType permits ComputedTypeArrayFixed, ComputedTypeArrayNative,
		ComputedTypeArrayVersioned {

	ComputedType getBase();

	ClassName getJSerializerName(String basePackageName);
}
