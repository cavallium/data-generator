package it.cavallium.datagen.plugin;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import it.cavallium.datagen.nativedata.UpgradeUtil;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class ComputedTypeArrayFixed implements ComputedTypeArray {

	private final String baseType;
	private final ComputedVersion currentVersion;

	private ComputedType computedChild;
	private final ComputedTypeSupplier computedTypeSupplier;

	public ComputedTypeArrayFixed(String baseType, ComputedVersion currentVersion, ComputedTypeSupplier computedTypeSupplier) {
		this.baseType = baseType;
		this.currentVersion = currentVersion;
		this.computedTypeSupplier = computedTypeSupplier;
	}

	public ComputedType getBase() {
		return child();
	}

	public ComputedType child() {
		synchronized (this) {
			if (computedChild == null) {
				computedChild = computedTypeSupplier.get(baseType);
				if (computedChild instanceof ComputedTypeNullableVersioned) {
					throw new IllegalStateException();
				} else if (computedChild instanceof ComputedTypeArrayFixed) {
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

		ComputedTypeArrayFixed that = (ComputedTypeArrayFixed) o;

		return Objects.equals(baseType, that.baseType);
	}

	@Override
	public int hashCode() {
		return baseType != null ? baseType.hashCode() : 0;
	}

	@Override
	public String getName() {
		return "§" + baseType;
	}

	@Override
	public TypeName getJTypeName(String basePackageName) {
		return ArrayTypeName.of(child().getJTypeName(basePackageName));
	}

	@Override
	public TypeName getJTypeNameGeneric(String basePackageName) {
		return getJTypeName(basePackageName);
	}

	@Override
	public ClassName getJSerializerName(String basePackageName) {
		return ClassName.get(currentVersion.getSerializersPackage(basePackageName), "Array" + baseType + "Serializer");
	}

	@Override
	public FieldLocation getJSerializerInstance(String basePackageName) {
		var className = ClassName.get(currentVersion.getPackage(basePackageName), "Version");
		var serializerFieldName = "Array" + baseType + "SerializerInstance";
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
		builder.add(", $T.class, $T.emptyArray(), $T.$N)", next.getJTypeName(basePackageName),
				next.getJSerializerName(basePackageName),
				upgraderInstance.className(), upgraderInstance.fieldName());
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
		return baseType + "[] (custom)";
	}
}
