package it.cavallium.datagen.plugin;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Deterministic allocator for generated private fields, parameters, and locals. */
public final class GeneratedNameAllocator {

	private final Set<String> used = new LinkedHashSet<>();

	public GeneratedNameAllocator(Collection<String> schemaNames, Collection<String> fixedGeneratedNames) {
		used.addAll(Objects.requireNonNull(schemaNames, "schemaNames"));
		used.addAll(Objects.requireNonNull(fixedGeneratedNames, "fixedGeneratedNames"));
	}

	public String allocate(String hint) {
		Objects.requireNonNull(hint, "hint");
		String base = "$datagen$" + hint;
		String candidate = base;
		int suffix = 0;
		while (!used.add(candidate)) {
			candidate = base + "$" + ++suffix;
		}
		return candidate;
	}
}
