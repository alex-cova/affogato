package dev.affogato.compiler;

import org.junit.Test;

public final class AffogatoDiagnosticRendererTest {
    @Test
    public void rendersSnippetCaretAndHint() {
        String source = """
                class App {
                    func run() {
                        return total
                    }
                }
                """;
        AffogatoDiagnostic diagnostic = new AffogatoDiagnostic(
                AffogatoDiagnostic.Severity.ERROR,
                "AFFOGATO_IDENTIFIER_RESOLUTION",
                "Cannot resolve identifier total.",
                null,
                3,
                9,
                5
        );
        String rendered = AffogatoDiagnosticRenderer.render(diagnostic, source);
        requireContains(rendered, "AFFOGATO_IDENTIFIER_RESOLUTION: Cannot resolve identifier total.");
        requireContains(rendered, "3 |         return total");
        requireContains(rendered, "        ^^^^^");
        requireContains(rendered, "Hint: Declare the name before use");
    }

    @Test
    public void columnOfIdentifierPrefersLaterOccurrenceOnSameLine() {
        int column = SourceLocations.columnOfIdentifier("let y = x + 1", 1, "x", 9);
        require(column == 9, "Expected x at column 9 but was " + column);
    }

    private static void requireContains(String text, String expected) {
        require(text.contains(expected), "Missing expected text: " + expected + System.lineSeparator() + text);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
