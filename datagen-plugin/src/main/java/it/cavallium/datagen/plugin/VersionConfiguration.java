package it.cavallium.datagen.plugin;

import java.util.List;
import java.util.Map;
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
    public Map<String, Integer> typeVersions;
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
    public Map<String, List<String>> dependentTypes;

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
                && Objects.equals(transformations, that.transformations)
                && Objects.equals(typeVersions, that.typeVersions)
                && Objects.equals(dependentTypes, that.dependentTypes);
    }

    @Override
    public int hashCode() {
        int hash = 0;
        hash += ConfigUtils.hashCode(previousVersion);
        hash += ConfigUtils.hashCode(details);
        hash += ConfigUtils.hashCode(transformations);
        hash += ConfigUtils.hashCode(typeVersions);
        hash += ConfigUtils.hashCode(dependentTypes);
        return hash;
    }
}
