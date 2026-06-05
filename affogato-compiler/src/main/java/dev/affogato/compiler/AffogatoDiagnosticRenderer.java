package dev.affogato.compiler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders {@link AffogatoDiagnostic} instances with source snippets, carets, and hints.
 */
public final class AffogatoDiagnosticRenderer {
    private AffogatoDiagnosticRenderer() {
    }

    public static String render(AffogatoDiagnostic diagnostic) {
        return render(diagnostic, null);
    }

    public static String render(AffogatoDiagnostic diagnostic, String source) {
        List<String> lines = new ArrayList<>();
        lines.add(diagnostic.code() + ": " + diagnostic.message());
        if (diagnostic.source() != null && diagnostic.line() > 0) {
            lines.add("  at " + formatLocation(diagnostic.source(), diagnostic.line(), diagnostic.column()));
        }
        if (source != null && diagnostic.line() > 0) {
            String snippet = snippet(source, diagnostic.line(), diagnostic.column(), diagnostic.length());
            if (!snippet.isEmpty()) {
                lines.add(snippet);
            }
        }
        AffogatoDiagnosticCodes.hint(diagnostic.code()).ifPresent(hint -> lines.add("Hint: " + hint));
        return String.join(System.lineSeparator(), lines);
    }

    private static String formatLocation(Path source, int line, int column) {
        String file = source.getFileName() == null ? source.toString() : source.toString();
        if (column > 0) {
            return file + ":" + line + ":" + column;
        }
        return file + ":" + line;
    }

    private static String snippet(String source, int line, int column, int length) {
        String lineText = SourceLocations.lineText(source, line);
        if (lineText.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append(line).append(" | ").append(lineText).append(System.lineSeparator());
        int caretStart = Math.max(0, column - 1);
        int caretLength = Math.max(1, length);
        if (caretStart > lineText.length()) {
            caretStart = lineText.length();
        }
        int caretEnd = Math.min(lineText.length(), caretStart + caretLength);
        if (caretEnd == caretStart) {
            caretEnd = Math.min(lineText.length(), caretStart + 1);
        }
        out.append(" ".repeat(("" + line).length())).append(" | ");
        out.append(" ".repeat(caretStart));
        out.append("^".repeat(Math.max(1, caretEnd - caretStart)));
        return out.toString();
    }
}
