package it.cavallium.data.generator.plugin;

public record VersionedType(String type, int version) {

	public VersionedType withVersion(int version) {
		if (version == this.version) {
			return this;
		}
		return new VersionedType(type, version);
	}

	public VersionedType withVersionIfChanged(int version, VersionChangeChecker versionChangeChecker) {
		if (versionChangeChecker.checkChanged(this.type)) {
			return withVersion(version);
		}
		return this;
	}
}
