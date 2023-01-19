package it.cavallium.data.generator.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public sealed interface ComputedType {

	String getName();

	sealed interface VersionedComputedType extends ComputedType {

		int getVersion();

		ComputedType withChangeAtVersion(int version, VersionChangeChecker versionChangeChecker);
	}

	/**
	 * Get all types that are required by this type
	 */
	Stream<ComputedType> getDependencies();

	/**
	 * Get all types that require this type
	 */
	Stream<ComputedType> getDependents();

	final class ComputedBaseType implements VersionedComputedType {

		private final VersionedType type;
		private final String stringRepresenter;

		private final LinkedHashMap<String, VersionedType> data;
		private LinkedHashMap<String, ComputedType> computedData;
		private final ComputedTypeSupplier computedTypeSupplier;

		public ComputedBaseType(VersionedType type,
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
		public int getVersion() {
			return type.version();
		}

		@Override
		public ComputedBaseType withChangeAtVersion(int version, VersionChangeChecker versionChangeChecker) {
			var newData = new LinkedHashMap<String, VersionedType>();
			data.forEach((k, v) -> newData.put(k, v.withVersionIfChanged(version, versionChangeChecker)));
			return new ComputedBaseType(type.withVersion(version), stringRepresenter, newData, computedTypeSupplier);
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

			ComputedBaseType that = (ComputedBaseType) o;

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
		public Stream<ComputedType> getDependencies() {
			return this.data.values().stream().map(computedTypeSupplier::get);
		}

		@Override
		public Stream<ComputedType> getDependents() {
			return computedTypeSupplier.getDependents(type);
		}
	}

	final class ComputedCustomType implements ComputedType {

		private final String type;
		private final String javaClass;
		private final String serializer;
		private final ComputedTypeSupplier computedTypeSupplier;

		public ComputedCustomType(String type, String javaClass, String serializer, ComputedTypeSupplier computedTypeSupplier) {
			this.type = type;
			this.javaClass = javaClass;
			this.serializer = serializer;
			this.computedTypeSupplier = computedTypeSupplier;
		}

		public String getType() {
			return type;
		}

		public String getJavaClass() {
			return javaClass;
		}

		public String getSerializer() {
			return serializer;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}

			ComputedCustomType that = (ComputedCustomType) o;

			if (!Objects.equals(type, that.type)) {
				return false;
			}
			if (!Objects.equals(javaClass, that.javaClass)) {
				return false;
			}
			return Objects.equals(serializer, that.serializer);
		}

		@Override
		public int hashCode() {
			int result = type != null ? type.hashCode() : 0;
			result = 31 * result + (javaClass != null ? javaClass.hashCode() : 0);
			result = 31 * result + (serializer != null ? serializer.hashCode() : 0);
			return result;
		}

		@Override
		public String getName() {
			return type;
		}

		@Override
		public Stream<ComputedType> getDependencies() {
			return Stream.of();
		}

		@Override
		public Stream<ComputedType> getDependents() {
			return computedTypeSupplier.getDependents(new VersionedType(type, 0));
		}
	}

	final class ComputedNullableType implements VersionedComputedType {

		private final VersionedType baseType;

		private ComputedType computedChild;
		private final ComputedTypeSupplier computedTypeSupplier;

		public ComputedNullableType(VersionedType baseType, ComputedTypeSupplier computedTypeSupplier) {
			this.baseType = baseType;
			this.computedTypeSupplier = computedTypeSupplier;
		}

		public String getBaseType() {
			return baseType.type();
		}

		@Override
		public int getVersion() {
			return baseType.version();
		}

		@Override
		public ComputedNullableType withChangeAtVersion(int version, VersionChangeChecker versionChangeChecker) {
			return new ComputedNullableType(baseType.withVersion(version), computedTypeSupplier);
		}

		public ComputedType child() {
			synchronized (this) {
				if (computedChild == null) {
					computedChild = computedTypeSupplier.get(baseType);
				}
			}
			if (computedChild instanceof ComputedNullableType) {
				throw new IllegalStateException();
			} else if (computedChild instanceof ComputedArrayType) {
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

			ComputedNullableType that = (ComputedNullableType) o;

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
		public Stream<ComputedType> getDependencies() {
			return Stream.of(child());
		}

		@Override
		public Stream<ComputedType> getDependents() {
			return computedTypeSupplier.getDependents(new VersionedType(getName(), 0));
		}
	}

	final class ComputedSuperType implements VersionedComputedType {

		private final VersionedType type;
		private final List<VersionedType> subTypes;

		private List<ComputedType> computedSubTypes;
		private final ComputedTypeSupplier computedTypeSupplier;

		public ComputedSuperType(VersionedType type,
				List<VersionedType> subType,
				ComputedTypeSupplier computedTypeSupplier) {
			this.type = type;
			this.subTypes = subType;
			this.computedTypeSupplier = computedTypeSupplier;
		}

		public String getType() {
			return type.type();
		}

		@Override
		public int getVersion() {
			return type.version();
		}

		@Override
		public ComputedSuperType withChangeAtVersion(int version, VersionChangeChecker versionChangeChecker) {
			return new ComputedSuperType(type.withVersion(version),
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

			ComputedSuperType that = (ComputedSuperType) o;

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
			return computedTypeSupplier.getDependents(new VersionedType(getName(), 0));
		}

		@Override
		public String getName() {
			return type.type();
		}
	}

	final class ComputedArrayType implements VersionedComputedType {

		private final VersionedType baseType;

		private ComputedType computedChild;
		private final ComputedTypeSupplier computedTypeSupplier;

		public ComputedArrayType(VersionedType baseType, ComputedTypeSupplier computedTypeSupplier) {
			this.baseType = baseType;
			this.computedTypeSupplier = computedTypeSupplier;
		}

		public String getBaseType() {
			return baseType.type();
		}

		@Override
		public int getVersion() {
			return baseType.version();
		}

		@Override
		public ComputedArrayType withChangeAtVersion(int version, VersionChangeChecker versionChangeChecker) {
			return new ComputedArrayType(baseType.withVersion(version), computedTypeSupplier);
		}

		public ComputedType child() {
			synchronized (this) {
				if (computedChild == null) {
					computedChild = computedTypeSupplier.get(baseType);
				}
			}
			if (computedChild instanceof ComputedNullableType) {
				throw new IllegalStateException();
			} else if (computedChild instanceof ComputedArrayType) {
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

			ComputedArrayType that = (ComputedArrayType) o;

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
		public Stream<ComputedType> getDependencies() {
			return Stream.of(child());
		}

		@Override
		public Stream<ComputedType> getDependents() {
			return computedTypeSupplier.getDependents(new VersionedType(getName(), 0));
		}
	}

	final class ComputedNativeType implements ComputedType {

		private final String type;
		private final ComputedTypeSupplier computedTypeSupplier;

		public ComputedNativeType(String type, ComputedTypeSupplier computedTypeSupplier) {
			this.type = type;
			this.computedTypeSupplier = computedTypeSupplier;
		}

		public String getName() {
			return type;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}

			ComputedNativeType that = (ComputedNativeType) o;

			return Objects.equals(type, that.type);
		}

		@Override
		public int hashCode() {
			return type != null ? type.hashCode() : 0;
		}

		@Override
		public Stream<ComputedType> getDependencies() {
			return Stream.of();
		}

		@Override
		public Stream<ComputedType> getDependents() {
			return computedTypeSupplier.getDependents(new VersionedType(getName(), 0));
		}

		public static List<ComputedNativeType> get(ComputedTypeSupplier computedTypeSupplier) {
			return Stream.of("String",
					"boolean",
					"short",
					"char",
					"int",
					"long",
					"float",
					"double",
					"byte",
					"Int52").map(name -> new ComputedNativeType(name, computedTypeSupplier)).toList();
		}
	}
}
