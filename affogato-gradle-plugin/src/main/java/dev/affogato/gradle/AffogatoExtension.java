package dev.affogato.gradle;

import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

public class AffogatoExtension {
    private final ConfigurableFileCollection sourceDirs;
    private final DirectoryProperty generatedSourcesDir;
    private final Property<Boolean> failOnWarnings;
    private final Property<Integer> javaRelease;

    public AffogatoExtension(Project project) {
        this.sourceDirs = project.files();
        this.generatedSourcesDir = project.getObjects().directoryProperty();
        this.failOnWarnings = project.getObjects().property(Boolean.class);
        this.javaRelease = project.getObjects().property(Integer.class);

        this.sourceDirs.from(project.file("src/main/affogato"));
        this.generatedSourcesDir.convention(project.getLayout().getBuildDirectory().dir("generated/sources/affogato/main/java"));
        this.failOnWarnings.convention(false);
        this.javaRelease.convention(21);
    }

    public ConfigurableFileCollection getSourceDirs() {
        return sourceDirs;
    }

    public DirectoryProperty getGeneratedSourcesDir() {
        return generatedSourcesDir;
    }

    public Property<Boolean> getFailOnWarnings() {
        return failOnWarnings;
    }

    public Property<Integer> getJavaRelease() {
        return javaRelease;
    }
}
