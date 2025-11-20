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
import java.util.List;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
				.filter(f ->  f instanceof ComputedTypeNullable)
				.map(f -> (ComputedTypeNullable) f)
				.filter(type -> (type instanceof ComputedTypeNullableVersioned versioned
						&& versioned.getVersion().equals(version)) || type instanceof ComputedTypeNullableFixed)
				.map(type -> generateTypeVersioned(version, type));
	}

	private GeneratedClass generateTypeVersioned(ComputedVersion version, ComputedTypeNullable computedType) {
		var type = (ClassName) computedType.getJTypeName(basePackageName);
		var classBuilder = TypeSpec.recordBuilder(type.simpleName());

		var base = computedType.getBase();
		var baseType = base.getJTypeName(basePackageName);

		classBuilder.addModifiers(Modifier.PUBLIC);
		classBuilder.recordConstructor(MethodSpec.constructorBuilder().addParameter(ParameterSpec.builder(baseType, "value").build()).build());

		var iNullableITypeClass = ClassName.get(version.getDataNullablesPackage(basePackageName), "INullableIType");
		var iNullableClass = ClassName.get(INullable.class);
		var typedNullable = ParameterizedTypeName.get(ClassName.get(TypedNullable.class), baseType);

		if (base instanceof ComputedTypeSuper computedTypeSuper) {
			var iNullableSuperTypeClass = ClassName.get(version.getDataNullablesPackage(basePackageName), "INullableSuperType");
			var superTypeClass = ClassName.get(dataModel.getRootPackage(basePackageName), "SuperType");
			classBuilder.addSuperinterface(iNullableSuperTypeClass);

			classBuilder.addMethod(MethodSpec
					.methodBuilder("getSuperType$")
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.returns(superTypeClass)
					.addStatement("return $T.$N", superTypeClass, base.getName())
					.build());
		} else if (base instanceof ComputedTypeBase) {
			var iNullableBaseTypeClass = ClassName.get(version.getDataNullablesPackage(basePackageName), "INullableBaseType");
			var baseTypeClass = ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType");
			classBuilder.addSuperinterface(iNullableBaseTypeClass);

			classBuilder.addMethod(MethodSpec
					.methodBuilder("getBaseType$")
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.returns(baseTypeClass)
					.addStatement("return $T.$N", baseTypeClass, base.getName())
					.build());
		} else if (!(base instanceof ComputedTypeCustom)) {
			throw new UnsupportedOperationException();
		}

		classBuilder.addField(FieldSpec
				.builder(type, "NULL").addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).initializer("new $T(null)", type).build());

		classBuilder.addSuperinterfaces(List.of(iNullableITypeClass, iNullableClass, typedNullable));

		if (version.isCurrent()) {

			classBuilder.addMethod(MethodSpec
					.methodBuilder("of")
					.addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
					.addParameter(ParameterSpec.builder(baseType, "value").build())
					.addException(ClassName.get(NullPointerException.class))
					.returns(type)
					.beginControlFlow("if (value != null)")
					.addStatement("return new $T(value)", type)
					.nextControlFlow("else")
					.addStatement("throw new $T()", NullPointerException.class)
					.endControlFlow()
					.build());

			classBuilder.addMethod(MethodSpec
					.methodBuilder("ofNullable")
					.addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
					.addParameter(ParameterSpec.builder(baseType, "value").build())
					.returns(type)
					.addStatement("return value != null ? new $T(value) : NULL", type)
					.build());

			classBuilder.addMethod(MethodSpec
					.methodBuilder("or")
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.addAnnotation(NotNull.class)
					.returns(type)
					.addParameter(ParameterSpec.builder(type, "fallback").addAnnotation(NotNull.class).build())
					.addStatement("return this.value == null ? fallback : this")
					.build());
		}

		classBuilder.addMethod(MethodSpec
				.methodBuilder("empty")
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
				.returns(type)
				.addStatement("return NULL")
				.build());

		classBuilder.addMethod(MethodSpec
				.methodBuilder("getNullable")
				.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
				.addAnnotation(Nullable.class)
				.returns(baseType)
				.addStatement("return this.value")
				.build());

		return new GeneratedClass(type.packageName(), classBuilder);
	}
}
