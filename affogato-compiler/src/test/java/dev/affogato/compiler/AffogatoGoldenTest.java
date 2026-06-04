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
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

/**
 * Golden test harness for the Affogato compiler.
 *
 * <p>Each fixture lives under {@code src/test/resources/golden/<feature>/} and contains one or more
 * {@code .aff} source files plus an {@code expected/} directory holding the exact Java the compiler
 * is expected to emit (keyed by the generated package path). Every fixture is compiled in isolation,
 * the generated Java is compared byte-for-byte against the golden files, and the result is fed to
 * {@code javac} to prove it is valid Java.
 *
 * <p>To create or refresh goldens after an intentional change, run with the update flag:
 * <pre>{@code
 *   GRADLE_USER_HOME=.gradle ./gradlew :affogato-compiler:test \
 *       --tests dev.affogato.compiler.AffogatoGoldenTest \
 *       -Daffogato.golden.update=true
 * }</pre>
 * (or set {@code AFFOGATO_UPDATE_GOLDEN=1}). Review the diff before committing — the update mode
 * trusts the current compiler output.
 */
public final class AffogatoGoldenTest {
    private static final Path GOLDEN_ROOT = Path.of("src/test/resources/golden");

    private static final boolean UPDATE =
            Boolean.getBoolean("affogato.golden.update")
                    || "1".equals(System.getenv("AFFOGATO_UPDATE_GOLDEN"));

    @Test
    public void goldenFixturesMatchGeneratedJava() throws Exception {
        require(Files.isDirectory(GOLDEN_ROOT),
                "Golden fixtures directory is missing: " + GOLDEN_ROOT.toAbsolutePath()
                        + " (tests must run with the module directory as the working directory).");

        List<Path> fixtures;
        try (var stream = Files.list(GOLDEN_ROOT)) {
            fixtures = stream.filter(Files::isDirectory).sorted().toList();
        }
        require(!fixtures.isEmpty(), "No golden fixtures found under " + GOLDEN_ROOT + ".");

        List<String> failures = new ArrayList<>();
        for (Path fixture : fixtures) {
            try {
                runFixture(fixture);
            } catch (AssertionError failure) {
                failures.add("[" + fixture.getFileName() + "] " + failure.getMessage());
            }
        }
        require(failures.isEmpty(),
                "Golden mismatches (" + failures.size() + "):" + System.lineSeparator()
                        + String.join(System.lineSeparator() + System.lineSeparator(), failures));
    }

    private void runFixture(Path fixture) throws Exception {
        Path workDir = Files.createTempDirectory("affogato-golden-" + fixture.getFileName());
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);

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

        List<Path> fixtureClasspath = compileFixtureJava(fixture, workDir);
        Path generatedRoot = workDir.resolve("generated");
        AffogatoCompilationResult result;
        try {
            AffogatoCompilerOptions.Builder options = AffogatoCompilerOptions.builder()
                    .addSourceRoot(sourceRoot)
                    .outputDirectory(generatedRoot);
            fixtureClasspath.forEach(options::addClasspathEntry);
            result = new AffogatoCompiler().compile(options.build());
        } catch (AffogatoCompilationException exception) {
            throw new AssertionError("Fixture failed to compile: " + describe(exception));
        }
        require(!result.hasErrors(), "Fixture produced error diagnostics: " + result.diagnostics());

        Map<String, String> actual = readJavaTree(generatedRoot);
        require(!actual.isEmpty(), "Compiler produced no Java output.");

        Path expectedDir = fixture.resolve("expected");
        if (UPDATE) {
            writeGolden(expectedDir, actual);
            return;
        }

        require(Files.isDirectory(expectedDir),
                "Missing expected/ golden directory. Run with -Daffogato.golden.update=true to create it.");
        Map<String, String> expected = readJavaTree(expectedDir);

