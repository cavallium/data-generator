package it.cavallium.data.generator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;

public class CustomTypesConfiguration {

	private String javaClass;
	public String serializer;

	public void setJavaClass(String javaClass) {
		this.javaClass = javaClass;
	}

	public TypeName getJavaClassType() {
		int indexOfGeneric;
		if ((indexOfGeneric = javaClass.indexOf("<")) == -1) {
			return ClassName.bestGuess(javaClass);
		} else {
			var rawTypesArray = javaClass.substring(indexOfGeneric + 1, javaClass.length() - 1).split(",");
			var genericsResult = new TypeName[rawTypesArray.length];
			int i = 0;
			for (String rawType : rawTypesArray) {
				genericsResult[i] = ClassName.bestGuess(rawType);
				i++;
			}
			var base = ClassName.bestGuess(javaClass.substring(0, indexOfGeneric));
			return ParameterizedTypeName.get(base, genericsResult);
		}
	}
}
