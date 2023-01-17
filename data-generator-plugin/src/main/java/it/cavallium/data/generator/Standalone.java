package it.cavallium.data.generator;

import java.io.IOException;
import java.nio.file.Paths;

public class Standalone {

	public static void main(String[] args) throws IOException {
		SourcesGenerator sourcesGenerator = SourcesGenerator.load(Paths.get(args[0]));
		sourcesGenerator.generateSources(args[1], Paths.get(args[2]), Boolean.parseBoolean(args[3]));
	}
}
