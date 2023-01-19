package it.cavallium.data.generator.plugin;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import java.util.Objects;

public final class CustomTypesConfiguration {

	private String javaClass;
	public String serializer;

	public void setJavaClass(String javaClass) {
		this.javaClass = javaClass;
	}

	public TypeName getJavaClassType() {
		int indexOfGeneric;
		if ((indexOfGeneric = javaClass.indexOf("<")) == -1) {
			return ClassName.bestGuess(javaClass.trim());
		} else {
			var rawTypesArray = javaClass.substring(indexOfGeneric + 1, javaClass.length() - 1).split(",");
			var genericsResult = new TypeName[rawTypesArray.length];
			int i = 0;
			for (String rawType : rawTypesArray) {
				genericsResult[i] = ClassName.bestGuess(rawType.trim());
				i++;
			}
			var base = ClassName.bestGuess(javaClass.substring(0, indexOfGeneric).trim());
			return ParameterizedTypeName.get(base, genericsResult);
		}
	}

	public String getJavaClassString() {
		return javaClass;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		CustomTypesConfiguration that = (CustomTypesConfiguration) o;
		return Objects.equals(javaClass, that.javaClass) && Objects.equals(serializer, that.serializer);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(javaClass);
		hash += ConfigUtils.hashCode(serializer);
		return hash;
	}

	@SuppressWarnings("MethodDoesntCallSuperMethod")
	@Override
	public CustomTypesConfiguration clone() {
		var c = new CustomTypesConfiguration();
		c.javaClass = this.javaClass;
		c.serializer = this.serializer;
		return c;
	}
}
