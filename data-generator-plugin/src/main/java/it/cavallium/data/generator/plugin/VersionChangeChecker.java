package it.cavallium.data.generator.plugin;

import java.util.Set;

public class VersionChangeChecker {

	private final Set<String> changedTypes;

	public VersionChangeChecker(Set<String> changedTypes) {
		this.changedTypes = changedTypes;
	}

	public boolean checkChanged(String name) {
		return changedTypes.contains(name);
	}
}
