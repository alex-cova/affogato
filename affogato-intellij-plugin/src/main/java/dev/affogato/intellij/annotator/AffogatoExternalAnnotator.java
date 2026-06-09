package dev.affogato.intellij.annotator;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import dev.affogato.compiler.AffogatoCompilationException;
import dev.affogato.compiler.AffogatoCompilationResult;
import dev.affogato.compiler.AffogatoCompiler;
import dev.affogato.compiler.AffogatoCompilerOptions;
import dev.affogato.compiler.AffogatoDiagnostic;
import dev.affogato.compiler.AffogatoDiagnosticCodes;
import dev.affogato.intellij.project.AffogatoClasspath;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class AffogatoExternalAnnotator extends ExternalAnnotator<AffogatoClasspath.ModuleInfo, List<AffogatoDiagnostic>> {

    public AffogatoExternalAnnotator() {
    }

    @Override
    public @Nullable AffogatoClasspath.ModuleInfo collectInformation(@NotNull PsiFile file) {
        return AffogatoClasspath.moduleInfo(file);
    }

    @Override
    public @Nullable List<AffogatoDiagnostic> doAnnotate(AffogatoClasspath.ModuleInfo info) {
        if (info == null) {
            return null;
        }
        Path outputDir;
        try {
            outputDir = Files.createTempDirectory("affogato-annotator");
        } catch (IOException e) {
            return null;
        }

        try {
            AffogatoCompilerOptions.Builder builder = AffogatoCompilerOptions.builder()
                    .outputDirectory(outputDir);
            builder.addSourceRoot(info.sourceRoot());
            for (Path entry : info.libraryPaths()) {
                builder.addClasspathEntry(entry);
            }

            List<AffogatoDiagnostic> diagnostics;
            try {
                AffogatoCompilationResult result = new AffogatoCompiler().compile(builder.build());
                diagnostics = result.diagnostics();
            } catch (AffogatoCompilationException e) {
                diagnostics = e.diagnostics();
            }

            return diagnostics.stream()
                    .filter(d -> d.source() != null && isSameFile(d.source(), info.filePath()))
                    .toList();
        } finally {
            deleteQuietly(outputDir);
        }
    }

    @Override
    public void apply(@NotNull PsiFile file, List<AffogatoDiagnostic> diagnostics, @NotNull AnnotationHolder holder) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return;
        }
        Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null) {
            return;
        }

        for (AffogatoDiagnostic diagnostic : diagnostics) {
            HighlightSeverity severity = switch (diagnostic.severity()) {
                case ERROR -> HighlightSeverity.ERROR;
                case WARNING -> HighlightSeverity.WARNING;
                case INFO -> HighlightSeverity.INFORMATION;
            };
            TextRange range = diagnosticRange(document, diagnostic);
            String message = diagnostic.message();
            String hint = AffogatoDiagnosticCodes.hint(diagnostic.code()).orElse("");
            if (!hint.isBlank()) {
                message = message + " — " + hint;
            }
            holder.newAnnotation(severity, "[" + diagnostic.code() + "] " + message)
                    .range(range)
                    .create();
        }
    }

    private static TextRange diagnosticRange(Document document, AffogatoDiagnostic diagnostic) {
        int line = Math.max(0, Math.min(diagnostic.line() - 1, document.getLineCount() - 1));
        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        int start = Math.min(lineStart + Math.max(0, diagnostic.column() - 1), lineEnd);
        int end = Math.min(start + Math.max(1, diagnostic.length()), lineEnd);
        if (end <= start) {
            end = Math.min(start + 1, lineEnd);
        }
        return TextRange.create(start, end);
    }

    private static boolean isSameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
