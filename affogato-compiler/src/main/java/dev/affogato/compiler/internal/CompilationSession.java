package dev.affogato.compiler.internal;

import dev.affogato.compiler.AffogatoDiagnostic;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

final class CompilationSession implements AutoCloseable {
    private final List<AffogatoDiagnostic> diagnostics;
    private final AffogatoSymbolResolver symbols;
    private final FlowAnalyzer flow;
    private final AffogatoParserRunner parserRunner;
    private boolean typesChecked;

    CompilationSession(List<AffogatoDiagnostic> diagnostics, List<Path> classpath, Path javaMetadataCacheDirectory) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.symbols = new AffogatoSymbolResolver(diagnostics, classpath, javaMetadataCacheDirectory);
        this.flow = new FlowAnalyzer(diagnostics);
        this.parserRunner = new AffogatoParserRunner(diagnostics);
    }

    List<AffogatoDiagnostic> diagnostics() {
        return diagnostics;
    }

    AffogatoSymbolResolver symbols() {
        return symbols;
    }

    FlowAnalyzer flow() {
        return flow;
    }

    AffogatoParserRunner parserRunner() {
        return parserRunner;
    }

    boolean typesChecked() {
        return typesChecked;
    }

    void setTypesChecked(boolean typesChecked) {
        this.typesChecked = typesChecked;
    }

    @Override
    public void close() {
        symbols.close();
    }
}
