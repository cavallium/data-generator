package it.cavallium.datagen.plugin;

import com.squareup.javapoet.CodeBlock;
import java.util.List;
import java.util.Objects;

public class SerializeCodeBlockGenerator {

	private final CodeBlock before;
	private final CodeBlock after;

	public SerializeCodeBlockGenerator(CodeBlock before, CodeBlock after) {
		this.before = before;
		this.after = after;
	}

	public CodeBlock generate(String method) {
		return generate(CodeBlock.builder().add(method).build());
	}

	public CodeBlock generate(CodeBlock method) {
		return CodeBlock.join(List.of(before, method, after), "");
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		SerializeCodeBlockGenerator that = (SerializeCodeBlockGenerator) o;
		return Objects.equals(before, that.before) && Objects.equals(after, that.after);
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash += ConfigUtils.hashCode(before);
		hash += ConfigUtils.hashCode(after);
		return hash;
	}


	@Override
	public String toString() {
		return CodeBlock.builder().add("$[").add(generate(CodeBlock.builder().add("test.get()").build())).add(";\n$]").build().toString();
	}
}
