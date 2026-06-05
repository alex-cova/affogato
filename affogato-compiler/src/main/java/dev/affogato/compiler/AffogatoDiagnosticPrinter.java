package dev.affogato.compiler;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Prints diagnostics with snippets when source files are available. */
public final class AffogatoDiagnosticPrinter {
    private AffogatoDiagnosticPrinter() {
    }

    public static void printAll(PrintStream out, List<AffogatoDiagnostic> diagnostics) {
        Map<Path, String> sources = new HashMap<>();
        for (AffogatoDiagnostic diagnostic : diagnostics) {
            out.println(render(diagnostic, sources));
        }
    }

    public static String render(AffogatoDiagnostic diagnostic, Map<Path, String> sourceCache) {
        return AffogatoDiagnosticRenderer.render(diagnostic, sourceFor(sourceCache, diagnostic.source()));
    }

    private static String sourceFor(Map<Path, String> cache, Path sourceFile) {
        if (sourceFile == null) {
            return null;
        }
        return cache.computeIfAbsent(sourceFile, AffogatoDiagnosticPrinter::readSource);
    }

    private static String readSource(Path sourceFile) {
        try {
            if (Files.isRegularFile(sourceFile)) {
                return Files.readString(sourceFile, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // Fall back to message-only rendering.
        }
        return null;
    }
}
