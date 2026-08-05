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

    @Parameter(required = true)
    private File configPath;

    @Parameter(required = true)
    private String basePackageName;

    @Parameter(required = true, defaultValue = "false")
    private boolean generateOldSerializers;

    @Parameter(defaultValue = "false")
    private boolean generateTestResources;

    @Parameter(defaultValue = "false")
    private boolean binaryStrings;

    @Parameter(defaultValue = "false")
    private boolean vectorKernels;

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
            boolean testResources = generateTestResources;
            Path genRecordsPath = project.getBasedir().getAbsoluteFile().toPath().resolve("target").resolve(testResources ? "generated-test-sources" : "generated-sources").resolve("database-classes");

            Path outPath = genRecordsPath.resolve("java");
            if (testResources) {
                this.project.addTestCompileSourceRoot(outPath.toString());
            } else {
                this.project.addCompileSourceRoot(outPath.toString());
            }
            sourcesGenerator.generateSources(basePackageName, outPath, false,
                    generateOldSerializers, binaryStrings, vectorKernels);
        } catch (IOException e) {
            throw new MojoExecutionException("Exception while generating classes", e);
        }
        getLog().info("Classes generated.");
    }
}
