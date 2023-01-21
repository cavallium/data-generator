package it.cavallium.data.generator.plugin;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.TypeName;
import it.cavallium.data.generator.plugin.ComputedType.VersionedComputedType;
import java.util.stream.Stream;

public sealed interface ComputedType permits VersionedComputedType, ComputedTypeArray, ComputedTypeCustom,
		ComputedTypeNative, ComputedTypeNullable {

	String getName();

	TypeName getJTypeName(String basePackageName);

	TypeName getJSerializerName(String basePackageName);

	FieldLocation getJSerializerInstance(String basePackageName);

	TypeName getJUpgraderName(String basePackageName);

	FieldLocation getJUpgraderInstance(String basePackageName);

	default CodeBlock wrapWithUpgrade(String basePackageName, CodeBlock content, ComputedType next) {
		return content;
	}

	sealed interface VersionedComputedType extends ComputedType permits ComputedTypeArrayVersioned, ComputedTypeBase,
			ComputedTypeNullableVersioned, ComputedTypeSuper {

		ComputedVersion getVersion();

		default boolean shouldUpgradeAfter(ComputedVersion version) {
			return !version.isCurrent() && version.getVersion() == this.getVersion().getVersion();
		}

		ComputedType withChangeAtVersion(ComputedVersion version, VersionChangeChecker versionChangeChecker);
	}

	/**
	 * Get all types that are required by this type
	 */
	Stream<ComputedType> getDependencies();

	/**
	 * Get all types that require this type
	 */
	Stream<ComputedType> getDependents();

	default boolean isPrimitive() {
		return false;
	}

}
