package it.cavallium.data.generator;

public class CommonField {
	public final String fieldName;
	public final String fieldType;
	public final boolean hasSetter;

	public CommonField(String fieldName, String fieldType, boolean hasSetter) {
		this.fieldName = fieldName;
		this.fieldType = fieldType;
		this.hasSetter = hasSetter;
	}
}
