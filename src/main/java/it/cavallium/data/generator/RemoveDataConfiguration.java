package it.cavallium.data.generator;

public class RemoveDataConfiguration implements TransformationConfiguration {

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
