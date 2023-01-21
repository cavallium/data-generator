package it.cavallium.data.generator.plugin;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ClassGenerator {

	private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final HashSet<Path> generatedFilesToDelete;
	protected final DataModel dataModel;
	protected final String basePackageName;
	private final Path outPath;
	protected final boolean deepCheckBeforeCreatingNewEqualInstances;
	protected final boolean useRecordBuilders;

	public ClassGenerator(ClassGeneratorParams params) {
		this.generatedFilesToDelete = params.generatedFilesToDelete;
		this.dataModel = params.dataModel;
		this.basePackageName = params.basePackageName;
		this.outPath = params.outPath;
		this.deepCheckBeforeCreatingNewEqualInstances = params.deepCheckBeforeCreatingNewEqualInstances;
		this.useRecordBuilders = params.useRecordBuilders;
	}

	public void run() throws IOException {
		for (GeneratedClass generatedClass : generateClasses().toList()) {
			writeClass(generatedClass.packageName, generatedClass.content);
		}
	}

	private void writeClass(String classPackage, Builder versionsClass) throws IOException {
		var sb = new StringBuilder();
		var typeSpec = versionsClass.build();
		var outJavaFile = outPath;
		for (String part : classPackage.split("\\.")) {
			outJavaFile = outJavaFile.resolve(part);
		}
		if (Files.notExists(outJavaFile)) {
			Files.createDirectories(outJavaFile);
		}
		outJavaFile = outJavaFile.resolve(typeSpec.name + ".java");
		JavaFile.builder(classPackage, typeSpec).build().writeTo(sb);
		String newFile = sb.toString();
		boolean mustWrite;
		if (Files.isRegularFile(outJavaFile) && Files.isReadable(outJavaFile)) {
			String oldFile = Files.readString(outJavaFile, StandardCharsets.UTF_8);
			mustWrite = !oldFile.equals(newFile);
		} else {
			mustWrite = true;
		}
		if (mustWrite) {
			logger.debug("File {} changed", outJavaFile);
			Files.writeString(outJavaFile, newFile, StandardCharsets.UTF_8, TRUNCATE_EXISTING, CREATE, WRITE);
		} else {
			logger.debug("File {} is the same, unchanged", outJavaFile);
		}
		markFileAsCreated(generatedFilesToDelete, outPath, outJavaFile);
	}

	private void markFileAsCreated(Set<Path> generatedFilesToDelete, Path basePath, Path filePath) {
		generatedFilesToDelete.remove(basePath.relativize(filePath));
	}

	protected abstract Stream<GeneratedClass> generateClasses();

	public record GeneratedClass(String packageName, TypeSpec.Builder content) {}

	public record ClassGeneratorParams(HashSet<Path> generatedFilesToDelete,
																		 DataModel dataModel,
																		 String basePackageName,
																		 Path outPath,
																		 boolean deepCheckBeforeCreatingNewEqualInstances,
																		 boolean useRecordBuilders) {}
}
