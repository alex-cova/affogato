package dev.affogato.compiler;

import dev.affogato.compiler.AffogatoDiagnostic.Severity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public final class AffogatoCompilerFlowTest {
    @Test
    public void unreachableCodeReportsWarningByDefault() throws Exception {
        Path workDir = Files.createTempDirectory("affogato-flow-warn");
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Unreachable.aff"), """
                package dev.affogato.flow

                class Unreachable {
                    func demo() {
                        return
                        println("never")
                    }
                }
                """, StandardCharsets.UTF_8);

        AffogatoCompilationResult result = new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                .addSourceRoot(sourceRoot)
                .outputDirectory(workDir.resolve("generated"))
                .build());

        require(result.diagnostics().stream().anyMatch(d ->
                "AFFOGATO_UNREACHABLE".equals(d.code()) && d.severity() == Severity.WARNING),
                "Expected unreachable warning, got: " + result.diagnostics());
    }

    @Test
    public void unreachableCodeFailsWhenFailOnWarningsEnabled() throws Exception {
        Path workDir = Files.createTempDirectory("affogato-flow-fail");
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Unreachable.aff"), """
                package dev.affogato.flow

                class Unreachable {
                    func demo() {
                        return
                        println("never")
                    }
                }
                """, StandardCharsets.UTF_8);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(sourceRoot)
                    .outputDirectory(workDir.resolve("generated"))
                    .failOnWarnings(true)
                    .build());
            throw new AssertionError("Expected compilation to fail on unreachable warning.");
        } catch (AffogatoCompilationException exception) {
            require(exception.diagnostics().stream().anyMatch(d -> "AFFOGATO_UNREACHABLE".equals(d.code())),
                    "Expected AFFOGATO_UNREACHABLE, got: " + exception.diagnostics());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
