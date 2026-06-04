package dev.affogato.gradle;

import dev.affogato.runtime.NotNull;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

import java.io.File;
import java.net.URISyntaxException;

public final class AffogatoGradlePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        AffogatoExtension extension = project.getExtensions().create("affogato", AffogatoExtension.class, project);

        project.getPlugins().withId("java", ignored -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            sourceSets.configureEach(sourceSet -> {
                TaskProvider<AffogatoCompile> compileAffogato = registerCompileTask(project, extension, sourceSet);
                sourceSet.getJava().srcDir(compileAffogato.flatMap(AffogatoCompile::getOutputDirectory));
                project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class).configure(task -> {
                    task.dependsOn(compileAffogato);
                    task.getOptions().getRelease().set(extension.getJavaRelease());
                });
            });
            addRuntimeDependency(project);
        });
    }

    private TaskProvider<AffogatoCompile> registerCompileTask(Project project, AffogatoExtension extension, SourceSet sourceSet) {
        String sourceSetName = sourceSet.getName();
        boolean main = SourceSet.MAIN_SOURCE_SET_NAME.equals(sourceSetName);
        String taskName = sourceSet.getTaskName("compile", "Affogato");
        Provider<Directory> generatedSources = main
                ? extension.getGeneratedSourcesDir()
                : project.getLayout().getBuildDirectory().dir("generated/sources/affogato/" + sourceSetName + "/java");

        return project.getTasks().register(taskName, AffogatoCompile.class, task -> {
            task.setDescription("Transpiles " + sourceSetName + " Affogato sources to Java.");
            task.setGroup("build");
            if (main) {
                task.getSourceDirs().from(extension.getSourceDirs());
            } else {
                task.getSourceDirs().from(project.file("src/" + sourceSetName + "/affogato"));
            }
            task.getOutputDirectory().set(generatedSources);
            task.getFailOnWarnings().set(extension.getFailOnWarnings());
            task.getJavaRelease().set(extension.getJavaRelease());
            task.getClasspath().from(sourceSet.getCompileClasspath());
        });
    }

    private void addRuntimeDependency(Project project) {
        File runtimeJar = runtimeClasspathLocation();
        if (runtimeJar != null) {
            project.getDependencies().add("implementation", project.files(runtimeJar));
        }
    }

    private File runtimeClasspathLocation() {
        try {
            return new File(NotNull.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | SecurityException exception) {
            return null;
        }
    }
}
