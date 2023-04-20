package it.cavallium.datagen.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

@Mojo(name = "run", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class MavenPlugin extends AbstractMojo {

	@Parameter( required = true)
	private File configPath;

	@Parameter( required = true)
	private String basePackageName;

	@Parameter( required = true, defaultValue = "true")
	private String deepCheckBeforeCreatingNewEqualInstances;

	@Parameter( required = true, defaultValue = "false")
	private String generateOldSerializers;

	@Parameter( required = true, defaultValue = "false")
	private String useRecordBuilder;

	@Parameter(defaultValue = "false")
	private String generateTestResources;

	/**
	 * @parameter default-value="${project}"
	 * @required
	 * @readonly
	 */
	@Parameter(defaultValue = "${project}", required = true, readonly = false)
	MavenProject project;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		try {
			SourcesGenerator sourcesGenerator = SourcesGenerator.load(configPath.toPath());
			project.hasLifecyclePhase("generate-test-sources");
			Path genRecordsPath = project.getBasedir().getAbsoluteFile().toPath().resolve("target").resolve(Boolean.parseBoolean(generateTestResources) ? "generated-test-sources" : "generated-sources").resolve("database-classes");

			Path outPath = genRecordsPath.resolve("java");
			this.project.addCompileSourceRoot(outPath.toString());
			sourcesGenerator.generateSources(basePackageName, outPath, Boolean.parseBoolean(useRecordBuilder), false, Boolean.parseBoolean(deepCheckBeforeCreatingNewEqualInstances),
					Boolean.parseBoolean(generateOldSerializers));
		} catch (IOException e) {
			throw new MojoExecutionException("Exception while generating classes", e);
		}
		getLog().info("Classes generated.");
	}
}
