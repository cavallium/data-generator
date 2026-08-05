package it.cavallium.datagen.plugin;

import static java.lang.Boolean.parseBoolean;

import java.io.IOException;
import java.nio.file.Paths;

public class Standalone {

	public static void main(String[] args) throws IOException {
		if (args.length != 7) {
			System.err.println("[PATH] [BASE PACKAGE NAME] [OUT PATH] [FORCE] [GENERATE OLD SERIALIZERS] [BINARY STRINGS] [VECTOR KERNELS]");
			System.exit(1);
			return;
		}
		SourcesGenerator sourcesGenerator = SourcesGenerator.load(Paths.get(args[0]));
		sourcesGenerator.generateSources(args[1],
				Paths.get(args[2]),
				parseBoolean(args[3]),
				parseBoolean(args[4]),
				parseBoolean(args[5]),
				parseBoolean(args[6])
		);
	}
}
