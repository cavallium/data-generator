package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedType;
import it.cavallium.datagen.plugin.ComputedTypeArray;
import it.cavallium.datagen.plugin.ComputedTypeBase;
import it.cavallium.datagen.plugin.ComputedTypeNullable;
import it.cavallium.datagen.plugin.ComputedVersion;
import it.cavallium.datagen.plugin.GeneratedNameAllocator;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Generates the owned, flattened immutable representation for a schema record. */
public class GenDataBaseX extends ClassGenerator {

	public GenDataBaseX(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().parallelStream().flatMap(this::generateVersionClasses);
	}

	private Stream<GeneratedClass> generateVersionClasses(ComputedVersion version) {
		return dataModel.getBaseTypesComputed(version)
				.filter(type -> type.getVersion().equals(version))
				.map(type -> generateTypeVersioned(version, type));
	}

	private GeneratedClass generateTypeVersioned(ComputedVersion version, ComputedTypeBase base) {
		ClassName type = base.getJTypeName(basePackageName);
		TypeSpec.Builder classBuilder = TypeSpec.classBuilder(type.simpleName())
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

		addImplementedTypes(base, classBuilder);
		addStorageAndConstruction(base, type, classBuilder);
		addAccessorsAndWithers(base, type, classBuilder);
		addIdentityMethods(base, type, classBuilder);
		addBaseAndUnionMetadata(base, classBuilder);

		if (version.isCurrent()) {
			addBuilder(base, type, classBuilder);
		}

		return new GeneratedClass(type.packageName(), classBuilder);
	}

	private void addImplementedTypes(ComputedTypeBase base, TypeSpec.Builder classBuilder) {
		dataModel.getTypeSameVersions(base).forEach(version -> classBuilder.addSuperinterface(
				ClassName.get(version.getPackage(basePackageName), "IBaseType")));
		dataModel.getSuperTypesOf(base, true)
				.forEach(superType -> classBuilder.addSuperinterface(superType.getJTypeName(basePackageName)));
	}

	private void addStorageAndConstruction(ComputedTypeBase base,
			ClassName type,
			TypeSpec.Builder classBuilder) {
		for (var field : base.getData().entrySet()) {
			String name = field.getKey();
			ComputedType fieldType = field.getValue();
			if (fieldType instanceof ComputedTypeNullable nullable) {
				TypeName valueType = nullable.getBase().getJTypeName(basePackageName);
				if (valueType.isPrimitive()) {
					classBuilder.addField(FieldSpec.builder(TypeName.BOOLEAN, presentName(base, name),
							Modifier.PRIVATE, Modifier.FINAL).build());
				}
				classBuilder.addField(FieldSpec.builder(valueType, name, Modifier.PRIVATE, Modifier.FINAL).build());
			} else {
				classBuilder.addField(FieldSpec.builder(fieldType.getJTypeNameGeneric(basePackageName),
						name, Modifier.PRIVATE, Modifier.FINAL).build());
			}
			if (fieldType instanceof ComputedTypeArray array) {
				addSafeArrayCopyMethod(field.getKey(), array, classBuilder);
			}
		}

		MethodSpec.Builder constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE);
		for (var field : base.getData().entrySet()) {
			String name = field.getKey();
			ComputedType fieldType = field.getValue();
			String valueParameter = parameterName(base, name);
			addFieldParameters(constructor, base, fieldType, name);
			if (fieldType instanceof ComputedTypeNullable nullable) {
				TypeName valueType = nullable.getBase().getJTypeName(basePackageName);
				if (valueType.isPrimitive()) {
					String presenceParameter = parameterPresenceName(base, name);
					constructor.addStatement("this.$N = $N", presentName(base, name), presenceParameter)
							.addStatement("this.$N = $N ? $N : $L", name, presenceParameter, valueParameter,
									primitiveDefault(valueType));
				} else {
					constructor.addStatement("this.$N = $N", name, valueParameter);
				}
			} else if (fieldType instanceof ComputedTypeArray array) {
				ClassName codec = array.getJSerializerName(basePackageName);
				constructor.addStatement("$T.requireNonNull($N, $S)", Objects.class, valueParameter, name)
						.addStatement("this.$N = $N.length == 0 ? $T.emptyArray() : $N",
								name, valueParameter, codec, valueParameter);
			} else if (!fieldType.getJTypeNameGeneric(basePackageName).isPrimitive()) {
				constructor.addStatement("this.$N = $T.requireNonNull($N, $S)", name,
						Objects.class, valueParameter, name);
			} else {
				constructor.addStatement("this.$N = $N", name, valueParameter);
			}
		}
		classBuilder.addMethod(constructor.build());

