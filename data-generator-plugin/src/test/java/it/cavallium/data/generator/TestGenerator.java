package it.cavallium.data.generator;

import java.io.IOException;
import java.nio.file.Files;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

public class TestGenerator {

	//@Test
	public void test() throws IOException {
		var dir = Files.createTempDirectory("data-generator-test");
		try {
			SourcesGenerator.load(this.getClass().getResourceAsStream("/test.yaml")).generateSources("it.test", dir, false);
		} finally {
			try {
				FileUtils.deleteDirectory(dir.toFile());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
