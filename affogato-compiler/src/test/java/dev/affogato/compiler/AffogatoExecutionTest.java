package dev.affogato.compiler;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Execution test harness for the Affogato compiler.
 *
 * <p>Each fixture lives under {@code src/test/resources/exec/<feature>/} and contains:
 * <ul>
 *   <li>One or more {@code .aff} source files.</li>
 *   <li>{@code main-class.txt} — the fully-qualified class name whose {@code run()} method to invoke.</li>
 *   <li>{@code expected-output.txt} — expected stdout, compared after stripping trailing whitespace.</li>
 * </ul>
 * The fixture's class must expose a {@code public static void run()} method with no parameters. The
 * test compiles the Affogato source, compiles the generated Java, loads the classes, captures stdout,
 * and compares against {@code expected-output.txt}.
 */
public final class AffogatoExecutionTest {
    private static final Path EXEC_ROOT = Path.of("src/test/resources/exec");

    @Test
    public void executionFixturesProduceExpectedOutput() throws Exception {
        if (!Files.isDirectory(EXEC_ROOT)) {
            return;
        }

        List<Path> fixtures;
        try (var stream = Files.list(EXEC_ROOT)) {
            fixtures = stream.filter(Files::isDirectory).sorted().toList();
        }
        if (fixtures.isEmpty()) {
            return;
        }

        List<String> failures = new ArrayList<>();
        for (Path fixture : fixtures) {
            try {
                runFixture(fixture);
            } catch (AssertionError failure) {
                failures.add("[" + fixture.getFileName() + "] " + failure.getMessage());
            }
        }
        require(failures.isEmpty(),
                "Execution fixture failures (" + failures.size() + "):" + System.lineSeparator()
                        + String.join(System.lineSeparator() + System.lineSeparator(), failures));
    }

    private void runFixture(Path fixture) throws Exception {
        Path mainClassFile = fixture.resolve("main-class.txt");
        require(Files.isRegularFile(mainClassFile), "Missing main-class.txt.");
        String mainClass = Files.readString(mainClassFile, StandardCharsets.UTF_8).strip();

        Path expectedOutputFile = fixture.resolve("expected-output.txt");
        require(Files.isRegularFile(expectedOutputFile), "Missing expected-output.txt.");
        String expectedOutput = Files.readString(expectedOutputFile, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n').stripTrailing();

        Path workDir = Files.createTempDirectory("affogato-exec-" + fixture.getFileName());
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

        Path generatedRoot = workDir.resolve("generated");
        try {
            AffogatoCompilerOptions options = AffogatoCompilerOptions.builder()
                    .addSourceRoot(sourceRoot)
                    .outputDirectory(generatedRoot)
                    .build();
            AffogatoCompilationResult result = new AffogatoCompiler().compile(options);
            require(!result.hasErrors(), "Fixture produced error diagnostics: " + result.diagnostics());
        } catch (AffogatoCompilationException exception) {
            throw new AssertionError("Fixture failed to compile: " + describe(exception));
        }

        Path classesDir = workDir.resolve("classes");
        compileGeneratedJava(generatedRoot, classesDir);

        String actualOutput = invokeRun(classesDir, mainClass);
        String actual = actualOutput.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();

        require(expectedOutput.equals(actual),
                "Output mismatch." + System.lineSeparator()
                        + "--- expected ---" + System.lineSeparator() + expectedOutput
                        + System.lineSeparator() + "--- actual ---" + System.lineSeparator() + actual);
    }

    private static void compileGeneratedJava(Path generatedRoot, Path classesDir) throws Exception {
        Files.createDirectories(classesDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "A JDK with javac is required.");

        List<File> javaFiles = new ArrayList<>();
        try (var stream = Files.walk(generatedRoot)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::toFile)
                    .forEach(javaFiles::add);
        }
        require(!javaFiles.isEmpty(), "Compiler produced no Java output.");

        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(javaFiles);
            Boolean ok = compiler.getTask(null, fileManager, null, List.of(
                    "--release", "21",
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classesDir.toString()
            ), null, units).call();
            require(Boolean.TRUE.equals(ok), "Generated Java did not compile with javac.");
        }
    }

    private static String invokeRun(Path classesDir, String mainClass) throws Exception {
        URL[] urls = {classesDir.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getSystemClassLoader())) {
            Class<?> clazz = loader.loadClass(mainClass);
            Method run = clazz.getMethod("run");

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            PrintStream original = System.out;
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            try {
                run.invoke(null);
            } finally {
                System.setOut(original);
            }
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    private static String describe(AffogatoCompilationException exception) {
        List<String> codes = exception.diagnostics().stream()
                .map(diagnostic -> diagnostic.code() + " (" + diagnostic.message() + ")")
                .toList();
        return String.join("; ", codes);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
