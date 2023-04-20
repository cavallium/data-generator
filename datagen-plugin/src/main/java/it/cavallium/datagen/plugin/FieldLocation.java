package it.cavallium.datagen.plugin;

import com.squareup.javapoet.TypeName;

public record FieldLocation(TypeName className, String fieldName) {}
