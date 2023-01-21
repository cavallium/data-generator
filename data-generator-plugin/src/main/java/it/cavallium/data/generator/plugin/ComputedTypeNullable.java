package it.cavallium.data.generator.plugin;

import com.squareup.javapoet.ClassName;

public sealed interface ComputedTypeNullable extends ComputedType permits ComputedTypeNullableFixed,
		ComputedTypeNullableNative, ComputedTypeNullableVersioned {

	ComputedType getBase();

	@Override
	ClassName getJSerializerName(String basePackageName);
}
