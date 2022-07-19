package it.cavallium.data.generator;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

class ConfigUtils {

	static int hashCode(Map<?, ?> map) {
		if (map == null) return 0;
		return map
				.entrySet()
				.stream()
				.map(e -> ConfigUtils.hashCode(e.getKey()) + ConfigUtils.hashCode(e.getValue()))
				.reduce(0, Integer::sum);
	}

	static int hashCode(Collection<?> collection) {
		if (collection == null) return 0;
		return collection.stream().map(ConfigUtils::hashCode).reduce(0, Integer::sum);
	}

	static int hashCode(Object collection) {
		return Objects.hashCode(collection);
	}
}
