package it.cavallium.data.generator;

public class MoveDataConfiguration implements TransformationConfiguration {

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
