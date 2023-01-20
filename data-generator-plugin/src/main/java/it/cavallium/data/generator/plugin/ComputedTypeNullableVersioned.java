package it.cavallium.data.generator.plugin;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import it.cavallium.data.generator.plugin.ComputedType.VersionedComputedType;
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
	public ComputedTypeNullableVersioned withChangeAtVersion(ComputedVersion version,
			VersionChangeChecker versionChangeChecker) {
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
		return ClassName.get(baseType.version().getDataNullablesPackage(basePackageName),
				"Nullable" + baseType.type());
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
		return ClassName.get(baseType.version().getSerializersPackage(basePackageName),
				"Nullable" + baseType.type() + "Upgrader");
	}

	@Override
	public FieldLocation getJUpgraderInstance(String basePackageName) {
		var className = ClassName.get(baseType.version().getPackage(basePackageName), "Version");
		var upgraderFieldName = "Nullable" + baseType.type() + "UpgraderInstance";
		return new FieldLocation(className, upgraderFieldName);
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
