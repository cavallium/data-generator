package it.cavallium.datagen.plugin;

import com.palantir.javapoet.TypeName;
import it.cavallium.datagen.plugin.ComputedType.VersionedComputedType;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates generated Java method surfaces before any output directory is created. */
final class GeneratedSurfaceValidator {

	private GeneratedSurfaceValidator() {}

	static void validate(DataModel model) {
		for (ComputedVersion version : model.getVersionsSet()) {
			model.getBaseTypesComputed(version).forEach(type -> validateRecord(model, version, type));
			model.getSuperTypesComputed(version).forEach(type -> validateUnion(model, version, type));
		}
	}

	private static void validateRecord(DataModel model, ComputedVersion version, ComputedTypeBase record) {
		var methods = new Surface(version, "record " + record.getName());
		methods.add("hashCode", List.of(), "generated Object override hashCode");
		methods.add("toString", List.of(), "generated Object override toString");
		methods.add("equals", List.of("java.lang.Object"), "generated Object override equals");
		methods.add("getClass", List.of(), "final java.lang.Object method getClass");
		methods.add("notify", List.of(), "final java.lang.Object method notify");
		methods.add("notifyAll", List.of(), "final java.lang.Object method notifyAll");
		methods.add("wait", List.of(), "final java.lang.Object method wait");
		methods.add("getBaseType$", List.of(), "generated base-type metadata method");
		methods.add("builder", List.of(), "generated builder factory");
		addInheritedUnionMetadata(methods, model, record);
		record.getData().forEach((name, type) -> addRecordField(methods, record.getName(), name, type));

		var builder = new Surface(version, "builder for record " + record.getName());
		builder.add("toString", List.of(), "generated builder Object override toString");
		builder.add("getClass", List.of(), "final java.lang.Object method getClass");
		builder.add("notify", List.of(), "final java.lang.Object method notify");
		builder.add("notifyAll", List.of(), "final java.lang.Object method notifyAll");
		builder.add("wait", List.of(), "final java.lang.Object method wait");
		builder.add("build", List.of(), "generated builder terminal method");
		builder.add("buildIfChanged", List.of(erase(record.getJTypeName(""))),
				"generated builder change-detection method");
		record.getData().forEach((name, type) -> addBuilderField(builder, record.getName(), name, type));
	}

	private static void validateUnion(DataModel model, ComputedVersion version, ComputedTypeSuper union) {
		var methods = new Surface(version, "union interface " + union.getName());
		methods.add("hashCode", List.of(), "java.lang.Object method hashCode");
		methods.add("toString", List.of(), "java.lang.Object method toString");
		methods.add("equals", List.of("java.lang.Object"), "java.lang.Object method equals");
		methods.add("getClass", List.of(), "final java.lang.Object method getClass");
		methods.add("notify", List.of(), "final java.lang.Object method notify");
		methods.add("notifyAll", List.of(), "final java.lang.Object method notifyAll");
		methods.add("wait", List.of(), "final java.lang.Object method wait");
		methods.add("getBaseType$", List.of(), "generated base-type metadata method");
		addUnionMetadata(methods, model, union, new LinkedHashSet<>());
		if (version.isCurrent()) methods.add("builder", List.of(), "generated union builder method");
		StreamSupport.concat(model.getCommonInterfaceData(union), model.getCommonInterfaceGetters(union))
				.forEach(entry -> addInterfaceField(methods, union.getName(), entry.getKey(), entry.getValue()));
		model.getCommonInterfaceData(union)
				.forEach(entry -> addInterfaceMutator(methods, union.getName(), entry.getKey(), entry.getValue()));
	}

	private static void addInheritedUnionMetadata(Surface surface,
			DataModel model,
			VersionedComputedType type) {
		Set<String> visited = new LinkedHashSet<>();
		model.getSuperTypesOf(type, true).forEach(union -> addUnionMetadata(surface, model, union, visited));
	}

	private static void addUnionMetadata(Surface surface,
			DataModel model,
			ComputedTypeSuper union,
			Set<String> visited) {
		if (!visited.add(union.getName())) return;
		surface.add("getMetaId$" + union.getName(), List.of(),
				"generated union metadata method for " + union.getName());
		model.getSuperTypesOf(union, true)
				.forEach(parent -> addUnionMetadata(surface, model, parent, visited));
		model.getExtendsInterfaces(union)
				.forEach(parent -> addUnionMetadata(surface, model, parent, visited));
	}