		if (base.getData().isEmpty()) {
			classBuilder.addField(FieldSpec.builder(type, "INSTANCE", Modifier.PRIVATE, Modifier.STATIC,
					Modifier.FINAL).initializer("new $T()", type).build());
		}

		MethodSpec.Builder safeFactory = MethodSpec.methodBuilder("of")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.returns(type);
		MethodSpec.Builder ownedFactory = MethodSpec.methodBuilder("unsafeOfOwned")
				.addJavadoc("Transfers ownership of freshly-created array arguments without copying them.\n")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.returns(type);
		for (var field : base.getData().entrySet()) {
			addFieldParameters(safeFactory, base, field.getValue(), field.getKey());
			addFieldParameters(ownedFactory, base, field.getValue(), field.getKey());
		}
		if (base.getData().isEmpty()) {
			safeFactory.addStatement("return INSTANCE");
			ownedFactory.addStatement("return INSTANCE");
		} else {
			safeFactory.addStatement("return new $T($L)", type, factoryArguments(base, true));
			ownedFactory.addStatement("return new $T($L)", type, factoryArguments(base, false));
		}
		classBuilder.addMethod(safeFactory.build());
		classBuilder.addMethod(ownedFactory.build());
	}

	private void addSafeArrayCopyMethod(String fieldName,
			ComputedTypeArray array,
			TypeSpec.Builder classBuilder) {
		TypeName arrayType = array.getJTypeName(basePackageName);
		ClassName codec = array.getJSerializerName(basePackageName);
		MethodSpec.Builder method = MethodSpec.methodBuilder("copy" + StringUtils.capitalize(fieldName))
				.addModifiers(Modifier.PRIVATE, Modifier.STATIC)
				.returns(arrayType)
				.addParameter(parameter(array, "source"))
				.addStatement("$T.requireNonNull(source, $S)", Objects.class, fieldName)
				.beginControlFlow("if (source.length == 0)")
				.addStatement("return $T.emptyArray()", codec)
				.endControlFlow()
				.addStatement("$T copy = source.clone()", arrayType);
		if (!array.getBase().getJTypeName(basePackageName).isPrimitive()) {
			method.beginControlFlow("for (int i = 0; i < copy.length; i++)")
					.addStatement("$T.requireNonNull(copy[i], $S + i + $S)", Objects.class,
							fieldName + "[", "]")
					.endControlFlow();
		}
		method.addStatement("return copy");
		classBuilder.addMethod(method.build());
	}

	private void addAccessorsAndWithers(ComputedTypeBase base,
			ClassName type,
			TypeSpec.Builder classBuilder) {
		for (var field : base.getData().entrySet()) {
			String name = field.getKey();
			ComputedType fieldType = field.getValue();
			String valueParameter = parameterName(base, name);
			if (fieldType instanceof ComputedTypeNullable nullable) {
				addNullableAccessorsAndWithers(base, type, name, nullable, classBuilder);
				continue;
			} else if (fieldType instanceof ComputedTypeArray array) {
				addArrayAccessors(name, array, classBuilder);
			} else {
				MethodSpec.Builder accessor = MethodSpec.methodBuilder(name)
						.addModifiers(Modifier.PUBLIC)
						.returns(fieldType.getJTypeNameGeneric(basePackageName))
						.addStatement("return $N", name);
				if (!fieldType.getJTypeNameGeneric(basePackageName).isPrimitive()) accessor.addAnnotation(NotNull.class);
				classBuilder.addMethod(accessor.build());
			}

			MethodSpec.Builder setter = MethodSpec.methodBuilder("set" + StringUtils.capitalize(name))
					.addModifiers(Modifier.PUBLIC)
					.addAnnotation(NotNull.class)
					.returns(type)
					.addParameter(parameter(fieldType, valueParameter));
			if (fieldType instanceof ComputedTypeArray) {
				setter.addStatement("$T.requireNonNull($N, $S)", Objects.class, valueParameter, name)
						.beginControlFlow("if ($T.equals(this.$N, $N))", Arrays.class, name, valueParameter)
						.addStatement("return this")
						.endControlFlow()
						.addStatement("$T owned = copy$N($N)", fieldType.getJTypeName(basePackageName),
								StringUtils.capitalize(name), valueParameter)
						.addStatement("return new $T($L)", type, replacementArguments(base, name,
								CodeBlock.of("owned")));
			} else {
				if (!fieldType.getJTypeNameGeneric(basePackageName).isPrimitive()) {
					setter.addStatement("$T.requireNonNull($N, $S)", Objects.class, valueParameter, name);
				}
				setter.beginControlFlow("if ($L)", sameExpression(fieldType, CodeBlock.of("this.$N", name),
						CodeBlock.of("$N", valueParameter)))
						.addStatement("return this")
						.endControlFlow()
						.addStatement("return new $T($L)", type, replacementArguments(base, name,
								CodeBlock.of("$N", valueParameter)));
			}
			classBuilder.addMethod(setter.build());
		}
	}

	private void addNullableAccessorsAndWithers(ComputedTypeBase owner,
			ClassName ownerType,
			String name,
			ComputedTypeNullable nullable,
			TypeSpec.Builder classBuilder) {
		TypeName valueType = nullable.getBase().getJTypeName(basePackageName);
		boolean primitive = valueType.isPrimitive();
		String valueParameterName = parameterName(owner, name);
		CodeBlock present = primitive ? CodeBlock.of("$N", presentName(owner, name)) : CodeBlock.of("($N != null)", name);
		classBuilder.addMethod(MethodSpec.methodBuilder("has" + StringUtils.capitalize(name))
				.addModifiers(Modifier.PUBLIC)
				.returns(TypeName.BOOLEAN)
				.addStatement("return $L", present)
				.build());

		MethodSpec.Builder accessor = MethodSpec.methodBuilder(name)
				.addModifiers(Modifier.PUBLIC)
				.returns(valueType)
				.beginControlFlow("if (!$L)", present)
				.addStatement("throw new $T($S)", NoSuchElementException.class,
						"Nullable field " + name + " is empty")
				.endControlFlow()
				.addStatement("return $N", name);
		if (!primitive) accessor.addAnnotation(NotNull.class);
		classBuilder.addMethod(accessor.build());

		if (!primitive) {
			classBuilder.addMethod(MethodSpec.methodBuilder(name + "OrNull")
					.addModifiers(Modifier.PUBLIC)
					.addAnnotation(Nullable.class)
					.returns(valueType)
					.addStatement("return $N", name)
					.build());
		}

		ParameterSpec.Builder valueParameter = ParameterSpec.builder(valueType, valueParameterName);
		if (!primitive) valueParameter.addAnnotation(NotNull.class);
		MethodSpec.Builder setter = MethodSpec.methodBuilder("set" + StringUtils.capitalize(name))
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(NotNull.class)
				.returns(ownerType)
				.addParameter(valueParameter.build());
		if (primitive) {
			setter.beginControlFlow("if ($N && $L)", presentName(owner, name),
					samePrimitiveExpression(valueType, CodeBlock.of("this.$N", name),
							CodeBlock.of("$N", valueParameterName)))
					.addStatement("return this")
					.endControlFlow()
					.addStatement("return new $T($L)", ownerType,
							replacementArguments(owner, name, CodeBlock.of("true"),
									CodeBlock.of("$N", valueParameterName)));
		} else {
			setter.addStatement("$T.requireNonNull($N, $S)", Objects.class, valueParameterName, name)
					.beginControlFlow("if ($T.equals(this.$N, $N))", Objects.class, name, valueParameterName)
					.addStatement("return this")
					.endControlFlow()
					.addStatement("return new $T($L)", ownerType,
							replacementArguments(owner, name, CodeBlock.of("$N", valueParameterName)));
		}
		classBuilder.addMethod(setter.build());

		MethodSpec.Builder clear = MethodSpec.methodBuilder("clear" + StringUtils.capitalize(name))
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(NotNull.class)
				.returns(ownerType)
				.beginControlFlow("if (!$L)", present)
				.addStatement("return this")
				.endControlFlow();
		if (primitive) {
			clear.addStatement("return new $T($L)", ownerType,
					replacementArguments(owner, name, CodeBlock.of("false"), primitiveDefault(valueType)));
		} else {
			clear.addStatement("return new $T($L)", ownerType,
					replacementArguments(owner, name, CodeBlock.of("null")));
		}
		classBuilder.addMethod(clear.build());
	}

	private void addArrayAccessors(String name,
			ComputedTypeArray array,
			TypeSpec.Builder classBuilder) {
		TypeName component = array.getBase().getJTypeName(basePackageName);
		TypeName arrayType = array.getJTypeName(basePackageName);
		ClassName codec = array.getJSerializerName(basePackageName);
		classBuilder.addMethod(MethodSpec.methodBuilder(name + "Size")
				.addModifiers(Modifier.PUBLIC)
				.returns(TypeName.INT)
				.addStatement("return $N.length", name)
				.build());
		MethodSpec.Builder indexed = MethodSpec.methodBuilder(name)
				.addModifiers(Modifier.PUBLIC)
				.returns(component)
				.addParameter(TypeName.INT, "index")
				.addStatement("return $N[index]", name);
		if (!component.isPrimitive()) indexed.addAnnotation(NotNull.class);
		classBuilder.addMethod(indexed.build());
		classBuilder.addMethod(MethodSpec.methodBuilder(name + "Copy")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(NotNull.class)
				.returns(arrayType)
				.addStatement("return $N.length == 0 ? $T.emptyArray() : $N.clone()", name, codec, name)
				.build());
		classBuilder.addMethod(MethodSpec.methodBuilder(name + "UnsafeArray")
				.addJavadoc("Returns the owned backing array. The caller must not mutate it.\n")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(NotNull.class)
				.returns(arrayType)
				.addStatement("return $N", name)
				.build());
	}

	private void addIdentityMethods(ComputedTypeBase base,
			ClassName type,
			TypeSpec.Builder classBuilder) {
		MethodSpec.Builder equals = MethodSpec.methodBuilder("equals")
				.addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC)
				.returns(TypeName.BOOLEAN)
				.addParameter(Object.class, "object")
				.beginControlFlow("if (this == object)")
				.addStatement("return true")
				.endControlFlow()
				.beginControlFlow("if (!(object instanceof $T other))", type)
				.addStatement("return false")
				.endControlFlow();
		if (base.getData().isEmpty()) {
			equals.addStatement("return true");
		} else {
			CodeBlock.Builder comparison = CodeBlock.builder();
			int index = 0;
			for (var field : base.getData().entrySet()) {
				if (index++ != 0) comparison.add(" && ");
					comparison.add("$L", fieldEquality(base, field.getValue(), field.getKey(), "this", "other"));
			}
			equals.addStatement("return $L", comparison.build());
		}
		classBuilder.addMethod(equals.build());

		MethodSpec.Builder hash = MethodSpec.methodBuilder("hashCode")
				.addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC)
				.returns(TypeName.INT)
				.addStatement("int result = 1");
		for (var field : base.getData().entrySet()) {
			if (field.getValue() instanceof ComputedTypeNullable nullable
					&& nullable.getBase().getJTypeName(basePackageName).isPrimitive()) {
				hash.addStatement("result = 31 * result + $T.hashCode($N)", Boolean.class,
						presentName(base, field.getKey()));
			}
			hash.addStatement("result = 31 * result + $L", hashExpression(field.getValue(), field.getKey()));
		}
		hash.addStatement("return result");
		classBuilder.addMethod(hash.build());

		String representer = base.getStringRepresenter();
		MethodSpec.Builder toString = MethodSpec.methodBuilder("toString")
				.addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC)
				.returns(String.class);
		if (base.getVersion().isCurrent() && representer != null && !representer.isBlank()) {
			if (representer.contains(".")) {
				toString.addStatement("return $T.valueOf(" + representer + "(this))", String.class);
			} else {
				ComputedType fieldType = base.getData().get(representer);
				if (fieldType instanceof ComputedTypeArray) {
					toString.addStatement("return $T.toString($N)", Arrays.class, representer);
				} else if (fieldType instanceof ComputedTypeNullable nullable
						&& nullable.getBase().getJTypeName(basePackageName).isPrimitive()) {
					toString.addStatement("return $T.valueOf($N ? $N : null)", String.class,
							presentName(base, representer), representer);
				} else {
					toString.addStatement("return $T.valueOf($N)", String.class, representer);
				}
			}
		} else {
			CodeBlock.Builder value = CodeBlock.builder().add("return $S", type.simpleName() + "[");
			int index = 0;
			for (var field : base.getData().entrySet()) {
				value.add(" + $S + ", index++ == 0 ? "" : ", ");
				value.add("$S + ", field.getKey() + "=");
				if (field.getValue() instanceof ComputedTypeArray) {
					value.add("$T.toString($N)", Arrays.class, field.getKey());
				} else if (field.getValue() instanceof ComputedTypeNullable nullable
						&& nullable.getBase().getJTypeName(basePackageName).isPrimitive()) {
					value.add("($N ? $N : null)", presentName(base, field.getKey()), field.getKey());
				} else {
					value.add("$N", field.getKey());
				}
			}
			value.add(" + $S", "]");
			toString.addStatement("$L", value.build());
		}
		classBuilder.addMethod(toString.build());
	}

	private void addBaseAndUnionMetadata(ComputedTypeBase base, TypeSpec.Builder classBuilder) {
		ClassName baseTypeClass = ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType");
		classBuilder.addMethod(MethodSpec.methodBuilder("getBaseType$")
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.addAnnotation(Override.class)
				.returns(baseTypeClass)
				.addStatement("return $T.$N", baseTypeClass, base.getName())
				.build());
		dataModel.getSuperTypesOf(base, false).forEach(superType -> classBuilder.addMethod(MethodSpec
				.methodBuilder("getMetaId$" + superType.getName())
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(Override.class)
				.returns(TypeName.INT)
				.addStatement("return $L", superType.subTypes().indexOf(base))
				.build()));
	}

	private void addBuilder(ComputedTypeBase base,
			ClassName type,
			TypeSpec.Builder classBuilder) {
		ClassName builderType = base.getJBuilderName(basePackageName);
		TypeSpec.Builder builder = TypeSpec.classBuilder("Builder")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
		dataModel.getSuperTypesOf(base, true)
				.filter(superType -> superType.getVersion().isCurrent())
				.forEach(superType -> builder.addSuperinterface(superType.getJBuilderName(basePackageName)));

		for (var field : base.getData().entrySet()) {
			String storageName = builderName(base, field.getKey());
			if (field.getValue() instanceof ComputedTypeNullable nullable) {
				TypeName valueType = nullable.getBase().getJTypeName(basePackageName);
				if (valueType.isPrimitive()) {
					builder.addField(FieldSpec.builder(TypeName.BOOLEAN, builderPresenceName(base, field.getKey()),
							Modifier.PRIVATE).build());
				}
				builder.addField(FieldSpec.builder(valueType, storageName, Modifier.PRIVATE).build());
			} else {
				builder.addField(FieldSpec.builder(field.getValue().getJTypeNameGeneric(basePackageName), storageName,
						Modifier.PRIVATE).build());
			}
		}
		builder.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).build());
		MethodSpec.Builder sourceConstructor = MethodSpec.constructorBuilder()
				.addModifiers(Modifier.PRIVATE)
				.addParameter(type, "source");
		for (var field : base.getData().entrySet()) {
			String storageName = builderName(base, field.getKey());
			if (field.getValue() instanceof ComputedTypeNullable nullable) {
				TypeName valueType = nullable.getBase().getJTypeName(basePackageName);
				if (valueType.isPrimitive()) {
					sourceConstructor.addStatement("this.$N = source.$N", builderPresenceName(base, field.getKey()),
							presentName(base, field.getKey()))
							.addStatement("this.$N = source.$N", storageName, field.getKey());
				} else {
					sourceConstructor.addStatement("this.$N = source.$NOrNull()", storageName, field.getKey());
				}
			} else if (field.getValue() instanceof ComputedTypeArray) {
				sourceConstructor.addStatement("this.$N = source.$NCopy()", storageName, field.getKey());
			} else {
				sourceConstructor.addStatement("this.$N = source.$N()", storageName, field.getKey());
			}
		}
		builder.addMethod(sourceConstructor.build());

		for (var field : base.getData().entrySet()) {
			String name = field.getKey();
			ComputedType fieldType = field.getValue();
			String storageName = builderName(base, name);
			String valueParameterName = parameterName(base, name);
			if (fieldType instanceof ComputedTypeNullable nullable) {
				TypeName valueType = nullable.getBase().getJTypeName(basePackageName);
				ParameterSpec.Builder valueParameter = ParameterSpec.builder(valueType, valueParameterName);
				if (!valueType.isPrimitive()) valueParameter.addAnnotation(NotNull.class);
				MethodSpec.Builder setter = MethodSpec.methodBuilder("set" + StringUtils.capitalize(name))
						.addModifiers(Modifier.PUBLIC)
						.addAnnotation(NotNull.class)
						.returns(builderType)
						.addParameter(valueParameter.build());
				if (valueType.isPrimitive()) {
					setter.addStatement("this.$N = true", builderPresenceName(base, name))
							.addStatement("this.$N = $N", storageName, valueParameterName);
				} else {
					setter.addStatement("this.$N = $T.requireNonNull($N, $S)", storageName,
							Objects.class, valueParameterName, name);
				}
				setter.addStatement("return this");
				builder.addMethod(setter.build());
				MethodSpec.Builder clear = MethodSpec.methodBuilder("clear" + StringUtils.capitalize(name))
						.addModifiers(Modifier.PUBLIC)
						.addAnnotation(NotNull.class)
						.returns(builderType);
				if (valueType.isPrimitive()) {
					clear.addStatement("this.$N = false", builderPresenceName(base, name))
							.addStatement("this.$N = $L", storageName, primitiveDefault(valueType));
				} else {
					clear.addStatement("this.$N = null", storageName);
				}
				clear.addStatement("return this");
				builder.addMethod(clear.build());
				continue;
			}
			MethodSpec.Builder setter = MethodSpec.methodBuilder("set" + StringUtils.capitalize(name))
					.addModifiers(Modifier.PUBLIC)
					.addAnnotation(NotNull.class)
					.returns(builderType)
					.addParameter(parameter(fieldType, valueParameterName));
			if (fieldType instanceof ComputedTypeArray) {
				setter.addStatement("this.$N = copy$N($N)", storageName, StringUtils.capitalize(name),
						valueParameterName);
			} else if (!fieldType.getJTypeNameGeneric(basePackageName).isPrimitive()) {
				setter.addStatement("this.$N = $T.requireNonNull($N, $S)", storageName, Objects.class,
						valueParameterName, name);
			} else {
				setter.addStatement("this.$N = $N", storageName, valueParameterName);
			}
			setter.addStatement("return this");
			builder.addMethod(setter.build());
		}

		MethodSpec.Builder build = MethodSpec.methodBuilder("build")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(NotNull.class)
				.returns(type);
		for (var field : base.getData().entrySet()) {
			if (field.getValue() instanceof ComputedTypeNullable) {
				continue;
			}
			if (!field.getValue().getJTypeNameGeneric(basePackageName).isPrimitive()) {
				build.addStatement("$T.requireNonNull($N, $S)", Objects.class,
						builderName(base, field.getKey()), field.getKey());
			}
		}
		build.addStatement("return unsafeOfOwned($L)", directArguments(base));
		builder.addMethod(build.build());

		MethodSpec.Builder buildIfChanged = MethodSpec.methodBuilder("buildIfChanged")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(NotNull.class)
				.returns(type)
				.addParameter(type, "original");
		if (base.getData().isEmpty()) {
			buildIfChanged.addStatement("return original");
		} else {
			CodeBlock.Builder same = CodeBlock.builder();
			int index = 0;
			for (var field : base.getData().entrySet()) {
				if (index++ != 0) same.add(" && ");
					same.add("$L", builderFieldEquality(base, field.getValue(), field.getKey()));
			}
			buildIfChanged.addStatement("return $L ? original : build()", same.build());
		}
		builder.addMethod(buildIfChanged.build());

		MethodSpec.Builder builderToString = MethodSpec.methodBuilder("toString")
				.addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC)
				.returns(String.class);
		CodeBlock.Builder text = CodeBlock.builder().add("return $S", type.simpleName() + ".Builder[");
		int index = 0;
		for (var field : base.getData().entrySet()) {
			text.add(" + $S + $S + ", index++ == 0 ? "" : ", ", field.getKey() + "=");
			if (field.getValue() instanceof ComputedTypeArray) {
				text.add("$T.toString($N)", Arrays.class, builderName(base, field.getKey()));
			} else if (field.getValue() instanceof ComputedTypeNullable nullable
					&& nullable.getBase().getJTypeName(basePackageName).isPrimitive()) {
				text.add("($N ? $N : null)", builderPresenceName(base, field.getKey()),
						builderName(base, field.getKey()));
			} else {
				text.add("$N", builderName(base, field.getKey()));
			}
		}
		text.add(" + $S", "]");
		builderToString.addStatement("$L", text.build());
		builder.addMethod(builderToString.build());

		classBuilder.addMethod(MethodSpec.methodBuilder("builder")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(NotNull.class)
				.returns(builderType)
				.addStatement("return new $T(this)", builderType)
				.build());
		classBuilder.addType(builder.build());
	}

	private ParameterSpec parameter(ComputedType type, String name) {
		ParameterSpec.Builder parameter = ParameterSpec.builder(type.getJTypeNameGeneric(basePackageName), name);
		if (!type.getJTypeNameGeneric(basePackageName).isPrimitive()) parameter.addAnnotation(NotNull.class);
		return parameter.build();
	}

	private void addFieldParameters(MethodSpec.Builder method, ComputedTypeBase owner,
			ComputedType type, String name) {
		String valueParameter = parameterName(owner, name);
		if (type instanceof ComputedTypeNullable nullable) {
			TypeName valueType = nullable.getBase().getJTypeName(basePackageName);
			if (valueType.isPrimitive()) {
				method.addParameter(TypeName.BOOLEAN, parameterPresenceName(owner, name));
				method.addParameter(valueType, valueParameter);
			} else {
				method.addParameter(ParameterSpec.builder(valueType, valueParameter)
						.addAnnotation(Nullable.class).build());
			}
		} else {
			method.addParameter(parameter(type, valueParameter));
		}
	}

	private CodeBlock factoryArguments(ComputedTypeBase base, boolean copyArrays) {
		CodeBlock.Builder result = CodeBlock.builder();
		int index = 0;
		for (var field : base.getData().entrySet()) {
			String valueParameter = parameterName(base, field.getKey());
			if (field.getValue() instanceof ComputedTypeNullable nullable
					&& nullable.getBase().getJTypeName(basePackageName).isPrimitive()) {
				if (index++ != 0) result.add(", ");
				result.add("$N", parameterPresenceName(base, field.getKey()));
				result.add(", $N", valueParameter);
				continue;
			}
			if (index++ != 0) result.add(", ");
			if (copyArrays && field.getValue() instanceof ComputedTypeArray) {
				result.add("copy$N($N)", StringUtils.capitalize(field.getKey()), valueParameter);
			} else {
				result.add("$N", valueParameter);
			}
		}
		return result.build();
	}

	private CodeBlock directArguments(ComputedTypeBase base) {
		CodeBlock.Builder result = CodeBlock.builder();
		int index = 0;
		for (var field : base.getData().entrySet()) {
			if (field.getValue() instanceof ComputedTypeNullable nullable
					&& nullable.getBase().getJTypeName(basePackageName).isPrimitive()) {
				if (index++ != 0) result.add(", ");
				result.add("$N, $N", builderPresenceName(base, field.getKey()),
						builderName(base, field.getKey()));
			} else {
				if (index++ != 0) result.add(", ");
				result.add("$N", builderName(base, field.getKey()));
			}
		}
		return result.build();
	}

	private CodeBlock replacementArguments(ComputedTypeBase base, String replacement, CodeBlock... values) {
		CodeBlock.Builder result = CodeBlock.builder();
		int index = 0;
		for (var field : base.getData().entrySet()) {
			String name = field.getKey();
			if (field.getValue() instanceof ComputedTypeNullable nullable
					&& nullable.getBase().getJTypeName(basePackageName).isPrimitive()) {
				if (index++ != 0) result.add(", ");
				if (name.equals(replacement)) {
					if (values.length != 2) throw new IllegalArgumentException("Primitive nullable replacement needs two values");
					result.add("$L, $L", values[0], values[1]);
				} else {
					result.add("this.$N, this.$N", presentName(base, name), name);
				}
			} else {
				if (index++ != 0) result.add(", ");
				result.add("$L", name.equals(replacement) ? values[0] : CodeBlock.of("this.$N", name));
			}
		}
		return result.build();
	}

	private CodeBlock sameExpression(ComputedType type, CodeBlock left, CodeBlock right) {
		if (type instanceof ComputedTypeArray) return CodeBlock.of("$T.equals($L, $L)", Arrays.class, left, right);
		TypeName javaType = type instanceof ComputedTypeNullable nullable
				? nullable.getBase().getJTypeName(basePackageName) : type.getJTypeNameGeneric(basePackageName);
		if (!javaType.isPrimitive()) return CodeBlock.of("$T.equals($L, $L)", Objects.class, left, right);
		return samePrimitiveExpression(javaType, left, right);
	}

	private CodeBlock samePrimitiveExpression(TypeName javaType, CodeBlock left, CodeBlock right) {
		return switch (javaType.toString()) {
			case "float" -> CodeBlock.of("$T.compare($L, $L) == 0", Float.class, left, right);
			case "double" -> CodeBlock.of("$T.compare($L, $L) == 0", Double.class, left, right);
			default -> CodeBlock.of("$L == $L", left, right);
		};
	}

	private CodeBlock fieldEquality(ComputedTypeBase owner, ComputedType type, String field,
			String leftOwner, String rightOwner) {
		if (type instanceof ComputedTypeNullable nullable) {
			TypeName valueType = nullable.getBase().getJTypeName(basePackageName);
			if (valueType.isPrimitive()) {
				return CodeBlock.of("$N.$N == $N.$N && $L", leftOwner, presentName(owner, field),
						rightOwner, presentName(owner, field), samePrimitiveExpression(valueType,
								CodeBlock.of("$N.$N", leftOwner, field), CodeBlock.of("$N.$N", rightOwner, field)));
			}
			return CodeBlock.of("$T.equals($N.$N, $N.$N)", Objects.class, leftOwner, field, rightOwner, field);
		}
		return sameExpression(type, CodeBlock.of("$N.$N", leftOwner, field),
				CodeBlock.of("$N.$N", rightOwner, field));
	}

	private CodeBlock builderFieldEquality(ComputedTypeBase owner, ComputedType type, String field) {
		String storageName = builderName(owner, field);
		if (type instanceof ComputedTypeNullable nullable) {
			TypeName valueType = nullable.getBase().getJTypeName(basePackageName);
			if (valueType.isPrimitive()) {
				return CodeBlock.of("this.$N == original.$N && $L", builderPresenceName(owner, field),
						presentName(owner, field),
						samePrimitiveExpression(valueType, CodeBlock.of("this.$N", storageName),
								CodeBlock.of("original.$N", field)));
			}
			return CodeBlock.of("$T.equals(this.$N, original.$N)", Objects.class, storageName, field);
		}
		CodeBlock original = type instanceof ComputedTypeArray
				? CodeBlock.of("original.$NUnsafeArray()", field) : CodeBlock.of("original.$N()", field);
		return sameExpression(type, CodeBlock.of("this.$N", storageName), original);
	}

	private CodeBlock hashExpression(ComputedType type, String field) {
		if (type instanceof ComputedTypeArray) return CodeBlock.of("$T.hashCode($N)", Arrays.class, field);
		TypeName javaType = type instanceof ComputedTypeNullable nullable
				? nullable.getBase().getJTypeName(basePackageName) : type.getJTypeNameGeneric(basePackageName);
		if (!javaType.isPrimitive()) return CodeBlock.of("$T.hashCode($N)", Objects.class, field);
		return switch (javaType.toString()) {
			case "boolean" -> CodeBlock.of("$T.hashCode($N)", Boolean.class, field);
			case "byte" -> CodeBlock.of("$T.hashCode($N)", Byte.class, field);
			case "short" -> CodeBlock.of("$T.hashCode($N)", Short.class, field);
			case "char" -> CodeBlock.of("$T.hashCode($N)", Character.class, field);
			case "int" -> CodeBlock.of("$T.hashCode($N)", Integer.class, field);
			case "long" -> CodeBlock.of("$T.hashCode($N)", Long.class, field);
			case "float" -> CodeBlock.of("$T.hashCode($N)", Float.class, field);
			case "double" -> CodeBlock.of("$T.hashCode($N)", Double.class, field);
			default -> throw new IllegalStateException(javaType.toString());
		};
	}

	private static String presentName(ComputedTypeBase owner, String field) {
		return requireGeneratedName(dataNames(owner).presenceFields(), owner, field, "primitive nullable");
	}

	private static String parameterName(ComputedTypeBase owner, String field) {
		return requireGeneratedName(dataNames(owner).parameterValues(), owner, field, "parameter");
	}

	private static String parameterPresenceName(ComputedTypeBase owner, String field) {
		return requireGeneratedName(dataNames(owner).parameterPresences(), owner, field,
				"primitive-nullable parameter");
	}

	private static String builderName(ComputedTypeBase owner, String field) {
		return requireGeneratedName(dataNames(owner).builderValues(), owner, field, "builder storage");
	}

	private static String builderPresenceName(ComputedTypeBase owner, String field) {
		return requireGeneratedName(dataNames(owner).builderPresences(), owner, field,
				"primitive-nullable builder storage");
	}

	private static String requireGeneratedName(Map<String, String> names,
			ComputedTypeBase owner,
			String field,
			String role) {
		String name = names.get(field);
		if (name == null) {
			throw new IllegalArgumentException("Field " + field + " has no " + role + " in " + owner.getName());
		}
		return name;
	}

	private static DataNames dataNames(ComputedTypeBase owner) {
		var allocator = new GeneratedNameAllocator(owner.getData().keySet(),
				java.util.List.of("source", "owned", "original", "result", "INSTANCE", "copy", "index"));
		var presenceFields = new LinkedHashMap<String, String>();
		var parameterValues = new LinkedHashMap<String, String>();
		var parameterPresences = new LinkedHashMap<String, String>();
		var builderValues = new LinkedHashMap<String, String>();
		var builderPresences = new LinkedHashMap<String, String>();
		for (var entry : owner.getData().entrySet()) {
			String field = entry.getKey();
			parameterValues.put(field, allocator.allocate("parameter$" + field));
			builderValues.put(field, allocator.allocate("builder$" + field));
			if (entry.getValue() instanceof ComputedTypeNullable nullable
					&& nullable.getBase().getJTypeName("").isPrimitive()) {
				presenceFields.put(field, allocator.allocate("present$" + field));
				parameterPresences.put(field, allocator.allocate("parameterPresent$" + field));
				builderPresences.put(field, allocator.allocate("builderPresent$" + field));
			}
		}
		return new DataNames(Map.copyOf(presenceFields), Map.copyOf(parameterValues),
				Map.copyOf(parameterPresences), Map.copyOf(builderValues), Map.copyOf(builderPresences));
	}

	private record DataNames(Map<String, String> presenceFields,
			Map<String, String> parameterValues,
			Map<String, String> parameterPresences,
			Map<String, String> builderValues,
			Map<String, String> builderPresences) { }

	private CodeBlock primitiveDefault(TypeName type) {
		return switch (type.toString()) {
			case "boolean" -> CodeBlock.of("false");
			case "byte" -> CodeBlock.of("(byte) 0");
			case "short" -> CodeBlock.of("(short) 0");
			case "char" -> CodeBlock.of("(char) 0");
			case "int" -> CodeBlock.of("0");
			case "long" -> CodeBlock.of("0L");
			case "float" -> CodeBlock.of("0.0f");
			case "double" -> CodeBlock.of("0.0d");
			default -> throw new IllegalArgumentException("Not a primitive: " + type);
		};
	}
}
