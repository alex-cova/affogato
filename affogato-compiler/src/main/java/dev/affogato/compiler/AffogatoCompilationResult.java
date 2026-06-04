package dev.affogato.compiler;

import java.nio.file.Path;
import java.util.List;

public final class AffogatoCompilationResult {
    private final List<Path> generatedFiles;
    private final List<AffogatoDiagnostic> diagnostics;

    public AffogatoCompilationResult(List<Path> generatedFiles, List<AffogatoDiagnostic> diagnostics) {
        this.generatedFiles = List.copyOf(generatedFiles);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Path> generatedFiles() {
        return generatedFiles;
    }

    public List<AffogatoDiagnostic> diagnostics() {
        return diagnostics;
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(AffogatoDiagnostic::isError);
    }
}
