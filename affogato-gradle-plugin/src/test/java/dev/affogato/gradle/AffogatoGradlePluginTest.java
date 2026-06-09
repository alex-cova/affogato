package dev.affogato.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AffogatoGradlePluginTest {
    @Test
    public void singleProjectBuildWiresGeneratedSourcesIntoJavaCompile() throws Exception {
        Path projectDir = newProjectDir();
        writeBasicBuild(projectDir);
        Path sourceDir = projectDir.resolve("src/main/affogato/dev/affogato/app");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("App.aff"), """
                package dev.affogato.app

                class App {
                    static func main(args: String[]) {
                        println("ok")
                    }
                }
                """, StandardCharsets.UTF_8);

        BuildResult result = runner(projectDir, "build").build();

        require(result.task(":compileAffogato").getOutcome() == TaskOutcome.SUCCESS, "compileAffogato should run successfully.");
        require(result.task(":compileJava").getOutcome() == TaskOutcome.SUCCESS, "compileJava should compile generated Java.");
        require(Files.exists(projectDir.resolve("build/generated/sources/affogato/main/java/dev/affogato/app/App.java")),
                "Affogato output should be wired to the main Java source set.");
    }

    @Test
    public void registersAffogatoDirectoryAsJavaSourceRoot() throws Exception {
        Path projectDir = newProjectDir();
        Files.writeString(projectDir.resolve("settings.gradle.kts"), """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }

                rootProject.name = "fixture"
                """, StandardCharsets.UTF_8);
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("dev.affogato")
                }

                tasks.register("printAffogatoSourceDirs") {
                    doLast {
                        sourceSets.getByName("main").java.srcDirs.forEach { println("AFFOGATO_SRC_DIR=" + it.path) }
                    }
                }
                """, StandardCharsets.UTF_8);

        BuildResult result = runner(projectDir, "printAffogatoSourceDirs").build();

        String expectedSuffix = "src" + File.separator + "main" + File.separator + "affogato";
        require(result.getOutput().lines().anyMatch(line -> line.startsWith("AFFOGATO_SRC_DIR=") && line.endsWith(expectedSuffix)),
                "src/main/affogato should be registered as a Java source root so IntelliJ marks it as a source folder.");
    }

    @Test
    public void compileAffogatoFailsClearlyAndDoesNotWritePartialSources() throws Exception {
        Path projectDir = newProjectDir();
        writeBasicBuild(projectDir);
        Path sourceDir = projectDir.resolve("src/main/affogato/dev/affogato/app");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("Bad.aff"), """
                package dev.affogato.app

                class Bad {
                    String broken() {
                        return 1
                    }
                }
                """, StandardCharsets.UTF_8);

        BuildResult result = runner(projectDir, "compileAffogato").buildAndFail();

        require(result.getOutput().contains("AFFOGATO_RETURN_TYPE"), "compileAffogato should print compiler diagnostics.");
        require(!Files.exists(projectDir.resolve("build/generated/sources/affogato/main/java/dev/affogato/app/Bad.java")),
                "compileAffogato must not write generated Java after compiler errors.");
    }

    @Test
    public void compileAffogatoIsUpToDateWhenAffogatoSourcesAreUnchanged() throws Exception {
        Path projectDir = newProjectDir();
        writeBasicBuild(projectDir);
        Path sourceDir = projectDir.resolve("src/main/affogato/dev/affogato/app");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("App.aff"), """
                package dev.affogato.app

                class App {
                    run(): String {
                        return "ok"
                    }
                }
                """, StandardCharsets.UTF_8);

        runner(projectDir, "compileAffogato").build();
        BuildResult second = runner(projectDir, "compileAffogato").build();

        require(second.task(":compileAffogato").getOutcome() == TaskOutcome.UP_TO_DATE,
                "compileAffogato should be up-to-date when individual .aff inputs are unchanged.");
    }

    private static GradleRunner runner(Path projectDir, String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments)
                .withPluginClasspath();
    }

    private static Path newProjectDir() throws Exception {
        return Files.createTempDirectory("affogato-gradle-plugin-test");
    }

    private static void writeBasicBuild(Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }

                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        mavenCentral()
                    }
                }

                rootProject.name = "fixture"
                """, StandardCharsets.UTF_8);
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("dev.affogato")
                }
                """, StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
