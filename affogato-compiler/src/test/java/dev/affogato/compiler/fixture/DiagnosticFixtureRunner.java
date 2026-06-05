package dev.affogato.compiler.fixture;

import dev.affogato.compiler.AffogatoCompilationException;
import dev.affogato.compiler.AffogatoCompilationResult;
import dev.affogato.compiler.AffogatoCompiler;
import dev.affogato.compiler.AffogatoCompilerOptions;
import dev.affogato.compiler.AffogatoDiagnostic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared harness for directory-based diagnostic fixtures (negative, lexer, parser).
 */
public final class DiagnosticFixtureRunner {
    private record DiagnosticDetail(String code, int line, int col, String messageSubstring) {}

    private DiagnosticFixtureRunner() {
    }

    public static void assertFixtureFails(Path fixture, List<Path> classpathEntries) throws Exception {
        List<String> expected = readExpectedDiagnostics(fixture.resolve("expected-diagnostics.txt"));
        require(!expected.isEmpty(), "Fixture has no expected diagnostic codes.");

        Path workDir = Files.createTempDirectory("affogato-fixture-" + fixture.getFileName());
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        copyAffogatoSources(fixture, sourceRoot);

        Path generatedRoot = workDir.resolve("generated");
        AffogatoCompilerOptions.Builder options = AffogatoCompilerOptions.builder()
                .addSourceRoot(sourceRoot)
                .outputDirectory(generatedRoot);
        for (Path entry : classpathEntries) {
            options.addClasspathEntry(entry);
        }

        List<AffogatoDiagnostic> actualDiagnostics;
        try {
            new AffogatoCompiler().compile(options.build());
            throw new AssertionError("Fixture compiled successfully but expected failure: " + expected);
        } catch (AffogatoCompilationException exception) {
            actualDiagnostics = exception.diagnostics();
            Set<String> actual = new TreeSet<>();
            for (AffogatoDiagnostic diagnostic : actualDiagnostics) {
                actual.add(diagnostic.code());
            }
            List<String> missing = expected.stream()
                    .filter(code -> !actual.contains(code))
                    .toList();
            require(missing.isEmpty(),
                    "Missing expected diagnostics " + missing + "." + System.lineSeparator()
                            + "  expected: " + expected + System.lineSeparator()
                            + "  actual:   " + actual + System.lineSeparator()
                            + describe(exception));
        }

        Path detailFile = fixture.resolve("expected-diagnostics-detail.txt");
        if (Files.isRegularFile(detailFile)) {
            checkDiagnosticDetails(detailFile, actualDiagnostics);
        }

        Path strictFile = fixture.resolve("expected-diagnostics-strict.txt");
        if (Files.isRegularFile(strictFile)) {
            checkStrictDiagnostics(strictFile, actualDiagnostics);
        }

        require(readJavaFiles(generatedRoot).isEmpty(),
                "Fixture left partial Java output under " + generatedRoot + ".");
    }

