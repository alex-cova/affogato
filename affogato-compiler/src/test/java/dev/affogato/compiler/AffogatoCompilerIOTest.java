package dev.affogato.compiler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class AffogatoCompilerIOTest {
    @Test
    public void unreadableSourceReportsIoDiagnostic() throws Exception {
        Path workDir = Files.createTempDirectory("affogato-io-read");
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Path sourceFile = sourceRoot.resolve("Unreadable.aff");
        Files.writeString(sourceFile, """
                package dev.affogato.io

                class Unreadable {
                    run(): String {
                        return "ok"
                    }
                }
                """, StandardCharsets.UTF_8);

        if (!sourceFile.toFile().setReadable(false, false)) {
            return;
        }

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(sourceRoot)
                    .outputDirectory(workDir.resolve("generated"))
                    .build());
            throw new AssertionError("Expected compilation to report IO failure.");
        } catch (AffogatoCompilationException exception) {
            require(exception.diagnostics().stream().anyMatch(d -> "AFFOGATO_IO".equals(d.code())),
                    "Expected AFFOGATO_IO, got: " + exception.diagnostics());
        } finally {
            sourceFile.toFile().setReadable(true, false);
        }
    }

    @Test
    public void outputPathThatIsAFileReportsWriteDiagnostic() throws Exception {
        Path workDir = Files.createTempDirectory("affogato-io-write");
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("App.aff"), """
                package dev.affogato.io

                class App {
                    run(): String {
                        return "ok"
                    }
                }
                """, StandardCharsets.UTF_8);

        Path outputFile = workDir.resolve("generated");
        Files.createFile(outputFile);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(sourceRoot)
                    .outputDirectory(outputFile)
                    .build());
            throw new AssertionError("Expected compilation to report write failure.");
        } catch (AffogatoCompilationException exception) {
            require(exception.diagnostics().stream().anyMatch(d -> "AFFOGATO_WRITE".equals(d.code())),
                    "Expected AFFOGATO_WRITE, got: " + exception.diagnostics());
        }
    }

    @Test
    public void invalidPackageSegmentReportsParseDiagnostic() throws Exception {
        Path workDir = Files.createTempDirectory("affogato-io-package");
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("BadPackage.aff"), """
                package dev..affogato

                class BadPackage {
                    run(): String {
                        return "ok"
                    }
                }
                """, StandardCharsets.UTF_8);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(sourceRoot)
                    .outputDirectory(workDir.resolve("generated"))
                    .build());
            throw new AssertionError("Expected compilation to fail on invalid package.");
        } catch (AffogatoCompilationException exception) {
            require(exception.diagnostics().stream().anyMatch(d -> "AFFOGATO_PARSE".equals(d.code())),
                    "Expected AFFOGATO_PARSE, got: " + exception.diagnostics());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
