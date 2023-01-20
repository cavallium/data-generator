package it.cavallium.data.generator.plugin;

import com.squareup.javapoet.TypeName;

public record FieldLocation(TypeName className, String fieldName) {}
