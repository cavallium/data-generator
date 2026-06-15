package it.cavallium.datagen.plugin;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;

class ConfigUtils {

	static int hashCode(Map<?, ?> map) {
		if (map == null) return 0;
		int hash = 1;
		var entries = map.entrySet().stream();
		if (!(map instanceof LinkedHashMap<?, ?>) && !(map instanceof SortedMap<?, ?>)) {
			entries = entries.sorted(Comparator.comparing(e -> String.valueOf(e.getKey())));
		}
		for (var e : entries.toList()) {
			hash = 31 * hash + ConfigUtils.hashCode(e.getKey());
			hash = 31 * hash + ConfigUtils.hashCode(e.getValue());
		}
        return hash;
    }

    static int hashCode(Collection<?> collection) {
        if (collection == null) return 0;
        if (collection instanceof Set<?>) {
            return collection.stream().map(ConfigUtils::hashCode).reduce(0, Integer::sum);
        }
        int hash = 1;
        for (var value : collection) {
            hash = 31 * hash + ConfigUtils.hashCode(value);
        }
        return hash;
    }

    static int hashCode(Object collection) {
        return Objects.hashCode(collection);
    }
}
