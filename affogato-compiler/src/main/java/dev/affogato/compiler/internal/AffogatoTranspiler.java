package dev.affogato.compiler.internal;

import dev.affogato.compiler.AffogatoDiagnostic;
import static dev.affogato.compiler.internal.TranspilerTypes.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class AffogatoTranspiler implements AutoCloseable {
    final List<AffogatoDiagnostic> diagnostics;
    private final CompilationSession session;
    private final AffogatoJavaGenerator generator;
    private final AffogatoTypeChecker typeChecker;

    public AffogatoTranspiler(List<AffogatoDiagnostic> diagnostics, List<Path> classpath, Path javaMetadataCacheDirectory) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.session = new CompilationSession(diagnostics, classpath, javaMetadataCacheDirectory);
        this.generator = new AffogatoJavaGenerator(session);
        this.typeChecker = new AffogatoTypeChecker(diagnostics, session.symbols(), session.flow(), generator, session.parserRunner());
        this.generator.bindTypeChecker(typeChecker);
    }

    @Override
    public void close() {
        session.close();
    }

    public ParsedUnit parse(Path sourceFile, String source) {
        TranspilerTypes.ParsedUnit parsed = session.parserRunner().parse(sourceFile, source);
        return new ParsedUnit(parsed.sourceFile(), parsed.unit());
    }

    public void registerSymbols(ParsedUnit parsedUnit) {
        session.symbols().registerSymbols(parsedUnit.unit());
    }

    public void typeCheck(ParsedUnit parsedUnit) {
        typeChecker.setActiveTypeParams(generator.activeTypeParams());
        typeChecker.typeCheck(parsedUnit);
        session.setTypesChecked(true);
    }

    public List<GeneratedJava> generate(ParsedUnit parsedUnit) {
        return generator.generate(parsedUnit);
    }

    public record ParsedUnit(Path sourceFile, CompilationUnit unit) {
        static ParsedUnit empty(Path sourceFile, String source) {
            TranspilerTypes.ParsedUnit empty = TranspilerTypes.ParsedUnit.empty(sourceFile, source);
            return new ParsedUnit(empty.sourceFile(), empty.unit());
        }
    }

    public record GeneratedJava(String packageName, String className, String source) {
    }
}