        require(actual.keySet().equals(expected.keySet()),
                "Generated file set does not match golden." + System.lineSeparator()
                        + "  expected: " + expected.keySet() + System.lineSeparator()
                        + "  actual:   " + actual.keySet());

        for (String relative : expected.keySet()) {
            String want = normalize(expected.get(relative));
            String have = normalize(actual.get(relative));
            if (!want.equals(have)) {
                throw new AssertionError("Generated Java differs from golden for " + relative
                        + System.lineSeparator() + "--- expected ---" + System.lineSeparator() + want
                        + System.lineSeparator() + "--- actual ---" + System.lineSeparator() + have);
            }
        }

        compileGeneratedJava(generatedRoot, workDir.resolve("classes"), fixtureClasspath);
    }

    private static List<Path> compileFixtureJava(Path fixture, Path workDir) throws Exception {
        List<Path> javaSources;
        try (var stream = Files.walk(fixture)) {
            javaSources = stream
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !fixture.relativize(path).startsWith("expected"))
                    .sorted()
                    .toList();
        }
        if (javaSources.isEmpty()) {
            return List.of();
        }

        Path classesDir = workDir.resolve("api-classes");
        Files.createDirectories(classesDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "A JDK with javac is required.");
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<File> files = javaSources.stream().map(Path::toFile).toList();
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(files);
            Boolean ok = compiler.getTask(null, fileManager, null, List.of(
                    "--release", "21",
                    "-parameters",
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classesDir.toString()
            ), null, units).call();
            require(Boolean.TRUE.equals(ok), "Fixture Java API did not compile with javac.");
        }
        return List.of(classesDir);
    }

    private static Map<String, String> readJavaTree(Path root) throws Exception {
        Map<String, String> files = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (var stream = Files.walk(root)) {
            List<Path> javaFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
            for (Path java : javaFiles) {
                String key = root.relativize(java).toString().replace(File.separatorChar, '/');
                files.put(key, Files.readString(java, StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private static void writeGolden(Path expectedDir, Map<String, String> actual) throws Exception {
        if (Files.exists(expectedDir)) {
            try (var stream = Files.walk(expectedDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (Exception exception) {
                        throw new RuntimeException("Failed to clear stale golden: " + path, exception);
                    }
                });
            }
        }
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            Path out = expectedDir.resolve(entry.getKey());
            Files.createDirectories(out.getParent());
            Files.writeString(out, entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static void compileGeneratedJava(Path generatedRoot, Path classesDir, List<Path> fixtureClasspath) throws Exception {
        Files.createDirectories(classesDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "A JDK with javac is required.");

        List<File> javaFiles = new ArrayList<>();
        try (var stream = Files.walk(generatedRoot)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::toFile)
                    .forEach(javaFiles::add);
        }

        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(javaFiles);
            List<String> classpathEntries = new ArrayList<>();
            classpathEntries.add(System.getProperty("java.class.path"));
            fixtureClasspath.stream().map(Path::toString).forEach(classpathEntries::add);
            List<String> options = List.of(
                    "--release", "21",
                    "-classpath", String.join(File.pathSeparator, classpathEntries),
                    "-d", classesDir.toString()
            );
            Boolean ok = compiler.getTask(null, fileManager, null, options, null, units).call();
            require(Boolean.TRUE.equals(ok), "Golden Java did not compile with javac.");
        }
    }

    private static String describe(AffogatoCompilationException exception) {
        List<String> codes = exception.diagnostics().stream()
                .map(diagnostic -> diagnostic.code() + " (" + diagnostic.message() + ")")
                .toList();
        return String.join("; ", codes);
    }

    /** Normalizes line endings and trailing whitespace so goldens are platform independent. */
    private static String normalize(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            int end = line.length();
            while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
                end--;
            }
            builder.append(line, 0, end).append('\n');
        }
        int trailing = builder.length();
        while (trailing > 0 && builder.charAt(trailing - 1) == '\n') {
            trailing--;
        }
        return builder.substring(0, trailing);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
