package it.cavallium.data.generator;

public class NewDataConfiguration implements TransformationConfiguration {

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
