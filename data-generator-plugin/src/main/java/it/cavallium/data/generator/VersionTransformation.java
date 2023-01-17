package it.cavallium.data.generator;

import java.util.Objects;

public class VersionTransformation {

	public MoveDataConfiguration moveData = null;
	public RemoveDataConfiguration removeData = null;
	public UpgradeDataConfiguration upgradeData = null;
	public NewDataConfiguration newData = null;

	void checkConsistency() {
		int nonNullValues = 0;
		if (moveData != null) {
			nonNullValues++;
		}
		if (removeData != null) {
			nonNullValues++;
		}
		if (upgradeData != null) {
			nonNullValues++;
		}
		if (newData != null) {
			nonNullValues++;
		}
		if (nonNullValues != 1) {
			throw new IllegalArgumentException("Please fill only one transformation!");
		}
	}

	public boolean isForClass(String type) {
		checkConsistency();
		if (moveData != null) {
			return moveData.transformClass.equals(type);
		}
		if (removeData != null) {
			return removeData.transformClass.equals(type);
		}
		if (upgradeData != null) {
			return upgradeData.transformClass.equals(type);
		}
		if (newData != null) {
			return newData.transformClass.equals(type);
		}
		throw new IllegalStateException();
	}

	public TransformationConfiguration getTransformation() {
		checkConsistency();
		if (moveData != null) {
			return moveData;
		}
		if (removeData != null) {
			return removeData;
		}
		if (upgradeData != null) {
			return upgradeData;
		}
		if (newData != null) {
			return newData;
		}
		throw new IllegalStateException();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		VersionTransformation that = (VersionTransformation) o;
		return Objects.equals(moveData, that.moveData) && Objects.equals(removeData, that.removeData) && Objects.equals(
				upgradeData,
				that.upgradeData
		) && Objects.equals(newData, that.newData);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(moveData);
		hash += ConfigUtils.hashCode(removeData);
		hash += ConfigUtils.hashCode(upgradeData);
		hash += ConfigUtils.hashCode(newData);
		return hash;
	}
}
