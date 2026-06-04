package dev.affogato.gradle;

import dev.affogato.compiler.AffogatoCompilationResult;
import dev.affogato.compiler.AffogatoCompiler;
import dev.affogato.compiler.AffogatoCompilerOptions;
import dev.affogato.compiler.AffogatoDiagnostic;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.nio.file.Path;

@CacheableTask
public abstract class AffogatoCompile extends DefaultTask {
    private final ConfigurableFileCollection sourceDirs = getProject().files();
    private final ConfigurableFileCollection classpath = getProject().files();
    private final DirectoryProperty outputDirectory = getProject().getObjects().directoryProperty();
    private final Property<Boolean> failOnWarnings = getProject().getObjects().property(Boolean.class).convention(false);
    private final Property<Integer> javaRelease = getProject().getObjects().property(Integer.class).convention(21);

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public ConfigurableFileCollection getSourceDirs() {
        return sourceDirs;
    }

    @Classpath
    public ConfigurableFileCollection getClasspath() {
        return classpath;
    }

    @OutputDirectory
    public DirectoryProperty getOutputDirectory() {
        return outputDirectory;
    }

    @Input
    public Property<Boolean> getFailOnWarnings() {
        return failOnWarnings;
    }

    @Input
    public Property<Integer> getJavaRelease() {
        return javaRelease;
    }

    @TaskAction
    public void compile() {
        AffogatoCompilerOptions.Builder options = AffogatoCompilerOptions.builder()
                .outputDirectory(outputDirectory.get().getAsFile().toPath())
                .failOnWarnings(failOnWarnings.get())
                .javaRelease(javaRelease.get());

        for (var sourceDir : sourceDirs.getFiles()) {
            options.addSourceRoot(sourceDir.toPath());
        }
        for (var classpathEntry : classpath.getFiles()) {
            options.addClasspathEntry(classpathEntry.toPath());
        }

        AffogatoCompilationResult result = new AffogatoCompiler().compile(options.build());
        for (AffogatoDiagnostic diagnostic : result.diagnostics()) {
            if (diagnostic.severity() == AffogatoDiagnostic.Severity.ERROR) {
                getLogger().error(diagnostic.toString());
            } else if (diagnostic.severity() == AffogatoDiagnostic.Severity.WARNING) {
                getLogger().warn(diagnostic.toString());
            } else {
                getLogger().info(diagnostic.toString());
            }
        }
        for (Path generatedFile : result.generatedFiles()) {
            getLogger().debug("Generated {}", generatedFile);
        }
    }
}
