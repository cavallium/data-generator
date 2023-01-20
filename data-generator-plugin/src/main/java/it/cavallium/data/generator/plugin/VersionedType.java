package it.cavallium.data.generator.plugin;

import java.util.Objects;

public record VersionedType(String type, ComputedVersion version) {

	public VersionedType withVersion(ComputedVersion version) {
		if (Objects.equals(version, this.version)) {
			return this;
		}
		return new VersionedType(type, version);
	}

	public VersionedType withVersionIfChanged(ComputedVersion version, VersionChangeChecker versionChangeChecker) {
		if (versionChangeChecker.checkChanged(this.type)) {
			return withVersion(version);
		}
		return this;
	}
}
