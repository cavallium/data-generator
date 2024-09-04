package it.cavallium.datagen.plugin;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import it.cavallium.datagen.nativedata.UpgradeUtil;
import it.cavallium.datagen.plugin.ComputedType.VersionedComputedType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class ComputedTypeArrayVersioned implements VersionedComputedType, ComputedTypeArray {

	private final VersionedType baseType;

	private VersionedComputedType computedChild;
	private final ComputedTypeSupplier computedTypeSupplier;

	public ComputedTypeArrayVersioned(VersionedType baseType, ComputedTypeSupplier computedTypeSupplier) {
		this.baseType = baseType;
		this.computedTypeSupplier = computedTypeSupplier;
	}

	public VersionedComputedType getBase() {
		return child();
	}

	@Override
	public ComputedVersion getVersion() {
		return baseType.version();
	}

	@Override
	public ComputedTypeArrayVersioned withChangeAtVersion(ComputedVersion version, VersionChangeChecker versionChangeChecker,
			LinkedHashMap<String, VersionedType> data) {
		return new ComputedTypeArrayVersioned(baseType.withVersion(version),
				computedTypeSupplier
		);
	}

	public VersionedComputedType child() {
		synchronized (this) {
			if (computedChild == null) {
				var computedChild = computedTypeSupplier.get(baseType);
				if (computedChild instanceof ComputedTypeNullableVersioned) {
					throw new IllegalStateException();
				} else if (computedChild instanceof ComputedTypeArrayVersioned) {
					throw new IllegalStateException();
				} else if (computedChild instanceof VersionedComputedType versionedComputedType) {
					this.computedChild = versionedComputedType;
				} else {
					throw new IllegalStateException();
				}
			}
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

		ComputedTypeArrayVersioned that = (ComputedTypeArrayVersioned) o;

		return Objects.equals(baseType, that.baseType);
	}

	@Override
	public int hashCode() {
		return baseType != null ? baseType.hashCode() : 0;
	}

	@Override
	public String getName() {
		return "§" + baseType.type();
	}

	@Override
	public TypeName getJTypeName(String basePackageName) {
		return ParameterizedTypeName.get(ClassName.get(List.class),
				computedTypeSupplier.get(baseType).getJTypeName(basePackageName));
	}

	@Override
	public TypeName getJTypeNameGeneric(String basePackageName) {
		return getJTypeName(basePackageName);
	}

	@Override
	public ClassName getJSerializerName(String basePackageName) {
		return ClassName.get(baseType.version().getSerializersPackage(basePackageName), "Array" + baseType.type() + "Serializer");
	}

	@Override
	public FieldLocation getJSerializerInstance(String basePackageName) {
		var className = ClassName.get(baseType.version().getPackage(basePackageName), "Version");
		var serializerFieldName = "Array" + baseType.type() + "SerializerInstance";
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
		builder.add("$T.upgradeArray(", UpgradeUtil.class);
		builder.add(content);
		var upgraderInstance = getBase().getJUpgraderInstance(basePackageName);
		builder.add(", $T.$N)", upgraderInstance.className(), upgraderInstance.fieldName());
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
		return baseType.type() + "[] (v" + getVersion() + ")";
	}
}
