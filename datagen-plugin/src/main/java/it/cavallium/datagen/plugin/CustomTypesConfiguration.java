package it.cavallium.datagen.plugin;

import java.util.Objects;

public final class CustomTypesConfiguration {

	private String javaClass;
	public String serializer;
	/** Optional {@code DataSkipper} implementation used when projections cross this type. */
	public String skipper;

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
		return Objects.equals(javaClass, that.javaClass) && Objects.equals(serializer, that.serializer)
				&& Objects.equals(skipper, that.skipper);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(javaClass);
		hash += ConfigUtils.hashCode(serializer);
		hash += ConfigUtils.hashCode(skipper);
		return hash;
	}

	public CustomTypesConfiguration copy() {
		var c = new CustomTypesConfiguration();
		c.javaClass = this.javaClass;
		c.serializer = this.serializer;
		c.skipper = this.skipper;
		return c;
	}
}
