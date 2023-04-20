package it.cavallium.datagen.plugin;

import java.util.Set;

public class VersionChangeChecker {

	private final Set<String> changedTypes;
	private final int version;
	private final int latestVersion;

	public VersionChangeChecker(Set<String> changedTypes, int version, int latestVersion) {
		this.changedTypes = changedTypes;
		this.version = version;
		this.latestVersion = latestVersion;
	}

	public boolean checkChanged(String name) {
		return changedTypes.contains(name);
	}

	public int getVersion() {
		return version;
	}

	public int getLatestVersion() {
		return latestVersion;
	}
}
