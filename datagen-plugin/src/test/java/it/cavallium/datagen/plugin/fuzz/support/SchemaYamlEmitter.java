package it.cavallium.datagen.plugin.fuzz.support;

import it.cavallium.datagen.plugin.ClassConfiguration;
import it.cavallium.datagen.plugin.CustomTypesConfiguration;
import it.cavallium.datagen.plugin.DetailsConfiguration;
import it.cavallium.datagen.plugin.InterfaceDataConfiguration;
import it.cavallium.datagen.plugin.MoveDataConfiguration;
import it.cavallium.datagen.plugin.NewDataConfiguration;
import it.cavallium.datagen.plugin.NewSubtypeConfiguration;
import it.cavallium.datagen.plugin.NewTypeConfiguration;
import it.cavallium.datagen.plugin.ProjectionConfiguration;
import it.cavallium.datagen.plugin.ReadTransformConfiguration;
import it.cavallium.datagen.plugin.RemoveDataConfiguration;
import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;
import it.cavallium.datagen.plugin.TransformationConfiguration;
import it.cavallium.datagen.plugin.UpgradeDataConfiguration;
import it.cavallium.datagen.plugin.VersionConfiguration;
import it.cavallium.datagen.plugin.VersionTransformation;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.yaml.snakeyaml.Yaml;

/**
 * Deterministic YAML emitter for {@link SourcesGeneratorConfiguration}, plus a loader that uses the very same
 * SnakeYAML path as {@code SourcesGenerator.load}.
 *
 * <p>Emission is hand-rolled on top of a normalized tree (see {@link #node(SourcesGeneratorConfiguration)}) because
 * the production POJOs cannot be dumped by reflection: {@link CustomTypesConfiguration} exposes {@code javaClass}
 * only through a setter, and {@link ProjectionConfiguration#consumeToEnd} has {@code null == Boolean.TRUE}
 * semantics. Ordering rules:</p>
 * <ul>
 *   <li>map keys are emitted sorted (byte-determinism for {@code HashMap} inputs), except
 *   {@link ClassConfiguration#data} and {@link ProjectionConfiguration#fields}, which are
 *   {@link java.util.LinkedHashMap} wire contracts and keep their iteration order;</li>
 *   <li>lists keep their order (union alternatives, transformations, context parameters);</li>
 *   <li>{@link InterfaceDataConfiguration#extendInterfaces} is a {@link java.util.HashSet} and is sorted;</li>
 *   <li>{@code null} / empty maps and lists are omitted; a bean node that normalizes to empty is emitted as
 *   {@code {}} so that, for example, a {@code versions: {v1: null}} key survives the round-trip.</li>
 * </ul>
 */
public final class SchemaYamlEmitter {

	private SchemaYamlEmitter() {
	}

	/**
	 * Renders the configuration as UTF-8 YAML text. Two equal configurations always produce byte-identical text.
	 */
	public static String emit(SourcesGeneratorConfiguration config) {
		StringBuilder builder = new StringBuilder();
		Object root = node(config);
		if (root instanceof Map<?, ?> map) {
			emitBlockMap(toStringMap(map), "", "", 0, builder);
		} else {
			builder.append(scalarText(root)).append('\n');
		}
		return builder.toString();
	}

	/**
	 * Parses YAML back into the configuration model using the same technique as the private
	 * {@code SourcesGenerator} constructor: {@code new Yaml().loadAs(stream, SourcesGeneratorConfiguration.class)}.
	 */
	public static SourcesGeneratorConfiguration load(String yaml) {
		Objects.requireNonNull(yaml, "yaml");
		Yaml parser = new Yaml();
		SourcesGeneratorConfiguration configuration = parser.loadAs(
				new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
				SourcesGeneratorConfiguration.class);
		if (configuration == null) {
			throw new IllegalStateException("YAML document is empty");
		}
		return configuration;
	}

	// ------------------------------------------------------------------------------------------------
	// normalization
	// ------------------------------------------------------------------------------------------------

