package it.cavallium.data.generator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SourcesGeneratorConfiguration {
	public String currentVersion;
	public Map<String, InterfaceDataConfiguration> interfacesData;
	public Map<String, VersionConfiguration> versions;

	public static class InterfaceDataConfiguration {
		public Set<String> extendInterfaces = new HashSet<>();
		public Map<String, String> commonData = new HashMap<>();
		public Map<String, String> commonGetters = new HashMap<>();
	}

	public static class VersionConfiguration {
		public DetailsConfiguration details;
		public Map<String, Set<String>> superTypes;
		public Map<String, CustomTypesConfiguration> customTypes;
		public Map<String, ClassConfiguration> classes;
		public List<VersionTransformation> transformations;
	}


	public static class DetailsConfiguration {
		public String changelog;
	}

	public static class ClassConfiguration {
		public String stringRepresenter;

		public LinkedHashMap<String, String> data;

		public String getStringRepresenter() {
			return stringRepresenter;
		}

		public LinkedHashMap<String, String> getData() {
			return data;
		}
	}

	public static class VersionTransformation {
		public MoveDataConfiguration moveData = null;
		public RemoveDataConfiguration removeData = null;
		public UpgradeDataConfiguration upgradeData = null;
		public NewDataConfiguration newData = null;

		void checkConsistency() {
			int nonNullValues = 0;
			if (moveData != null) nonNullValues++;
			if (removeData != null) nonNullValues++;
			if (upgradeData != null) nonNullValues++;
			if (newData != null) nonNullValues++;
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
	}


	public interface TransformationConfiguration {
		String getTransformClass();

		String getTransformName();
	}

	public static class MoveDataConfiguration implements TransformationConfiguration {

		public String transformClass;
		public String from;
		public String to;

		@Override
		public String getTransformClass() {
			return transformClass;
		}

		@Override
		public String getTransformName() {
			return "move-data";
		}
	}

	public static class RemoveDataConfiguration implements TransformationConfiguration {

		public String transformClass;
		public String from;

		@Override
		public String getTransformClass() {
			return transformClass;
		}

		@Override
		public String getTransformName() {
			return "remove-data";
		}
	}

	public static class UpgradeDataConfiguration implements TransformationConfiguration {

		public String transformClass;
		public String from;
		public String upgrader;

		@Override
		public String getTransformClass() {
			return transformClass;
		}

		@Override
		public String getTransformName() {
			return "upgrade-data";
		}
	}

	public static class NewDataConfiguration implements TransformationConfiguration {

		public String transformClass;
		public String to;
		public String initializer;

		@Override
		public String getTransformClass() {
			return transformClass;
		}

		@Override
		public String getTransformName() {
			return "new-data";
		}
	}

	public static class CustomTypesConfiguration {
		public String javaClass;
		public String serializer;
	}
}
