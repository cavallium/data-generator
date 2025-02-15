package it.cavallium.datagen.plugin;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import it.cavallium.datagen.TypedNullable;
import it.cavallium.datagen.nativedata.UpgradeUtil;
import it.cavallium.datagen.plugin.ComputedType.VersionedComputedType;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.stream.Stream;

public final class ComputedTypeNullableVersioned implements ComputedTypeNullable, VersionedComputedType {

	private final VersionedType baseType;

	private ComputedType computedChild;
	private final ComputedTypeSupplier computedTypeSupplier;

	public ComputedTypeNullableVersioned(VersionedType baseType, ComputedTypeSupplier computedTypeSupplier) {
		this.baseType = baseType;
		this.computedTypeSupplier = computedTypeSupplier;
	}

	public VersionedComputedType getBase() {
		return (VersionedComputedType) computedTypeSupplier.get(baseType);
	}

	@Override
	public ComputedVersion getVersion() {
		return baseType.version();
	}

	@Override
	public ComputedTypeNullableVersioned withChangeAtVersion(ComputedVersion version, VersionChangeChecker versionChangeChecker,
			LinkedHashMap<String, VersionedType> data) {
		return new ComputedTypeNullableVersioned(baseType.withVersion(version),
				computedTypeSupplier
		);
	}

	public ComputedType child() {
		synchronized (this) {
			if (computedChild == null) {
				computedChild = computedTypeSupplier.get(baseType);
			}
		}
		if (computedChild instanceof ComputedTypeNullableVersioned) {
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

		ComputedTypeNullableVersioned that = (ComputedTypeNullableVersioned) o;

		return Objects.equals(baseType, that.baseType);
	}

	@Override
	public int hashCode() {
		return baseType != null ? baseType.hashCode() : 0;
	}

	@Override
	public String getName() {
		return "-" + baseType.type();
	}

	@Override
	public TypeName getJTypeName(String basePackageName) {
		return getJTypeNameOfVersion(baseType.version(), basePackageName);
	}

	@Override
	public TypeName getJTypeNameGeneric(String basePackageName) {
		return getJTypeNameGenericOfVersion(baseType.version(), basePackageName);
	}

	private TypeName getJTypeNameOfVersion(ComputedVersion version, String basePackageName) {
		return ClassName.get(version.getDataNullablesPackage(basePackageName),
				"Nullable" + baseType.type());
	}

	private TypeName getJTypeNameGenericOfVersion(ComputedVersion version, String basePackageName) {
		return ParameterizedTypeName.get(ClassName.get(TypedNullable.class),
				ClassName.get(version.getDataPackage(basePackageName), baseType.type()));
	}

	@Override
	public ClassName getJSerializerName(String basePackageName) {
		return ClassName.get(baseType.version().getSerializersPackage(basePackageName),
				"Nullable" + baseType.type() + "Serializer");
	}

	@Override
	public FieldLocation getJSerializerInstance(String basePackageName) {
		var className = ClassName.get(baseType.version().getPackage(basePackageName), "Version");
		var serializerFieldName = "Nullable" + baseType.type() + "SerializerInstance";
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
		return "-" + baseType.type() + " (v" + getVersion() + ")";
	}
}
