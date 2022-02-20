package it.cavallium.data.generator;

public class UpgradeDataConfiguration implements TransformationConfiguration {

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
