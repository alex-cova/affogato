package dev.affogato.compiler;

/**
 * Helpers for mapping diagnostics to source text positions.
 */
public final class SourceLocations {
    private SourceLocations() {
    }

    /**
     * Finds a 1-based column for {@code identifier} on {@code line}, preferring an occurrence at or after
     * {@code searchFromColumn}. Returns {@code searchFromColumn} when the identifier cannot be located.
     */
    public static int columnOfIdentifier(String source, int line, String identifier, int searchFromColumn) {
        if (source == null || identifier == null || identifier.isBlank() || line < 1) {
            return Math.max(1, searchFromColumn);
        }
        String lineText = lineText(source, line);
        if (lineText.isEmpty()) {
            return Math.max(1, searchFromColumn);
        }
        int preferredIndex = Math.max(0, searchFromColumn - 1);
        int bestIndex = -1;
        int bestDistance = Integer.MAX_VALUE;
        int index = 0;
        while (index < lineText.length()) {
            int found = lineText.indexOf(identifier, index);
            if (found < 0) {
                break;
            }
            if (isWordBoundary(lineText, found, found + identifier.length())) {
                int distance = Math.abs(found - preferredIndex);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = found;
                }
            }
            index = found + 1;
        }
        return bestIndex >= 0 ? bestIndex + 1 : Math.max(1, searchFromColumn);
    }

    public static String lineText(String source, int line) {
        if (source == null || line < 1) {
            return "";
        }
        int current = 1;
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                if (current == line) {
                    return source.substring(start, index);
                }
                current++;
                start = index + 1;
            }
        }
        return current == line ? source.substring(start) : "";
    }

    private static boolean isWordBoundary(String text, int start, int end) {
        boolean left = start == 0 || !isIdentifierPart(text.charAt(start - 1));
        boolean right = end >= text.length() || !isIdentifierPart(text.charAt(end));
        return left && right;
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }
}
