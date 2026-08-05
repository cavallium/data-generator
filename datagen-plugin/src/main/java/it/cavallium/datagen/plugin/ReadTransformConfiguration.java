package it.cavallium.datagen.plugin;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Recursive, declarative transform used only while decoding serialized data directly into a newer
 * graph. Exactly one operation must be configured at every node. The optional root {@link #type}
 * names a terminal result type when it intentionally bypasses later structural boundaries.
 */
public final class ReadTransformConfiguration {

	public String type;
	public Custom custom;
	public Constant constant;
	public Identity identity;
	public InvokeStatic invokeStatic;
	public Construct construct;
	public MapNullable mapNullable;
	public MapArray mapArray;

	public Kind kind() {
		Kind result = null;
		if (custom != null) result = unique(result, Kind.CUSTOM);
		if (constant != null) result = unique(result, Kind.CONSTANT);
		if (identity != null) result = unique(result, Kind.IDENTITY);
		if (invokeStatic != null) result = unique(result, Kind.INVOKE_STATIC);
		if (construct != null) result = unique(result, Kind.CONSTRUCT);
		if (mapNullable != null) result = unique(result, Kind.MAP_NULLABLE);
		if (mapArray != null) result = unique(result, Kind.MAP_ARRAY);
		if (result == null) throw new IllegalArgumentException("readTransform must declare exactly one operation");
		return result;
	}

	public void validate(String coordinate) {
		Kind operation;
		try {
			operation = kind();
		} catch (IllegalArgumentException failure) {
			throw new IllegalArgumentException(coordinate + ": " + failure.getMessage(), failure);
		}
		switch (operation) {
			case CUSTOM -> custom.location(coordinate + ".custom");
			case CONSTANT -> { }
			case IDENTITY -> requireText(identity.source, coordinate + ".identity.source");
			case INVOKE_STATIC -> {
				requireText(invokeStatic.method, coordinate + ".invokeStatic.method");
				validateChildren(invokeStatic.getArguments(), coordinate + ".invokeStatic.arguments");
			}
			case CONSTRUCT -> {
				if (isBlank(construct.className) && isBlank(construct.type)) {
					throw new IllegalArgumentException(coordinate
							+ ".construct must declare schema type or className");
				}
				if (!isBlank(construct.className) && !isBlank(construct.type)) {
					throw new IllegalArgumentException(coordinate
							+ ".construct must not declare both schema type and className");
				}
				validateChildren(construct.getArguments(), coordinate + ".construct.arguments");
			}
			case MAP_NULLABLE -> {
				requireChild(mapNullable.source, coordinate + ".mapNullable.source");
				requireChild(mapNullable.transform, coordinate + ".mapNullable.transform");
			}
			case MAP_ARRAY -> {
				requireChild(mapArray.source, coordinate + ".mapArray.source");
				requireChild(mapArray.transform, coordinate + ".mapArray.transform");
			}
		}
	}

	public boolean isCustom() {
		return kind() == Kind.CUSTOM;
	}

	public String getResultType(String defaultType) {
		return isBlank(type) ? defaultType : type;
	}

	public boolean hasResultTypeOverride() {
		return !isBlank(type);
	}

	/** Every schema type named explicitly by this node or one of its descendants. */
	public Stream<String> declaredSchemaTypes() {
		Stream<String> own = isBlank(type) ? Stream.empty() : Stream.of(type);
		Stream<String> operation = switch (kind()) {
			case CUSTOM, CONSTANT, IDENTITY -> Stream.empty();
			case INVOKE_STATIC -> invokeStatic.getArguments().stream()
					.flatMap(ReadTransformConfiguration::declaredSchemaTypes);
			case CONSTRUCT -> Stream.concat(
					isBlank(construct.type) ? Stream.empty() : Stream.of(construct.type),
					construct.getArguments().stream().flatMap(ReadTransformConfiguration::declaredSchemaTypes));
			case MAP_NULLABLE -> Stream.concat(mapNullable.source.declaredSchemaTypes(),
					mapNullable.transform.declaredSchemaTypes());
			case MAP_ARRAY -> Stream.concat(mapArray.source.declaredSchemaTypes(),
					mapArray.transform.declaredSchemaTypes());
		};
		return Stream.concat(own, operation);
	}

	public ReadTransformConfiguration copy() {
		var copy = new ReadTransformConfiguration();
		copy.type = type;
		copy.custom = custom == null ? null : custom.copy();
		copy.constant = constant == null ? null : constant.copy();
		copy.identity = identity == null ? null : identity.copy();
		copy.invokeStatic = invokeStatic == null ? null : invokeStatic.copy();
		copy.construct = construct == null ? null : construct.copy();
		copy.mapNullable = mapNullable == null ? null : mapNullable.copy();
		copy.mapArray = mapArray == null ? null : mapArray.copy();
		return copy;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) return true;
		if (!(object instanceof ReadTransformConfiguration that)) return false;
		return Objects.equals(type, that.type)
				&& Objects.equals(custom, that.custom)
				&& Objects.equals(constant, that.constant)
				&& Objects.equals(identity, that.identity)
				&& Objects.equals(invokeStatic, that.invokeStatic)
				&& Objects.equals(construct, that.construct)
				&& Objects.equals(mapNullable, that.mapNullable)
				&& Objects.equals(mapArray, that.mapArray);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, custom, constant, identity, invokeStatic, construct, mapNullable, mapArray);
	}

	private static Kind unique(Kind current, Kind next) {
		if (current != null) throw new IllegalArgumentException("readTransform must declare exactly one operation");
		return next;
	}

	private static void validateChildren(List<ReadTransformConfiguration> children, String coordinate) {
		for (int index = 0; index < children.size(); index++) {
			requireChild(children.get(index), coordinate + "[" + index + "]");
		}
	}

	private static void requireChild(ReadTransformConfiguration child, String coordinate) {
		if (child == null) throw new IllegalArgumentException(coordinate + " is required");
		child.validate(coordinate);
	}

	private static String requireText(String value, String coordinate) {
		if (isBlank(value)) throw new IllegalArgumentException(coordinate + " is required");
		return value;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	public enum Kind {
		CUSTOM,
		CONSTANT,
		IDENTITY,
		INVOKE_STATIC,
		CONSTRUCT,
		MAP_NULLABLE,
		MAP_ARRAY
	}

	public static final class Custom {

		public String className;
		public String instance;

		public JInterfaceLocation location(String coordinate) {
			try {
				return JInterfaceLocation.parse(className, instance);
			} catch (RuntimeException failure) {
				throw new IllegalArgumentException(coordinate
						+ " must declare exactly one of className or instance", failure);
			}
		}

		private Custom copy() {
			var copy = new Custom();
			copy.className = className;
			copy.instance = instance;
			return copy;
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof Custom that && Objects.equals(className, that.className)
					&& Objects.equals(instance, that.instance);
		}

		@Override
		public int hashCode() {
			return Objects.hash(className, instance);
		}
	}

	public static final class Constant {

		public Object value;

		private Constant copy() {
			var copy = new Constant();
			copy.value = value;
			return copy;
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof Constant that && Objects.equals(value, that.value);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(value);
		}
	}

	public static final class Identity {

		public String source;

		private Identity copy() {
			var copy = new Identity();
			copy.source = source;
			return copy;
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof Identity that && Objects.equals(source, that.source);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(source);
		}
	}

	public static final class InvokeStatic {

		public String method;
		public List<ReadTransformConfiguration> arguments;

		public List<ReadTransformConfiguration> getArguments() {
			return Objects.requireNonNullElse(arguments, List.of());
		}

		private InvokeStatic copy() {
			var copy = new InvokeStatic();
			copy.method = method;
			copy.arguments = getArguments().stream().map(ReadTransformConfiguration::copy).toList();
			return copy;
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof InvokeStatic that && Objects.equals(method, that.method)
					&& Objects.equals(getArguments(), that.getArguments());
		}

		@Override
		public int hashCode() {
			return Objects.hash(method, getArguments());
		}
	}

	public static final class Construct {

		/** Schema type. Defaults to the surrounding transform result when omitted. */
		public String type;
		/** External Java class; mutually exclusive with schema type. */
		public String className;
		/** Optional static factory; when absent, generated schema types use {@code unsafeOfOwned}. */
		public String factory;
		public List<ReadTransformConfiguration> arguments;

		public List<ReadTransformConfiguration> getArguments() {
			return Objects.requireNonNullElse(arguments, List.of());
		}

		private Construct copy() {
			var copy = new Construct();
			copy.type = type;
			copy.className = className;
			copy.factory = factory;
			copy.arguments = getArguments().stream().map(ReadTransformConfiguration::copy).toList();
			return copy;
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof Construct that && Objects.equals(type, that.type)
					&& Objects.equals(className, that.className)
					&& Objects.equals(factory, that.factory)
					&& Objects.equals(getArguments(), that.getArguments());
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, className, factory, getArguments());
		}
	}

	public static final class MapNullable {

		public ReadTransformConfiguration source;
		public ReadTransformConfiguration transform;

		private MapNullable copy() {
			var copy = new MapNullable();
			copy.source = source == null ? null : source.copy();
			copy.transform = transform == null ? null : transform.copy();
			return copy;
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof MapNullable that && Objects.equals(source, that.source)
					&& Objects.equals(transform, that.transform);
		}

		@Override
		public int hashCode() {
			return Objects.hash(source, transform);
		}
	}

	public static final class MapArray {

		public ReadTransformConfiguration source;
		public ReadTransformConfiguration transform;

		private MapArray copy() {
			var copy = new MapArray();
			copy.source = source == null ? null : source.copy();
			copy.transform = transform == null ? null : transform.copy();
			return copy;
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof MapArray that && Objects.equals(source, that.source)
					&& Objects.equals(transform, that.transform);
		}

		@Override
		public int hashCode() {
			return Objects.hash(source, transform);
		}
	}
}
