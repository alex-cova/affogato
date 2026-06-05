package dev.affogato.compiler;

import java.nio.file.Path;
import java.util.Objects;

public final class AffogatoDiagnostic {
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    private final Severity severity;
    private final String code;
    private final String message;
    private final Path source;
    private final int line;
    private final int column;
    private final int length;

    public AffogatoDiagnostic(Severity severity, String code, String message, Path source, int line, int column) {
        this(severity, code, message, source, line, column, 1);
    }

    public AffogatoDiagnostic(
            Severity severity,
            String code,
            String message,
            Path source,
            int line,
            int column,
            int length
    ) {
        this.severity = Objects.requireNonNull(severity, "severity");
        this.code = Objects.requireNonNull(code, "code");
        this.message = Objects.requireNonNull(message, "message");
        this.source = source;
        this.line = line;
        this.column = column;
        this.length = length <= 0 ? 1 : length;
    }

    public Severity severity() {
        return severity;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public Path source() {
        return source;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    /** Highlight span length on the diagnostic line (1-based column is the start). */
    public int length() {
        return length;
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        String location = source == null ? "" : source + ":" + line + ":" + column + ": ";
        return location + severity + " " + code + ": " + message;
    }
}
