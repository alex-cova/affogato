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
    public void rendersWithoutSnippetWhenSourceMissing() {
        AffogatoDiagnostic diagnostic = new AffogatoDiagnostic(
                AffogatoDiagnostic.Severity.ERROR,
                "AFFOGATO_IO",
                "Permission denied",
                null,
                1,
                1
        );
        String rendered = AffogatoDiagnosticRenderer.render(diagnostic, null);
        requireContains(rendered, "AFFOGATO_IO: Permission denied");
        require(!rendered.contains("|"), "Should not render snippet without source.");
        requireContains(rendered, "Hint: Check file permissions");
    }

    @Test
    public void rendersMultiCharacterCaretSpan() {
        String source = "let duplicate = 1";
        AffogatoDiagnostic diagnostic = new AffogatoDiagnostic(
                AffogatoDiagnostic.Severity.ERROR,
                "AFFOGATO_DUPLICATE_LOCAL",
                "Duplicate local variable 'duplicate' in the same block.",
                null,
                1,
                5,
                9
        );
        String rendered = AffogatoDiagnosticRenderer.render(diagnostic, source);
        requireContains(rendered, "1 | let duplicate = 1");
        requireContains(rendered, "    ^^^^^^^^^");
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
