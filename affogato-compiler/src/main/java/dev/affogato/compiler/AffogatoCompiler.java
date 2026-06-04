package dev.affogato.compiler;

import dev.affogato.compiler.internal.AffogatoTranspiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class AffogatoCompiler {
    public AffogatoCompilationResult compile(AffogatoCompilerOptions options) {
        List<AffogatoDiagnostic> diagnostics = new ArrayList<>();
        List<AffogatoTranspiler.ParsedUnit> units = new ArrayList<>();
        AffogatoTranspiler transpiler = new AffogatoTranspiler(diagnostics, options.classpath());

        for (Path sourceRoot : options.sourceRoots()) {
            if (!Files.exists(sourceRoot)) {
                continue;
            }
            for (Path sourceFile : findAffogatoSources(sourceRoot)) {
                try {
                    String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
                    units.add(transpiler.parse(sourceFile, source));
                } catch (IOException exception) {
                    diagnostics.add(new AffogatoDiagnostic(
                            AffogatoDiagnostic.Severity.ERROR,
                            "AFFOGATO_IO",
                            exception.getMessage(),
                            sourceFile,
                            1,
                            1
                    ));
                }
            }
        }

        for (AffogatoTranspiler.ParsedUnit unit : units) {
            transpiler.registerSymbols(unit);
        }

        failIfNeeded(options, diagnostics);

        // Generate all Java sources first (collecting diagnostics), then write to disk only if clean.
        List<AffogatoTranspiler.GeneratedJava> allGenerated = new ArrayList<>();
        for (AffogatoTranspiler.ParsedUnit unit : units) {
            allGenerated.addAll(transpiler.generate(unit));
        }

        failIfNeeded(options, diagnostics);

        List<Path> generatedFiles = new ArrayList<>();
        for (AffogatoTranspiler.GeneratedJava generated : allGenerated) {
            Path packageDirectory = packageDirectory(options.outputDirectory(), generated.packageName());
            Path outputFile = packageDirectory.resolve(generated.className() + ".java");
            try {
                Files.createDirectories(packageDirectory);
                Files.writeString(outputFile, generated.source(), StandardCharsets.UTF_8);
                generatedFiles.add(outputFile);
            } catch (IOException exception) {
                diagnostics.add(new AffogatoDiagnostic(
                        AffogatoDiagnostic.Severity.ERROR,
                        "AFFOGATO_WRITE",
                        exception.getMessage(),
                        outputFile,
                        1,
                        1
                ));
            }
        }

        return new AffogatoCompilationResult(generatedFiles, diagnostics);
    }

    private void failIfNeeded(AffogatoCompilerOptions options, List<AffogatoDiagnostic> diagnostics) {
        boolean hasErrors = diagnostics.stream().anyMatch(AffogatoDiagnostic::isError);
        boolean hasWarnings = diagnostics.stream().anyMatch(d -> d.severity() == AffogatoDiagnostic.Severity.WARNING);
        if (hasErrors || options.failOnWarnings() && hasWarnings) {
            throw new AffogatoCompilationException(diagnostics);
        }
    }

    private List<Path> findAffogatoSources(Path sourceRoot) {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".aff"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw new AffogatoCompilationException(List.of(new AffogatoDiagnostic(
                    AffogatoDiagnostic.Severity.ERROR,
                    "AFFOGATO_SOURCE_SCAN",
                    exception.getMessage(),
                    sourceRoot,
                    1,
                    1
            )));
        }
    }

    private Path packageDirectory(Path outputDirectory, String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return outputDirectory;
        }
        return outputDirectory.resolve(packageName.replace('.', '/'));
    }
}
