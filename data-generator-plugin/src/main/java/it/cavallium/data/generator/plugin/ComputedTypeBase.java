package it.cavallium.data.generator.plugin;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import it.cavallium.data.generator.plugin.ComputedType.VersionedComputedType;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.stream.Stream;

public final class ComputedTypeBase implements VersionedComputedType {

	private final VersionedType type;
	private final String stringRepresenter;

	private final LinkedHashMap<String, VersionedType> data;
	private LinkedHashMap<String, ComputedType> computedData;
	private final ComputedTypeSupplier computedTypeSupplier;

	public ComputedTypeBase(VersionedType type,
			String stringRepresenter,
			LinkedHashMap<String, VersionedType> data,
			ComputedTypeSupplier computedTypeSupplier) {
		this.type = type;
		if (type.type().startsWith("~") || type.type().startsWith("-")) {
			throw new IllegalStateException();
		}
		this.computedTypeSupplier = computedTypeSupplier;
		this.stringRepresenter = stringRepresenter;
		this.data = data;
	}

	public String getType() {
		return type.type();
	}

	@Override
	public ComputedVersion getVersion() {
		return type.version();
	}

	@Override
	public ComputedTypeBase withChangeAtVersion(ComputedVersion version, VersionChangeChecker versionChangeChecker,
			LinkedHashMap<String, VersionedType> data) {
		var newData = new LinkedHashMap<String, VersionedType>();
		data.forEach((k, v) -> newData.put(k, v.withVersionIfChanged(version, versionChangeChecker)));
		return new ComputedTypeBase(type.withVersion(version),
				stringRepresenter,
				newData, computedTypeSupplier
		);
	}

	public String getStringRepresenter() {
		return stringRepresenter;
	}

	public LinkedHashMap<String, ComputedType> getData() {
		synchronized (this) {
			if (computedData == null) {
				computedData = new LinkedHashMap<>();
				data.forEach((k, v) -> computedData.put(k, computedTypeSupplier.get(v)));
			}
		}
		return computedData;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		ComputedTypeBase that = (ComputedTypeBase) o;

		if (!Objects.equals(type, that.type)) {
			return false;
		}
		if (!Objects.equals(stringRepresenter, that.stringRepresenter)) {
			return false;
		}
		return Objects.equals(data, that.data);
	}

	@Override
	public int hashCode() {
		int result = type != null ? type.hashCode() : 0;
		result = 31 * result + (stringRepresenter != null ? stringRepresenter.hashCode() : 0);
		result = 31 * result + (data != null ? data.hashCode() : 0);
		return result;
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
	public ClassName getJUpgraderName(String basePackageName) {
		return ClassName.get(type.version().getUpgradersPackage(basePackageName), type.type() + "Upgrader");
	}

	@Override
	public FieldLocation getJUpgraderInstance(String basePackageName) {
		var className = ClassName.get(type.version().getPackage(basePackageName), "Version");
		var upgraderFieldName = type.type() + "UpgraderInstance";
		return new FieldLocation(className, upgraderFieldName);
	}

	@Override
	public CodeBlock wrapWithUpgrade(String basePackageName, CodeBlock content, ComputedType next) {
		var upgraderInstance = getJUpgraderInstance(basePackageName);
		var cb = CodeBlock.builder();
		cb.add(CodeBlock.of("$T.$N.upgrade(", upgraderInstance.className(), upgraderInstance.fieldName()));
		cb.add(content);
		cb.add(")");
		return VersionedComputedType.super.wrapWithUpgrade(basePackageName, cb.build(), next);
	}

	@Override
	public Stream<ComputedType> getDependencies() {
		return this.data.values().stream().map(computedTypeSupplier::get);
	}

	@Override
	public Stream<ComputedType> getDependents() {
		return computedTypeSupplier.getDependents(type);
	}

	@Override
	public String toString() {
		return type.type() + " (base, v" + getVersion() + ")";
	}
}
