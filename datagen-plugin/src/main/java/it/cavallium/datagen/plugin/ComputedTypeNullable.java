package it.cavallium.datagen.plugin;

import com.palantir.javapoet.ClassName;

public sealed interface ComputedTypeNullable extends ComputedType permits ComputedTypeNullableFixed,
		ComputedTypeNullableNative, ComputedTypeNullableVersioned {

	ComputedType getBase();

	@Override
	ClassName getJSerializerName(String basePackageName);
}
