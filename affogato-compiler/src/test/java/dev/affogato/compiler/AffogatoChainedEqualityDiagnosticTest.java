package dev.affogato.compiler;

import org.junit.Test;

public final class AffogatoChainedEqualityDiagnosticTest {
    @Test
    public void chainedEqualityTypeErrorPointsAtRightOperand() throws Exception {
        java.nio.file.Path workDir = java.nio.file.Files.createTempDirectory("affogato-chained-eq");
        java.nio.file.Path sourceRoot = workDir.resolve("src");
        java.nio.file.Files.createDirectories(sourceRoot);
        java.nio.file.Files.writeString(sourceRoot.resolve("Bad.aff"), """
                package dev.affogato.test

                class Bad {
                    run(a: int, b: int, c: int): boolean {
                        return a == b == c
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
                    .filter(d -> "AFFOGATO_OPERATOR_TYPE".equals(d.code()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing OPERATOR_TYPE: " + exception.diagnostics()));
            require(diagnostic.line() == 5, "Expected line 5, was " + diagnostic.line());
            require(diagnostic.column() == 26, "Expected column 26 on 'c', was " + diagnostic.column());
            require(diagnostic.length() == 1, "Expected span length 1, was " + diagnostic.length());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
