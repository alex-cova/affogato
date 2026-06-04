package dev.affogato.compiler;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Negative fixture harness for the Affogato compiler.
 *
 * <p>Each fixture lives under {@code src/test/resources/negative/<case>/} and contains one or more
 * {@code .aff} source files plus {@code expected-diagnostics.txt}. The diagnostics file lists the
 * required diagnostic codes, one per line. Message text is intentionally not matched so wording can
 * improve without making these regression tests brittle.
 */
public final class AffogatoNegativeTest {
    private static final Path NEGATIVE_ROOT = Path.of("src/test/resources/negative");

    private record DiagnosticDetail(String code, int line, int col, String messageSubstring) {}

    @Test
    public void negativeFixturesFailWithExpectedDiagnostics() throws Exception {
        require(Files.isDirectory(NEGATIVE_ROOT),
                "Negative fixtures directory is missing: " + NEGATIVE_ROOT.toAbsolutePath()
                        + " (tests must run with the module directory as the working directory).");

        List<Path> fixtures;
        try (var stream = Files.list(NEGATIVE_ROOT)) {
            fixtures = stream.filter(Files::isDirectory).sorted().toList();
        }
        require(!fixtures.isEmpty(), "No negative fixtures found under " + NEGATIVE_ROOT + ".");

        List<String> failures = new ArrayList<>();
        for (Path fixture : fixtures) {
            try {
                runFixture(fixture);
            } catch (AssertionError failure) {
                failures.add("[" + fixture.getFileName() + "] " + failure.getMessage());
            }
        }
        require(failures.isEmpty(),
                "Negative fixture failures (" + failures.size() + "):" + System.lineSeparator()
                        + String.join(System.lineSeparator() + System.lineSeparator(), failures));
    }

    private void runFixture(Path fixture) throws Exception {
        List<String> expected = readExpectedDiagnostics(fixture.resolve("expected-diagnostics.txt"));
        require(!expected.isEmpty(), "Fixture has no expected diagnostic codes.");

        Path workDir = Files.createTempDirectory("affogato-negative-" + fixture.getFileName());
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        copyAffogatoSources(fixture, sourceRoot);

        Path generatedRoot = workDir.resolve("generated");
        AffogatoCompilerOptions.Builder options = AffogatoCompilerOptions.builder()
                .addSourceRoot(sourceRoot)
                .addClasspathEntry(compileJavaApi(workDir))
                .outputDirectory(generatedRoot);

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

        require(readJavaFiles(generatedRoot).isEmpty(),
                "Negative fixture left partial Java output under " + generatedRoot + ".");
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
            String code = parts[0];
            int lineNum = Integer.parseInt(parts[1]);
            int col = Integer.parseInt(parts[2]);
            String msg = parts[3];
            details.add(new DiagnosticDetail(code, lineNum, col, msg));
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

    private static Path compileJavaApi(Path workDir) throws Exception {
        Path apiSourceRoot = workDir.resolve("api-src/dev/affogato/negative");
        Files.createDirectories(apiSourceRoot);
        Files.writeString(apiSourceRoot.resolve("JavaApi.java"), """
                package dev.affogato.negative;

                public final class JavaApi {
                    public final Nested nested = new Nested();
                    public int mutableCount;
                    public final String direct = "direct";

                    public JavaApi(String label) {
                    }

                    public static String stringOnly(String value) {
                        return value;
                    }

                    public static boolean booleanOnly(boolean value) {
                        return value;
                    }

                    public static String ambiguous(String value) {
                        return value;
                    }

                    public static String ambiguous(StringBuilder value) {
                        return value.toString();
                    }

                    public static String lambdaResult(java.util.function.Function<String, String> fn) {
                        return fn.apply("lambda");
                    }

                    public static String lambdaResult(Object fn) {
                        return fn.toString();
                    }

                    public static <T extends Tagged & Named> String describe(T value) {
                        return value.tag() + ":" + value.name();
                    }

                    public static OnlyTagged onlyTagged() {
                        return new OnlyTagged();
                    }

                    public static String wildcardOnly(java.util.List<? extends CharSequence> values) {
                        return values.get(0).toString();
                    }
                }

                interface Tagged {
                    String tag();
                }

                interface Named {
                    String name();
                }

                final class OnlyTagged implements Tagged {
                    public String tag() {
                        return "tag";
                    }
                }

                final class Nested {
                    public final String name = "nested";
                }
                """, StandardCharsets.UTF_8);

        Path apiClasses = workDir.resolve("api-classes");
        Files.createDirectories(apiClasses);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "A JDK with javac is required.");
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<File> apiSources = new ArrayList<>();
            try (var stream = Files.list(apiSourceRoot)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".java"))
                        .map(Path::toFile)
                        .forEach(apiSources::add);
            }
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(apiSources);
            Boolean ok = compiler.getTask(null, fileManager, null, List.of(
                    "--release", "21",
                    "-parameters",
                    "-d", apiClasses.toString()
            ), null, units).call();
            require(Boolean.TRUE.equals(ok), "Java interop fixture did not compile.");
        }
        return apiClasses;
    }

    private static String describe(AffogatoCompilationException exception) {
        List<String> lines = exception.diagnostics().stream()
                .map(diagnostic -> "  " + diagnostic.code() + ": " + diagnostic.message())
                .toList();
        return String.join(System.lineSeparator(), lines);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