    public static void assertFixtureCompiles(Path fixture) throws Exception {
        Path workDir = Files.createTempDirectory("affogato-valid-" + fixture.getFileName());
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        copyAffogatoSources(fixture, sourceRoot);

        Path generatedRoot = workDir.resolve("generated");
        AffogatoCompilationResult result = new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                .addSourceRoot(sourceRoot)
                .outputDirectory(generatedRoot)
                .build());
        require(!result.hasErrors(), "Fixture produced errors: " + result.diagnostics());
        if (!Files.isRegularFile(fixture.resolve("allow-empty-output"))) {
            require(!readJavaFiles(generatedRoot).isEmpty(), "Fixture produced no Java output.");
        }
    }

    public static void runAllUnder(Path root, List<Path> classpathEntries) throws Exception {
        require(Files.isDirectory(root),
                "Fixture directory is missing: " + root.toAbsolutePath()
                        + " (tests must run with the module directory as the working directory).");

        List<Path> fixtures;
        try (var stream = Files.list(root)) {
            fixtures = stream.filter(Files::isDirectory).sorted().toList();
        }
        require(!fixtures.isEmpty(), "No fixtures found under " + root + ".");

        List<String> failures = new ArrayList<>();
        for (Path fixture : fixtures) {
            try {
                assertFixtureFails(fixture, classpathEntries);
            } catch (AssertionError failure) {
                failures.add("[" + fixture.getFileName() + "] " + failure.getMessage());
            }
        }
        require(failures.isEmpty(),
                "Fixture failures (" + failures.size() + "):" + System.lineSeparator()
                        + String.join(System.lineSeparator() + System.lineSeparator(), failures));
    }

    public static void runAllValidUnder(Path root) throws Exception {
        require(Files.isDirectory(root),
                "Valid fixture directory is missing: " + root.toAbsolutePath()
                        + " (tests must run with the module directory as the working directory).");

        List<Path> fixtures;
        try (var stream = Files.list(root)) {
            fixtures = stream.filter(Files::isDirectory).sorted().toList();
        }
        require(!fixtures.isEmpty(), "No valid fixtures found under " + root + ".");

        List<String> failures = new ArrayList<>();
        for (Path fixture : fixtures) {
            try {
                assertFixtureCompiles(fixture);
            } catch (AssertionError failure) {
                failures.add("[" + fixture.getFileName() + "] " + failure.getMessage());
            }
        }
        require(failures.isEmpty(),
                "Valid fixture failures (" + failures.size() + "):" + System.lineSeparator()
                        + String.join(System.lineSeparator() + System.lineSeparator(), failures));
    }

    private static void checkStrictDiagnostics(Path strictFile, List<AffogatoDiagnostic> actuals) throws Exception {
        Set<String> expected = new TreeSet<>(readExpectedDiagnostics(strictFile));
        Set<String> actualErrors = new TreeSet<>();
        for (AffogatoDiagnostic diagnostic : actuals) {
            if (diagnostic.isError()) {
                actualErrors.add(diagnostic.code());
            }
        }
        require(actualErrors.equals(expected),
                "Strict diagnostic set mismatch." + System.lineSeparator()
                        + "  expected exactly: " + expected + System.lineSeparator()
                        + "  actual errors:    " + actualErrors);
    }

    private static void copyAffogatoSources(Path fixture, Path sourceRoot) throws Exception {
        List<Path> affFiles;
        try (var stream = Files.walk(fixture)) {
            affFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".aff"))
                    .sorted()
                    .toList();
        }
        require(!affFiles.isEmpty(), "Fixture has no .aff source files.");
        for (Path aff : affFiles) {
            Path target = sourceRoot.resolve(fixture.relativize(aff).toString());
            Files.createDirectories(target.getParent());
            Files.copy(aff, target);
        }
    }

    private static void checkDiagnosticDetails(
            Path detailFile, List<AffogatoDiagnostic> actuals) throws Exception {
        List<DiagnosticDetail> details = readDiagnosticDetails(detailFile);
        List<String> unmatched = new ArrayList<>();
        for (DiagnosticDetail detail : details) {
            boolean found = actuals.stream().anyMatch(d ->
                    d.code().equals(detail.code())
                            && d.line() == detail.line()
                            && d.column() == detail.col()
                            && d.message().contains(detail.messageSubstring()));
            if (!found) {
                String actualSummary = actuals.stream()
                        .filter(d -> d.code().equals(detail.code()))
                        .map(d -> d.code() + ":" + d.line() + ":" + d.column() + " \"" + d.message() + "\"")
                        .toList()
                        .toString();
                unmatched.add("  No match for: " + detail.code() + " line=" + detail.line()
                        + " col=" + detail.col() + " msg~=\"" + detail.messageSubstring() + "\""
                        + " (same-code actuals: " + actualSummary + ")");
            }
        }
        require(unmatched.isEmpty(),
                "Diagnostic detail mismatches:" + System.lineSeparator()
                        + String.join(System.lineSeparator(), unmatched));
    }

    private static List<DiagnosticDetail> readDiagnosticDetails(Path path) throws Exception {
        List<DiagnosticDetail> details = new ArrayList<>();
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split(" ", 4);
            require(parts.length == 4,
                    "Bad expected-diagnostics-detail.txt line (expected: CODE line col message): " + raw);
            details.add(new DiagnosticDetail(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), parts[3]));
        }
        return details;
    }

    private static List<String> readExpectedDiagnostics(Path path) throws Exception {
        require(Files.isRegularFile(path), "Missing expected-diagnostics.txt.");
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("#"))
                .toList();
    }

    private static List<Path> readJavaFiles(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static String describe(AffogatoCompilationException exception) {
        return exception.diagnostics().stream()
                .map(diagnostic -> "  " + diagnostic.code() + ": " + diagnostic.message())
                .reduce((a, b) -> a + System.lineSeparator() + b)
                .orElse("");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
