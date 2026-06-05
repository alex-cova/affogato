package dev.affogato.compiler;

import dev.affogato.compiler.AffogatoDiagnostic.Severity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Robustness regressions: the compiler must report deeply-nested input and a misshapen entry point
 * as diagnostics rather than crashing or silently emitting unrunnable Java.
 */
public final class AffogatoRobustnessTest {

    @Test
    public void deeplyNestedExpressionReportsParseDiagnosticInsteadOfStackOverflow() throws Exception {
        Path workDir = Files.createTempDirectory("affogato-deep-nest");
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);

        int depth = 6000;
        String nested = "(".repeat(depth) + "1" + ")".repeat(depth);
        Files.writeString(sourceRoot.resolve("Deep.aff"), """
                package dev.affogato.robust

                class Deep {
                    run(): int {
                        let x = %s
                        return x
                    }
                }
                """.formatted(nested), StandardCharsets.UTF_8);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(sourceRoot)
                    .outputDirectory(workDir.resolve("generated"))
                    .build());
            throw new AssertionError("Expected deeply nested source to fail compilation.");
        } catch (AffogatoCompilationException exception) {
            require(exception.diagnostics().stream().anyMatch(d -> "AFFOGATO_PARSE".equals(d.code())),
                    "Expected AFFOGATO_PARSE diagnostic, got: " + exception.diagnostics());
        }
        // Reaching here (rather than a StackOverflowError escaping) is the point of the test.
    }

    @Test
    public void zeroArgStaticMainWarnsAboutEntryPoint() throws Exception {
        AffogatoCompilationResult result = compileSource("""
                package dev.affogato.robust

                class App {
                    static func main() {
                        println("hi")
                    }
                }
                """);
        require(result.diagnostics().stream().anyMatch(d ->
                        "AFFOGATO_MAIN_SIGNATURE".equals(d.code()) && d.severity() == Severity.WARNING),
                "Expected AFFOGATO_MAIN_SIGNATURE warning, got: " + result.diagnostics());
    }

    @Test
    public void validStringArrayMainDoesNotWarn() throws Exception {
        AffogatoCompilationResult result = compileSource("""
                package dev.affogato.robust

                class App {
                    static func main(args: String[]) {
                        println("hi")
                    }
                }
                """);
        require(result.diagnostics().stream().noneMatch(d -> "AFFOGATO_MAIN_SIGNATURE".equals(d.code())),
                "Valid main(args: String[]) should not warn, got: " + result.diagnostics());
    }

    private static AffogatoCompilationResult compileSource(String source) throws Exception {
        Path workDir = Files.createTempDirectory("affogato-robust");
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("App.aff"), source, StandardCharsets.UTF_8);
        return new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                .addSourceRoot(sourceRoot)
                .outputDirectory(workDir.resolve("generated"))
                .build());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
