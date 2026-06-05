package dev.affogato.compiler;

import org.junit.Test;

public final class AffogatoUseBeforeInitTest {
    @Test
    public void useBeforeInitReportsDedicatedDiagnosticWithSpan() throws Exception {
        java.nio.file.Path workDir = java.nio.file.Files.createTempDirectory("affogato-use-before-init");
        java.nio.file.Path sourceRoot = workDir.resolve("src");
        java.nio.file.Files.createDirectories(sourceRoot);
        java.nio.file.Files.writeString(sourceRoot.resolve("App.aff"), """
                package dev.affogato.test

                class App {
                    run(): int {
                        let y: int = x + 1
                        let x = 1
                        return y
                    }
                }
                """, java.nio.charset.StandardCharsets.UTF_8);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(sourceRoot)
                    .outputDirectory(workDir.resolve("generated"))
                    .build());
            throw new AssertionError("Expected compilation failure.");
        } catch (AffogatoCompilationException exception) {
            AffogatoDiagnostic diagnostic = exception.diagnostics().stream()
                    .filter(d -> "AFFOGATO_USE_BEFORE_INIT".equals(d.code()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing USE_BEFORE_INIT: " + exception.diagnostics()));
            require(diagnostic.line() == 5, "Expected line 5, was " + diagnostic.line());
            require(diagnostic.column() == 22, "Expected column 22, was " + diagnostic.column());
            require(diagnostic.length() == 1, "Expected span length 1 for 'x', was " + diagnostic.length());
            String source = java.nio.file.Files.readString(sourceRoot.resolve("App.aff"));
            String rendered = AffogatoDiagnosticRenderer.render(diagnostic, source);
            require(rendered.contains("Hint: Declare the variable before use"),
                    "Expected USE_BEFORE_INIT hint in: " + rendered);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
