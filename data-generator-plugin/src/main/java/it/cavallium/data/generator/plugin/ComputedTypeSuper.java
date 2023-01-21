package it.cavallium.data.generator.plugin;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import it.cavallium.data.generator.plugin.ComputedType.VersionedComputedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class ComputedTypeSuper implements VersionedComputedType {

	private final VersionedType type;
	private final List<VersionedType> subTypes;

	private List<ComputedType> computedSubTypes;
	private final ComputedTypeSupplier computedTypeSupplier;

	public ComputedTypeSuper(VersionedType type, List<VersionedType> subType, ComputedTypeSupplier computedTypeSupplier) {
		this.type = type;
		this.subTypes = subType;
		this.computedTypeSupplier = computedTypeSupplier;
	}

	public String getType() {
		return type.type();
	}

	@Override
	public ComputedVersion getVersion() {
		return type.version();
	}

	@Override
	public it.cavallium.data.generator.plugin.ComputedTypeSuper withChangeAtVersion(ComputedVersion version,
			VersionChangeChecker versionChangeChecker) {
		return new it.cavallium.data.generator.plugin.ComputedTypeSuper(type.withVersion(version),
				subTypes.stream().map(subType -> subType.withVersionIfChanged(version, versionChangeChecker)).toList(),
				computedTypeSupplier
		);
	}

	public List<ComputedType> subTypes() {
		synchronized (this) {
			if (computedSubTypes == null) {
				computedSubTypes = new ArrayList<>();
				for (VersionedType subType : subTypes) {
					computedSubTypes.add(computedTypeSupplier.get(subType));
				}
			}
		}
		return computedSubTypes;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		it.cavallium.data.generator.plugin.ComputedTypeSuper that = (it.cavallium.data.generator.plugin.ComputedTypeSuper) o;

		if (!Objects.equals(type, that.type)) {
			return false;
		}
		return Objects.equals(subTypes, that.subTypes);
	}

	@Override
	public int hashCode() {
		int result = type != null ? type.hashCode() : 0;
		result = 31 * result + (subTypes != null ? subTypes.hashCode() : 0);
		return result;
	}

	@Override
	public Stream<ComputedType> getDependencies() {
		return subTypes().stream();
	}

	@Override
	public Stream<ComputedType> getDependents() {
		return computedTypeSupplier.getDependents(getName());
	}

	@Override
	public String getName() {
		return type.type();
	}

	@Override
	public ClassName getJTypeName(String basePackageName) {
		return ClassName.get(getVersion().getDataPackage(basePackageName), type.type());
	}

	@Override
	public ClassName getJSerializerName(String basePackageName) {
		return ClassName.get(type.version().getSerializersPackage(basePackageName), type.type() + "Serializer");
	}

	@Override
	public FieldLocation getJSerializerInstance(String basePackageName) {
		var className = ClassName.get(type.version().getPackage(basePackageName), "Version");
		var serializerFieldName = type.type() + "SerializerInstance";
		return new FieldLocation(className, serializerFieldName);
	}

	@Override
	public TypeName getJUpgraderName(String basePackageName) {
		return ClassName.get(type.version().getSerializersPackage(basePackageName), type.type() + "Upgrader");
	}

	@Override
	public FieldLocation getJUpgraderInstance(String basePackageName) {
		var className = ClassName.get(type.version().getPackage(basePackageName), "Version");
		var upgraderFieldName = type.type() + "UpgraderInstance";
		return new FieldLocation(className, upgraderFieldName);
	}

	@Override
	public String toString() {
		return type.type() + " (super, v" + getVersion() + ")";
	}
}
