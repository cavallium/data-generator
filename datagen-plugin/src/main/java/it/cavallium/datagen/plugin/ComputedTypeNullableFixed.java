package it.cavallium.datagen.plugin;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import it.cavallium.datagen.TypedNullable;
import it.cavallium.datagen.nativedata.UpgradeUtil;
import java.util.Objects;
import java.util.stream.Stream;

public final class ComputedTypeNullableFixed implements ComputedTypeNullable {

	private final String baseType;
	private final ComputedVersion currentVersion;

	private ComputedType computedChild;
	private final ComputedTypeSupplier computedTypeSupplier;

	public ComputedTypeNullableFixed(String baseType, ComputedVersion currentVersion, ComputedTypeSupplier computedTypeSupplier) {
		this.baseType = baseType;
		this.currentVersion = currentVersion;
		this.computedTypeSupplier = computedTypeSupplier;
	}

	public ComputedType getBase() {
		return computedTypeSupplier.get(baseType);
	}

	public ComputedType child() {
		synchronized (this) {
			if (computedChild == null) {
				computedChild = computedTypeSupplier.get(baseType);
			}
		}
		if (computedChild instanceof ComputedTypeNullableFixed) {
			throw new IllegalStateException();
		} else if (computedChild instanceof ComputedTypeArrayVersioned) {
			throw new IllegalStateException();
		}
		return computedChild;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		ComputedTypeNullableFixed that = (ComputedTypeNullableFixed) o;

		return Objects.equals(baseType, that.baseType);
	}

	@Override
	public int hashCode() {
		return baseType != null ? baseType.hashCode() : 0;
	}

	@Override
	public String getName() {
		return "-" + baseType;
	}

	@Override
	public TypeName getJTypeName(String basePackageName) {
		return getJTypeNameOfVersion(currentVersion, basePackageName);
	}

	@Override
	public TypeName getJTypeNameGeneric(String basePackageName) {
		return getJTypeNameGenericOfVersion(currentVersion, basePackageName);
	}

	private TypeName getJTypeNameOfVersion(ComputedVersion version, String basePackageName) {
		return ClassName.get(version.getDataNullablesPackage(basePackageName),
				"Nullable" + baseType);
	}

	private TypeName getJTypeNameGenericOfVersion(ComputedVersion version, String basePackageName) {
		return ParameterizedTypeName.get(ClassName.get(TypedNullable.class),
				ClassName.get(version.getDataPackage(basePackageName), baseType));
	}

	@Override
	public ClassName getJSerializerName(String basePackageName) {
		return ClassName.get(currentVersion.getSerializersPackage(basePackageName),
				"Nullable" + baseType + "Serializer");
	}

	@Override
	public FieldLocation getJSerializerInstance(String basePackageName) {
		var className = ClassName.get(currentVersion.getPackage(basePackageName), "Version");
		var serializerFieldName = "Nullable" + baseType + "SerializerInstance";
		return new FieldLocation(className, serializerFieldName);
	}

	@Override
	public TypeName getJUpgraderName(String basePackageName) {
		throw new UnsupportedOperationException("Not upgradable");
	}

	@Override
	public FieldLocation getJUpgraderInstance(String basePackageName) {
		throw new UnsupportedOperationException("Not upgradable");
	}

	@Override
	public CodeBlock wrapWithUpgrade(String basePackageName, CodeBlock content, ComputedType next) {
		var builder = CodeBlock.builder();
		var upgraderInstance = getBase().getJUpgraderInstance(basePackageName);
		builder.add("new $T($T.upgradeNullable(", next.getJTypeName(basePackageName), UpgradeUtil.class);
		builder.add(content);
		builder.add(".getNullable(), $T.$N)", upgraderInstance.className(), upgraderInstance.fieldName());
		builder.add(")");
		return builder.build();
	}

	@Override
	public Stream<ComputedType> getDependencies() {
		return Stream.of(child());
	}

	@Override
	public Stream<ComputedType> getDependents() {
		return computedTypeSupplier.getDependents(getName());
	}

	@Override
	public String toString() {
		return "-" + baseType + " (custom)";
	}
}