	private static void addRecordField(Surface surface, String owner, String name, ComputedType type) {
		String origin = "field " + owner + "." + name;
		if (type instanceof ComputedTypeNullable nullable) {
			String valueType = erase(nullable.getBase().getJTypeName(""));
			surface.add("has" + capitalize(name), List.of(), origin + " nullable presence accessor");
			surface.add(name, List.of(), origin + " nullable value accessor");
			if (!nullable.getBase().getJTypeName("").isPrimitive()) {
				surface.add(name + "OrNull", List.of(), origin + " nullable-or-null accessor");
			}
			surface.add("set" + capitalize(name), List.of(valueType), origin + " nullable setter");
			surface.add("clear" + capitalize(name), List.of(), origin + " nullable clearer");
			return;
		}
		if (type instanceof ComputedTypeArray array) {
			surface.add(name + "Size", List.of(), origin + " array-size accessor");
			surface.add(name, List.of("int"), origin + " indexed array accessor");
			surface.add(name + "Copy", List.of(), origin + " array-copy accessor");
			surface.add(name + "UnsafeArray", List.of(), origin + " unsafe-array accessor");
			surface.add("set" + capitalize(name), List.of(erase(array.getJTypeName(""))), origin + " array setter");
			return;
		}
		surface.add(name, List.of(), origin + " accessor");
		surface.add("set" + capitalize(name), List.of(erase(type.getJTypeNameGeneric(""))), origin + " setter");
	}

	private static void addBuilderField(Surface surface, String owner, String name, ComputedType type) {
		String origin = "field " + owner + "." + name;
		ComputedType parameterType = type instanceof ComputedTypeNullable nullable ? nullable.getBase() : type;
		surface.add("set" + capitalize(name), List.of(erase(parameterType.getJTypeNameGeneric(""))),
				origin + " builder setter");
		if (type instanceof ComputedTypeNullable) {
			surface.add("clear" + capitalize(name), List.of(), origin + " builder clearer");
		}
	}

	private static void addInterfaceField(Surface surface, String owner, String name, ComputedType type) {
		String origin = "common field " + owner + "." + name;
		if (type instanceof ComputedTypeNullable nullable) {
			surface.add("has" + capitalize(name), List.of(), origin + " nullable presence accessor");
			surface.add(name, List.of(), origin + " nullable value accessor");
			if (!nullable.getBase().getJTypeName("").isPrimitive()) {
				surface.add(name + "OrNull", List.of(), origin + " nullable-or-null accessor");
			}
			return;
		}
		if (type instanceof ComputedTypeArray) {
			surface.add(name + "Size", List.of(), origin + " array-size accessor");
			surface.add(name, List.of("int"), origin + " indexed array accessor");
			surface.add(name + "Copy", List.of(), origin + " array-copy accessor");
			surface.add(name + "UnsafeArray", List.of(), origin + " unsafe-array accessor");
			return;
		}
		surface.add(name, List.of(), origin + " accessor");
	}

	private static void addInterfaceMutator(Surface surface, String owner, String name, ComputedType type) {
		String origin = "common field " + owner + "." + name;
		ComputedType parameterType = type instanceof ComputedTypeNullable nullable ? nullable.getBase() : type;
		surface.add("set" + capitalize(name), List.of(erase(parameterType.getJTypeNameGeneric(""))),
				origin + " union setter");
		if (type instanceof ComputedTypeNullable) {
			surface.add("clear" + capitalize(name), List.of(), origin + " union clearer");
		}
	}

	private static String erase(TypeName type) {
		String value = type.toString();
		int generic = value.indexOf('<');
		return generic < 0 ? value : value.substring(0, generic);
	}

	private static String capitalize(String value) {
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}

	private record Signature(String name, List<String> erasedParameters) {
		@Override
		public String toString() {
			return name + "(" + String.join(", ", erasedParameters) + ")";
		}
	}

	private static final class Surface {
		private final ComputedVersion version;
		private final String owner;
		private final Map<Signature, String> origins = new LinkedHashMap<>();

		private Surface(ComputedVersion version, String owner) {
			this.version = version;
			this.owner = owner;
		}

		private void add(String name, List<String> erasedParameters, String origin) {
			Signature signature = new Signature(name, List.copyOf(erasedParameters));
			String previous = origins.putIfAbsent(signature, origin);
			if (previous != null && !previous.equals(origin)) {
				throw new IllegalArgumentException("Generated public method collision in " + owner
						+ " at version " + version.getVersion() + " (" + version.getName() + "): "
						+ signature + " originates from both " + previous + " and " + origin);
			}
		}
	}

	private static final class StreamSupport {
		private static <K, V> java.util.stream.Stream<Map.Entry<K, V>> concat(
				java.util.stream.Stream<Map.Entry<K, V>> first,
				java.util.stream.Stream<Map.Entry<K, V>> second) {
			return java.util.stream.Stream.concat(first, second).distinct();
		}
	}
}
