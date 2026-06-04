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

    public AffogatoDiagnostic(Severity severity, String code, String message, Path source, int line, int column) {
        this.severity = Objects.requireNonNull(severity, "severity");
        this.code = Objects.requireNonNull(code, "code");
        this.message = Objects.requireNonNull(message, "message");
        this.source = source;
        this.line = line;
        this.column = column;
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

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        String location = source == null ? "" : source + ":" + line + ":" + column + ": ";
        return location + severity + " " + code + ": " + message;
    }
}
