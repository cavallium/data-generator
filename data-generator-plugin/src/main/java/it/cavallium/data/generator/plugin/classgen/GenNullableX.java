package it.cavallium.data.generator.plugin.classgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import it.cavallium.data.generator.TypedNullable;
import it.cavallium.data.generator.nativedata.INullable;
import it.cavallium.data.generator.plugin.ClassGenerator;
import it.cavallium.data.generator.plugin.ComputedTypeBase;
import it.cavallium.data.generator.plugin.ComputedTypeNullableVersioned;
import it.cavallium.data.generator.plugin.ComputedTypeSuper;
import it.cavallium.data.generator.plugin.ComputedVersion;
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
				.filter(type -> type instanceof ComputedTypeNullableVersioned)
				.map(type -> (ComputedTypeNullableVersioned) type)
				.filter(type -> type.getVersion().equals(version))
				.map(type -> generateTypeVersioned(version, type));
	}

	private GeneratedClass generateTypeVersioned(ComputedVersion version, ComputedTypeNullableVersioned computedType) {
		var type = (ClassName) computedType.getJTypeName(basePackageName);
		var classBuilder = TypeSpec.recordBuilder(type.simpleName());

		var base = computedType.getBase();
		var baseType = base.getJTypeName(basePackageName);

		classBuilder.addModifiers(Modifier.PUBLIC);
		classBuilder.addRecordComponent(ParameterSpec.builder(baseType, "value").build());

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
		} else if (base instanceof ComputedTypeBase computedTypeBase) {
			var iNullableBaseTypeClass = ClassName.get(version.getDataNullablesPackage(basePackageName), "INullableBaseType");
			var baseTypeClass = ClassName.get(dataModel.getRootPackage(basePackageName), "BaseType");
			classBuilder.addSuperinterface(iNullableBaseTypeClass);

			classBuilder.addMethod(MethodSpec
					.methodBuilder("getBaseType$")
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.returns(baseTypeClass)
					.addStatement("return $T.$N", baseTypeClass, base.getName())
					.build());
		} else {
			throw new UnsupportedOperationException();
		}

		if (version.isCurrent()) {
			classBuilder.addSuperinterfaces(List.of(iNullableITypeClass, iNullableClass, typedNullable));

			classBuilder.addField(FieldSpec.builder(type, "NULL").addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).initializer("new $T(($T)null)", type, baseType).build());

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
					.methodBuilder("empty")
					.addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
					.returns(type)
					.addStatement("return NULL")
					.build());

			classBuilder.addMethod(MethodSpec
					.methodBuilder("or")
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.addAnnotation(NotNull.class)
					.returns(type)
					.addParameter(ParameterSpec.builder(type, "fallback").addAnnotation(NotNull.class).build())
					.addStatement("return this.value == null ? fallback : this")
					.build());

			classBuilder.addMethod(MethodSpec
					.methodBuilder("getNullable")
					.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
					.addAnnotation(Nullable.class)
					.returns(baseType)
					.addStatement("return this.value")
					.build());
		}

		return new GeneratedClass(type.packageName(), classBuilder);
	}
}
