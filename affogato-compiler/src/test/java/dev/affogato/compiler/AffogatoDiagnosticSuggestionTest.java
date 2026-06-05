package dev.affogato.compiler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class AffogatoDiagnosticSuggestionTest {
    @Test
    public void unresolvedIdentifierSuggestsVisibleVariable() throws Exception {
        AffogatoCompilationException exception = compileFail("""
                package dev.affogato.suggest

                class App {
                    run(): int {
                        let count = 1
                        cout
                        return count
                    }
                }
                """);

        AffogatoDiagnostic diagnostic = find(exception, "AFFOGATO_IDENTIFIER_RESOLUTION");
        require(diagnostic.hint() != null && diagnostic.hint().contains("count"),
                "Expected identifier suggestion for count, got: " + diagnostic.hint());
    }

    @Test
    public void unresolvedTypeSuggestsKnownType() throws Exception {
        AffogatoCompilationException exception = compileFail("""
                package dev.affogato.suggest

                class Customer {
                }

                class App {
                    run(value: Coustomer): String {
                        return "ok"
                    }
                }
                """);

        AffogatoDiagnostic diagnostic = find(exception, "AFFOGATO_TYPE_RESOLUTION");
        require(diagnostic.hint() != null && diagnostic.hint().contains("Customer"),
                "Expected type suggestion for Customer, got: " + diagnostic.hint());
    }

    @Test
    public void nestedLocalShadowingFailsBeforeJavaGeneration() throws Exception {
        AffogatoCompilationException exception = compileFail("""
                package dev.affogato.suggest

                class App {
                    run(value: int): int {
                        if true {
                            let value = 2
                            return value
                        }
                        return value
                    }
                }
                """);

        find(exception, "AFFOGATO_DUPLICATE_LOCAL");
    }

    private static AffogatoCompilationException compileFail(String source) throws Exception {
        Path workDir = Files.createTempDirectory("affogato-suggest");
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("App.aff"), source, StandardCharsets.UTF_8);
        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(sourceRoot)
                    .outputDirectory(workDir.resolve("generated"))
                    .build());
            throw new AssertionError("Expected compilation to fail.");
        } catch (AffogatoCompilationException exception) {
            return exception;
        }
    }

    private static AffogatoDiagnostic find(AffogatoCompilationException exception, String code) {
        return exception.diagnostics().stream()
                .filter(diagnostic -> code.equals(diagnostic.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + code + ": " + exception.diagnostics()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
