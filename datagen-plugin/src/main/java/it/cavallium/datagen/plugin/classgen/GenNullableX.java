package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import it.cavallium.datagen.TypedNullable;
import it.cavallium.datagen.nativedata.INullable;
import it.cavallium.datagen.plugin.ClassGenerator;
import it.cavallium.datagen.plugin.ComputedTypeBase;
import it.cavallium.datagen.plugin.ComputedTypeCustom;
import it.cavallium.datagen.plugin.ComputedTypeNullable;
import it.cavallium.datagen.plugin.ComputedTypeNullableFixed;
import it.cavallium.datagen.plugin.ComputedTypeNullableVersioned;
import it.cavallium.datagen.plugin.ComputedTypeSuper;
import it.cavallium.datagen.plugin.ComputedVersion;
import java.util.Objects;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Generates nullable carrier values used only at explicit codec and object-transform boundaries. */
public class GenNullableX extends ClassGenerator {

	public GenNullableX(ClassGeneratorParams params) {
		super(params);
	}

	@Override
	protected Stream<GeneratedClass> generateClasses() {
		return dataModel.getVersionsSet().parallelStream().flatMap(this::generateVersionClasses);
	}

	private Stream<GeneratedClass> generateVersionClasses(ComputedVersion version) {
		return dataModel
				.getComputedTypes(version)
				.values()
				.stream()
				.filter(ComputedTypeNullable.class::isInstance)
				.map(ComputedTypeNullable.class::cast)
				.filter(type -> (type instanceof ComputedTypeNullableVersioned versioned
						&& versioned.getVersion().equals(version)) || type instanceof ComputedTypeNullableFixed)
				.map(type -> generateTypeVersioned(version, type));
	}

	private GeneratedClass generateTypeVersioned(ComputedVersion version, ComputedTypeNullable computedType) {
		ClassName type = (ClassName) computedType.getJTypeName(basePackageName);
		var base = computedType.getBase();
		var baseType = base.getJTypeName(basePackageName);
		if (baseType.isPrimitive()) {
			throw new IllegalArgumentException("Generated nullable carriers require a reference type: " + computedType);
		}

		TypeSpec.Builder classBuilder = TypeSpec.classBuilder(type.simpleName())
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL);
		var iNullableITypeClass = ClassName.get(version.getDataNullablesPackage(basePackageName), "INullableIType");
		var typedNullable = ParameterizedTypeName.get(ClassName.get(TypedNullable.class), baseType);
		classBuilder.addSuperinterface(iNullableITypeClass)
				.addSuperinterface(ClassName.get(INullable.class))
				.addSuperinterface(typedNullable);

		if (base instanceof ComputedTypeSuper) {
			classBuilder.addSuperinterface(ClassName.get(version.getDataNullablesPackage(basePackageName),
					"INullableSuperType"));
			var superTypeClass = ClassName.get(dataModel.getRootPackage(basePackageName), "SuperType");
			classBuilder.addMethod(MethodSpec.methodBuilder("getSuperType$")
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.returns(superTypeClass)
					.addStatement("return $T.$N", superTypeClass, base.getName())
					.build());
		} else if (base instanceof ComputedTypeBase) {
			classBuilder.addSuperinterface(ClassName.get(version.getDataNullablesPackage(basePackageName),
					"INullableBaseType"));
			var baseTypeClass = ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType");
			classBuilder.addMethod(MethodSpec.methodBuilder("getBaseType$")
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.returns(baseTypeClass)
					.addStatement("return $T.$N", baseTypeClass, base.getName())
					.build());
		} else if (!(base instanceof ComputedTypeCustom)) {
			throw new UnsupportedOperationException("Unsupported nullable carrier base: " + base);
		}

		classBuilder.addField(FieldSpec.builder(type, "NULL", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
				.initializer("new $T(null)", type)
				.build());
		classBuilder.addField(FieldSpec.builder(baseType, "value", Modifier.PRIVATE, Modifier.FINAL)
				.addAnnotation(Nullable.class)
				.build());
		classBuilder.addMethod(MethodSpec.constructorBuilder()
				.addModifiers(Modifier.PRIVATE)
				.addParameter(ParameterSpec.builder(baseType, "value").addAnnotation(Nullable.class).build())
				.addStatement("this.value = value")
				.build());

		classBuilder.addMethod(MethodSpec.methodBuilder("of")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.addAnnotation(NotNull.class)
				.addParameter(ParameterSpec.builder(baseType, "value").addAnnotation(NotNull.class).build())
				.returns(type)
				.addStatement("return new $T($T.requireNonNull(value, $S))", type, Objects.class, "value")
				.build());
		classBuilder.addMethod(MethodSpec.methodBuilder("ofNullable")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.addAnnotation(NotNull.class)
				.addParameter(ParameterSpec.builder(baseType, "value").addAnnotation(Nullable.class).build())
				.returns(type)
				.addStatement("return value == null ? NULL : new $T(value)", type)
				.build());
		classBuilder.addMethod(MethodSpec.methodBuilder("empty")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC)
				.addAnnotation(NotNull.class)
				.returns(type)
				.addStatement("return NULL")
				.build());
		classBuilder.addMethod(MethodSpec.methodBuilder("getNullable")
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.addAnnotation(Override.class)
				.addAnnotation(Nullable.class)
				.returns(baseType)
				.addStatement("return value")
				.build());

		if (version.isCurrent()) {
			classBuilder.addMethod(MethodSpec.methodBuilder("or")
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.addAnnotation(NotNull.class)
					.returns(type)
					.addParameter(ParameterSpec.builder(type, "fallback").addAnnotation(NotNull.class).build())
					.addStatement("return value == null ? $T.requireNonNull(fallback, $S) : this",
							Objects.class, "fallback")
					.build());
		}

		classBuilder.addMethod(MethodSpec.methodBuilder("equals")
				.addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC)
				.returns(boolean.class)
				.addParameter(Object.class, "object")
				.addStatement("return this == object || object instanceof $T other && $T.equals(value, other.value)",
						type, Objects.class)
				.build());
		classBuilder.addMethod(MethodSpec.methodBuilder("hashCode")
				.addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC)
				.returns(int.class)
				.addStatement("return $T.hashCode(value)", Objects.class)
				.build());
		classBuilder.addMethod(MethodSpec.methodBuilder("toString")
				.addAnnotation(Override.class)
				.addModifiers(Modifier.PUBLIC)
				.returns(String.class)
				.addStatement("return $S + value + $S", type.simpleName() + "[value=", "]")
				.build());

		return new GeneratedClass(type.packageName(), classBuilder);
	}
}
