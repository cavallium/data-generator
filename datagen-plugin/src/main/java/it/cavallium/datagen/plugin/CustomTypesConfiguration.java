package it.cavallium.datagen.plugin;

import java.util.Objects;

public final class CustomTypesConfiguration {

	private String javaClass;
	/** The single {@code DataCodec} responsible for serialization, reading, and skipping. */
	public String codec;
	/** Optional exact serialized width. Variable-width codecs leave this unset. */
	public Integer fixedSize;

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
		return Objects.equals(javaClass, that.javaClass) && Objects.equals(codec, that.codec)
				&& Objects.equals(fixedSize, that.fixedSize);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(javaClass);
		hash += ConfigUtils.hashCode(codec);
		hash += ConfigUtils.hashCode(fixedSize);
		return hash;
	}

	public CustomTypesConfiguration copy() {
		var c = new CustomTypesConfiguration();
		c.javaClass = this.javaClass;
		c.codec = this.codec;
		c.fixedSize = this.fixedSize;
		return c;
	}
}