	/**
	 * Builds the normalized, order-stable tree that both {@link #emit} and
	 * {@link SchemaCanonicalizer#canonical} render. Values are {@link String}, {@link Number}, {@link Boolean},
	 * {@link java.util.LinkedHashMap} or {@link java.util.List} (plus {@link SetNode} for sorted sets).
	 */
	static Object node(SourcesGeneratorConfiguration config) {
		Objects.requireNonNull(config, "config");
		Map<String, Object> root = new java.util.LinkedHashMap<>();
		putString(root, "currentVersion", config.currentVersion);
		putSortedBeanMap(root, "interfacesData", config.interfacesData, SchemaYamlEmitter::interfaceNode);
		putSortedBeanMap(root, "baseTypesData", config.baseTypesData, SchemaYamlEmitter::classNode);
		putSortedMap(root, "superTypesData", config.superTypesData, SchemaYamlEmitter::stringList);
		putSortedBeanMap(root, "customTypesData", config.customTypesData, SchemaYamlEmitter::customTypeNode);
		putSortedBeanMap(root, "projectionsData", config.projectionsData, SchemaYamlEmitter::projectionNode);
		putSortedBeanMap(root, "versions", config.versions, SchemaYamlEmitter::versionNode);
		return root;
	}

	private static Object interfaceNode(InterfaceDataConfiguration config) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		if (config.extendInterfaces != null && !config.extendInterfaces.isEmpty()) {
			node.put("extendInterfaces", new SetNode(sorted(config.extendInterfaces)));
		}
		putSortedMap(node, "commonData", config.commonData, SchemaYamlEmitter::identity);
		putSortedMap(node, "commonGetters", config.commonGetters, SchemaYamlEmitter::identity);
		return node;
	}

	private static Object classNode(ClassConfiguration config) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		putString(node, "stringRepresenter", config.stringRepresenter);
		// `data` order is the wire contract: keep the declared iteration order.
		putOrderedMap(node, "data", config.data, SchemaYamlEmitter::identity, true);
		return node;
	}

	private static Object customTypeNode(CustomTypesConfiguration config) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		// `javaClass` is a write-only bean property; read it through the production getter.
		putString(node, "javaClass", config.getJavaClassString());
		putString(node, "codec", config.codec);
		if (config.fixedSize != null) {
			node.put("fixedSize", config.fixedSize);
		}
		return node;
	}

	private static Object projectionNode(ProjectionConfiguration config) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		putString(node, "sourceType", config.sourceType);
		// projection `fields` order is the generated record component order.
		putOrderedMap(node, "fields", config.fields, SchemaYamlEmitter::identity, true);
		// production treats a missing flag as true (ProjectionConfiguration#consumesToEnd), so only false is emitted.
		if (!config.consumesToEnd()) {
			node.put("consumeToEnd", Boolean.FALSE);
		}
		return node;
	}

	private static Object versionNode(VersionConfiguration config) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		putString(node, "previousVersion", config.previousVersion);
		if (config.details != null) {
			Map<String, Object> details = new java.util.LinkedHashMap<>();
			DetailsConfiguration source = config.details;
			putString(details, "changelog", source.changelog);
			putBean(node, "details", details);
		}
		if (config.transformations != null && !config.transformations.isEmpty()) {
			node.put("transformations", config.transformations.stream().map(SchemaYamlEmitter::transformationNode).toList());
		}
		putSortedMap(node, "typeVersions", config.typeVersions, SchemaYamlEmitter::identity);
		putSortedMap(node, "dependentTypes", config.dependentTypes, SchemaYamlEmitter::stringList);
		return node;
	}

	private static Object transformationNode(VersionTransformation transformation) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		if (transformation.moveData != null) {
			node.put("moveData", moveNode(transformation.moveData));
		}
		if (transformation.removeData != null) {
			node.put("removeData", removeNode(transformation.removeData));
		}
		if (transformation.newData != null) {
			node.put("newData", newNodeNode(transformation.newData));
		}
		if (transformation.upgradeData != null) {
			node.put("upgradeData", upgradeNode(transformation.upgradeData));
		}
		if (transformation.newType != null) {
			node.put("newType", newTypeNode(transformation.newType));
		}
		if (transformation.newSubtype != null) {
			node.put("newSubtype", newSubtypeNode(transformation.newSubtype));
		}
		return node;
	}

	private static Object moveNode(MoveDataConfiguration config) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		putTransformClass(node, config);
		putString(node, "from", config.from);
		putString(node, "to", config.to);
		if (config.index != null) {
			node.put("index", config.index);
		}
		return node;
	}

	private static Object removeNode(RemoveDataConfiguration config) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		putTransformClass(node, config);
		putString(node, "from", config.from);
		return node;
	}

	private static Object newNodeNode(NewDataConfiguration config) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		putTransformClass(node, config);
		putString(node, "to", config.to);
		putString(node, "type", config.type);
		putString(node, "initializer", config.initializer);
		putString(node, "initializerInstance", config.initializerInstance);
		if (config.readTransform != null) {
			putBean(node, "readTransform", readTransformNode(config.readTransform));
		}
		if (config.index != null) {
			node.put("index", config.index);
		}
		putStringList(node, "contextParameters", config.contextParameters);
		return node;
	}

	private static Object upgradeNode(UpgradeDataConfiguration config) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		putTransformClass(node, config);
		putString(node, "from", config.from);
		putString(node, "type", config.type);
		putString(node, "upgrader", config.upgrader);
		putString(node, "upgraderInstance", config.upgraderInstance);
		if (config.readTransform != null) {
			putBean(node, "readTransform", readTransformNode(config.readTransform));
		}
		putStringList(node, "contextParameters", config.contextParameters);
		return node;
	}

	private static Object newTypeNode(NewTypeConfiguration config) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		putString(node, "name", config.name);
		putString(node, "stringRepresenter", config.stringRepresenter);
		putOrderedMap(node, "data", config.data, SchemaYamlEmitter::identity);
		return node;
	}

	private static Object newSubtypeNode(NewSubtypeConfiguration config) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		putString(node, "superType", config.superType);
		putString(node, "subtype", config.subtype);
		return node;
	}

	private static Object readTransformNode(ReadTransformConfiguration config) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		putString(node, "type", config.type);
		if (config.custom != null) {
			Map<String, Object> custom = new java.util.LinkedHashMap<>();
			putString(custom, "className", config.custom.className);
			putString(custom, "instance", config.custom.instance);
			node.put("custom", custom);
		}
		if (config.constant != null) {
			Map<String, Object> constant = new java.util.LinkedHashMap<>();
			Object value = config.constant.value;
			if (value instanceof Number || value instanceof Boolean) {
				constant.put("value", value);
			} else if (value != null) {
				constant.put("value", String.valueOf(value));
			}
			node.put("constant", constant);
		}
		if (config.identity != null) {
			Map<String, Object> identity = new java.util.LinkedHashMap<>();
			putString(identity, "source", config.identity.source);
			node.put("identity", identity);
		}
		if (config.invokeStatic != null) {
			Map<String, Object> invokeStatic = new java.util.LinkedHashMap<>();
			putString(invokeStatic, "method", config.invokeStatic.method);
			List<ReadTransformConfiguration> arguments = config.invokeStatic.getArguments();
			if (!arguments.isEmpty()) {
				invokeStatic.put("arguments", arguments.stream().map(SchemaYamlEmitter::readTransformNode).toList());
			}
			node.put("invokeStatic", invokeStatic);
		}
		if (config.construct != null) {
			Map<String, Object> construct = new java.util.LinkedHashMap<>();
			putString(construct, "type", config.construct.type);
			putString(construct, "className", config.construct.className);
			putString(construct, "factory", config.construct.factory);
			List<ReadTransformConfiguration> arguments = config.construct.getArguments();
			if (!arguments.isEmpty()) {
				construct.put("arguments", arguments.stream().map(SchemaYamlEmitter::readTransformNode).toList());
			}
			node.put("construct", construct);
		}
		if (config.mapNullable != null) {
			node.put("mapNullable", mapLikeNode(config.mapNullable.source, config.mapNullable.transform));
		}
		if (config.mapArray != null) {
			node.put("mapArray", mapLikeNode(config.mapArray.source, config.mapArray.transform));
		}
		return node;
	}

	private static Map<String, Object> mapLikeNode(ReadTransformConfiguration source, ReadTransformConfiguration transform) {
		Map<String, Object> node = new java.util.LinkedHashMap<>();
		if (source != null) {
			node.put("source", readTransformNode(source));
		}
		if (transform != null) {
			node.put("transform", readTransformNode(transform));
		}
		return node;
	}

	private static void putTransformClass(Map<String, Object> node, TransformationConfiguration config) {
		putString(node, "transformClass", config.getTransformClass());
	}

	/** Marker for a set, whose members are sorted and therefore order-insensitive. */
	record SetNode(List<String> members) {
	}

	// ------------------------------------------------------------------------------------------------
	// normalization helpers
	// ------------------------------------------------------------------------------------------------

	private static Object identity(Object value) {
		return value;
	}

	private static void putString(Map<String, Object> node, String key, String value) {
		if (value != null) {
			node.put(key, value);
		}
	}

	/** An empty optional bean is omitted; a named entry that only exists as a key survives as {@code {}}. */
	private static void putBean(Map<String, Object> node, String key, Object bean) {
		if (bean instanceof Map<?, ?> map) {
			if (!map.isEmpty()) {
				node.put(key, bean);
			}
		} else if (bean != null) {
			node.put(key, bean);
		}
	}

	private static void putStringList(Map<String, Object> node, String key, List<String> values) {
		if (values != null && !values.isEmpty()) {
			node.put(key, List.copyOf(values));
		}
	}

	private static List<String> stringList(Object value) {
		@SuppressWarnings("unchecked")
		List<String> list = (List<String>) value;
		return List.copyOf(list);
	}

	private static void putOrderedMap(Map<String, Object> node, String key, Map<String, ?> value,
			java.util.function.Function<Object, Object> mapper) {
		putOrderedMap(node, key, value, mapper, false);
	}

	/**
	 * Ordered ({@code LinkedHashMap}) map key. With {@code keepEmpty}, a present-but-empty map is emitted as
	 * {@code {}} instead of being omitted: {@code ClassConfiguration.data} and {@code ProjectionConfiguration.fields}
	 * are read directly by {@link it.cavallium.datagen.plugin.ParsedClass}, which leaves {@code data} {@code null}
	 * when the key is absent (ParsedClass.java:32), and {@code DataModel.validateActiveTypeReferences} then
	 * dereferences it (DataModel.java:876).
	 */
	private static void putOrderedMap(Map<String, Object> node, String key, Map<String, ?> value,
			java.util.function.Function<Object, Object> mapper, boolean keepEmpty) {
		if (value == null || (value.isEmpty() && !keepEmpty)) {
			return;
		}
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, ?> entry : value.entrySet()) {
			putEntry(out, entry.getKey(), entry.getValue(), mapper);
		}
		node.put(key, out);
	}

	private static void putSortedMap(Map<String, Object> node, String key, Map<String, ?> value,
			java.util.function.Function<Object, Object> mapper) {
		if (value == null || value.isEmpty()) {
			return;
		}
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, ?> entry : new TreeMap<>(value).entrySet()) {
			putEntry(out, entry.getKey(), entry.getValue(), mapper);
		}
		node.put(key, out);
	}

	private static void putEntry(Map<String, Object> out, String key, Object rawValue,
			java.util.function.Function<Object, Object> mapper) {
		if (key == null) {
			throw new IllegalArgumentException("YAML map keys must not be null");
		}
		if (rawValue == null) {
			// A null value is a key that exists but carries no data, e.g. `versions: {v1: null}`.
			out.put(key, new java.util.LinkedHashMap<String, Object>());
			return;
		}
		Object mapped = mapper.apply(rawValue);
		if (mapped instanceof Map<?, ?> mappedMap && mappedMap.isEmpty()) {
			out.put(key, new java.util.LinkedHashMap<String, Object>());
		} else if (mapped instanceof Collection<?> mappedCollection && mappedCollection.isEmpty()) {
			return;
		} else {
			out.put(key, mapped);
		}
	}

	/**
	 * Like {@link #putSortedMap} but for maps whose values are POJOs: every value is normalized through
	 * {@code mapper}, and an empty result is preserved as an empty node so the key survives.
	 */
	private static <T> void putSortedBeanMap(Map<String, Object> node, String key, Map<String, T> value,
			java.util.function.Function<T, Object> mapper) {
		if (value == null || value.isEmpty()) {
			return;
		}
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, T> entry : new TreeMap<>(value).entrySet()) {
			T rawValue = entry.getValue();
			if (rawValue == null) {
				out.put(entry.getKey(), new java.util.LinkedHashMap<String, Object>());
			} else {
				Object mapped = mapper.apply(rawValue);
				if (mapped instanceof Collection<?> mappedCollection && mappedCollection.isEmpty()) {
					continue;
				}
				out.put(entry.getKey(), mapped);
			}
		}
		node.put(key, out);
	}

	private static List<String> sorted(Collection<String> values) {
		return values.stream().filter(Objects::nonNull).sorted().toList();
	}

	// ------------------------------------------------------------------------------------------------
	// YAML rendering
	// ------------------------------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private static Map<String, Object> toStringMap(Map<?, ?> map) {
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			out.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return out;
	}

	private static void emitBlockMap(Map<String, Object> map, String lead, String pad, int indent, StringBuilder out) {
		boolean first = true;
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			out.append(first ? lead : pad).append(scalarText(entry.getKey())).append(':');
			first = false;
			appendValue(entry.getValue(), indent, out);
		}
	}

	private static void appendValue(Object value, int indent, StringBuilder out) {
		if (isInline(value)) {
			out.append(' ').append(inline(value)).append('\n');
		} else if (value instanceof Map<?, ?> map) {
			out.append('\n');
			String nested = " ".repeat(indent + 2);
			emitBlockMap(toStringMap(map), nested, nested, indent + 2, out);
		} else if (value instanceof List<?> list) {
			out.append('\n');
			emitBlockList(list, indent, out);
		} else if (value instanceof SetNode set) {
			out.append('\n');
			emitBlockList(set.members(), indent, out);
		} else {
			out.append(' ').append(scalarText(value)).append('\n');
		}
	}

	private static void emitBlockList(List<?> list, int indent, StringBuilder out) {
		String dash = " ".repeat(indent) + "-";
		String pad = " ".repeat(indent + 2);
		for (Object element : list) {
			if (isInline(element)) {
				out.append(dash).append(' ').append(inline(element)).append('\n');
			} else if (element instanceof Map<?, ?> map) {
				emitBlockMap(toStringMap(map), dash + " ", pad, indent + 2, out);
			} else if (element instanceof List<?> nested) {
				out.append(dash).append('\n');
				emitBlockList(nested, indent + 2, out);
			} else {
				out.append(dash).append(' ').append(scalarText(element)).append('\n');
			}
		}
	}

	private static boolean isInline(Object value) {
		if (value instanceof SetNode set) {
			return true;
		}
		if (value instanceof Map<?, ?> map) {
			return map.values().stream().allMatch(SchemaYamlEmitter::isScalar);
		}
		if (value instanceof List<?> list) {
			return list.stream().allMatch(SchemaYamlEmitter::isScalar);
		}
		return isScalar(value);
	}

	private static boolean isScalar(Object value) {
		return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
	}

	private static String inline(Object value) {
		if (value instanceof SetNode set) {
			return flow(set.members().stream().map(SchemaYamlEmitter::scalarText).toList());
		}
		if (value instanceof Map<?, ?> map) {
			if (map.isEmpty()) {
				return "{}";
			}
			StringBuilder builder = new StringBuilder("{");
			boolean first = true;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (!first) {
					builder.append(", ");
				}
				first = false;
				builder.append(scalarText(String.valueOf(entry.getKey()))).append(": ")
						.append(scalarText(entry.getValue()));
			}
			return builder.append('}').toString();
		}
		if (value instanceof List<?> list) {
			if (list.isEmpty()) {
				return "[]";
			}
			return flow(list.stream().map(SchemaYamlEmitter::scalarText).toList());
		}
		return scalarText(value);
	}

	private static String flow(List<String> parts) {
		return "[" + String.join(", ", parts) + "]";
	}

	private static String scalarText(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof String string) {
			return quote(string);
		}
		if (value instanceof Number || value instanceof Boolean) {
			return String.valueOf(value);
		}
		return quote(String.valueOf(value));
	}

	private static final String INDICATORS = "-?:,[]{}#&*!|>'\"%@`";

	private static String quote(String value) {
		if (!needsQuotes(value)) {
			return value;
		}
		boolean control = false;
		for (int index = 0; index < value.length(); index++) {
			char c = value.charAt(index);
			if (c < 0x20 || c == 0x7f) {
				control = true;
				break;
			}
		}
		if (control) {
			StringBuilder out = new StringBuilder(value.length() + 8).append('"');
			for (int index = 0; index < value.length(); index++) {
				char c = value.charAt(index);
				switch (c) {
					case '"' -> out.append("\\\"");
					case '\\' -> out.append("\\\\");
					case '\n' -> out.append("\\n");
					case '\r' -> out.append("\\r");
					case '\t' -> out.append("\\t");
					default -> {
						if (c < 0x20 || c == 0x7f) {
							out.append(String.format("\\u%04x", (int) c));
						} else {
							out.append(c);
						}
					}
				}
			}
			return out.append('"').toString();
		}
		return "'" + value.replace("'", "''") + "'";
	}

	private static boolean needsQuotes(String value) {
		if (value.isEmpty()) {
			return true;
		}
		if (Character.isWhitespace(value.charAt(0)) || Character.isWhitespace(value.charAt(value.length() - 1))) {
			return true;
		}
		if (INDICATORS.indexOf(value.charAt(0)) >= 0) {
			return true;
		}
		for (int index = 0; index < value.length(); index++) {
			char c = value.charAt(index);
			if (c < 0x20 || c == 0x7f) {
				return true;
			}
			if (":#{},[]".indexOf(c) >= 0) {
				return true;
			}
			if (c == ' ' && index + 1 < value.length() && value.charAt(index + 1) == '#') {
				return true;
			}
		}
		if (value.contains(": ") || value.endsWith(":")) {
			return true;
		}
		String lower = value.toLowerCase(java.util.Locale.ROOT);
		switch (lower) {
			case "y", "yes", "true", "on", "n", "no", "false", "off", "null", "~", "*.inf", "-.inf", ".nan" -> {
				return true;
			}
			default -> {
			}
		}
		if (lower.equals(".inf") || lower.equals("-.inf") || lower.equals(".nan")) {
			return true;
		}
		return isNumericLike(value);
	}

	private static boolean isNumericLike(String value) {
		String body = value.charAt(0) == '-' || value.charAt(0) == '+' ? value.substring(1) : value;
		if (body.isEmpty()) {
			return false;
		}
		if (body.startsWith("0x") || body.startsWith("0X") || body.startsWith("0o") || body.startsWith("0b")) {
			return body.length() > 2;
		}
		boolean digits = true;
		boolean dot = false;
		boolean exponent = false;
		for (int index = 0; index < body.length(); index++) {
			char c = body.charAt(index);
			if (c >= '0' && c <= '9') {
				continue;
			}
			if (c == '_' || c == ':') {
				continue;
			}
			if (c == '.') {
				if (dot || exponent) {
					return false;
				}
				dot = true;
				continue;
			}
			if (c == 'e' || c == 'E') {
				if (exponent || index == 0) {
					return false;
				}
				exponent = true;
				if (index + 1 < body.length() && (body.charAt(index + 1) == '+' || body.charAt(index + 1) == '-')) {
					index++;
				}
				continue;
			}
			digits = false;
			break;
		}
		return digits;
	}
}
