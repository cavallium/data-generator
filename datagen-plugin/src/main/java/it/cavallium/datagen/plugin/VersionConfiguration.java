package it.cavallium.datagen.plugin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import java.util.List;
import java.util.Objects;

public class VersionConfiguration {

	public String previousVersion;
	public DetailsConfiguration details;
	public List<VersionTransformation> transformations;
	/**
	 * <pre>
	 * Type 1: v1
	 * Type 2: v4
	 * Type 3: v2
	 * ...
	 * </pre>
	 */
	public Object2IntMap<String> typeVersions;
	/**
	 * <pre>
	 * - Type 1
	 *   |_Dependent type 1
	 *   |_Dependent type 2
	 *   |_Dependent type ...
	 *
	 * - Type 2
	 *   |_Dependent type 1
	 *   |_Dependent type 2
	 *   |_Dependent type ...
	 * </pre>
	 */
	public Object2ObjectMap<String, List<String>> dependentTypes;

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		VersionConfiguration that = (VersionConfiguration) o;
		return Objects.equals(previousVersion, that.previousVersion) && Objects.equals(details, that.details)
				&& Objects.equals(transformations, that.transformations);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(previousVersion);
		hash += ConfigUtils.hashCode(details);
		hash += ConfigUtils.hashCode(transformations);
		return hash;
	}
}
