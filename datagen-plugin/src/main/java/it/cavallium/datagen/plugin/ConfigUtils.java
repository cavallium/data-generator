package it.cavallium.datagen.plugin;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

class ConfigUtils {

	static int hashCode(Map<?, ?> map) {
		return map == null ? 0 : map.hashCode();
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
