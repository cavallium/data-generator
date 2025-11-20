package it.cavallium.datagen.plugin;

import com.palantir.javapoet.TypeName;

public record FieldLocation(TypeName className, String fieldName) {}
