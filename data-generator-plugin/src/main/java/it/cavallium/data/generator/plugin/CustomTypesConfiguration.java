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
