package it.cavallium.datagen.plugin.fuzz.support;

import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Stable canonical text form of a schema configuration, used as a fingerprint input and as the round-trip
 * oracle for {@link SchemaYamlEmitter}.
 *
 * <p>It renders the same normalized tree the emitter uses, so it inherits the order rules (sorted map keys,
 * ordered {@code data} / {@code fields} / lists / sorted sets) and, in addition, normalizes away the
 * distinctions the YAML text cannot carry:</p>
 * <ul>
 *   <li>{@code null} versus an empty map, list or set;</li>
 *   <li>a missing {@code consumeToEnd} flag versus {@code consumeToEnd: true} (already collapsed by the tree);</li>
 *   <li>scalar type ambiguity, by tagging every scalar ({@code s:} string, {@code n:} number, {@code b:} bool).</li>
 * </ul>
 * Anchors, aliases and merge keys from a source document are legitimately expanded by the loader, which is why
 * callers compare canonical text and never raw fixture bytes.
 */
public final class SchemaCanonicalizer {

	private SchemaCanonicalizer() {
	}

	public static String canonical(SourcesGeneratorConfiguration config) {
		Objects.requireNonNull(config, "config");
		StringBuilder builder = new StringBuilder();
		write(SchemaYamlEmitter.node(config), 0, builder);
		return builder.toString();
	}

	public static String canonicalYaml(String yaml) {
		return canonical(SchemaYamlEmitter.load(yaml));
	}

	private static void write(Object node, int depth, StringBuilder out) {
		if (node instanceof Map<?, ?> map) {
			if (map.isEmpty()) {
				indent(depth, out).append("<map-empty>\n");
				return;
			}
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				indent(depth, out).append(String.valueOf(entry.getKey())).append(':');
				Object value = entry.getValue();
				if (isScalar(value)) {
					out.append(' ').append(scalar(value)).append('\n');
				} else {
					out.append('\n');
					write(value, depth + 1, out);
				}
			}
		} else if (node instanceof SchemaYamlEmitter.SetNode set) {
			if (set.members().isEmpty()) {
				indent(depth, out).append("<set-empty>\n");
			} else {
				for (String member : set.members()) {
					indent(depth, out).append("- ").append(scalar(member)).append('\n');
				}
			}
		} else if (node instanceof Collection<?> collection) {
			if (collection.isEmpty()) {
				indent(depth, out).append("<list-empty>\n");
				return;
			}
			int index = 0;
			for (Object element : collection) {
				indent(depth, out).append('[').append(index++).append("]:");
				if (isScalar(element)) {
					out.append(' ').append(scalar(element)).append('\n');
				} else {
					out.append('\n');
					write(element, depth + 1, out);
				}
			}
		} else {
			indent(depth, out).append(scalar(node)).append('\n');
		}
	}

	private static boolean isScalar(Object value) {
		return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
	}

	private static String scalar(Object value) {
		if (value == null) {
			return "z:<null>";
		}
		if (value instanceof String string) {
			return "s:" + string;
		}
		if (value instanceof Boolean bool) {
			return "b:" + bool;
		}
		if (value instanceof Number number) {
			// 1 and 1.0 must not collide, and neither must an integer-looking string.
			return "n:" + number.getClass().getSimpleName() + '(' + number + ')';
		}
		return "u:" + value.getClass().getName() + '(' + value + ')';
	}

	private static StringBuilder indent(int depth, StringBuilder out) {
		return out.append("  ".repeat(depth));
	}
}
