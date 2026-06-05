package dev.affogato.compiler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AffogatoCompilationException extends RuntimeException {
    private final List<AffogatoDiagnostic> diagnostics;

    public AffogatoCompilationException(List<AffogatoDiagnostic> diagnostics) {
        super(buildMessage(diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<AffogatoDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static String buildMessage(List<AffogatoDiagnostic> diagnostics) {
        if (diagnostics.isEmpty()) {
            return "Affogato compilation failed.";
        }
        StringBuilder message = new StringBuilder("Affogato compilation failed:");
        Map<java.nio.file.Path, String> sources = new HashMap<>();
        for (AffogatoDiagnostic diagnostic : diagnostics) {
            if (diagnostic.isError()) {
                message.append(System.lineSeparator())
                        .append(AffogatoDiagnosticPrinter.render(diagnostic, sources));
            }
        }
        return message.toString();
    }
}
