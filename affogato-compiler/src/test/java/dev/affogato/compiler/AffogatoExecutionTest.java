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
 *   <li>{@code main-class.txt} — fully-qualified class name to load.</li>
 *   <li>{@code entry-point.txt} — optional {@code run} (default) or {@code main} entry method.</li>
 *   <li>{@code expected-output.txt} — expected stdout (may be empty).</li>
 *   <li>{@code expected-stderr.txt} — optional expected stderr.</li>
 * </ul>
 * The fixture class must expose {@code public static void run()} or {@code public static void main(String[])}.
 * The test compiles Affogato, compiles generated Java with javac, runs the entry point, and compares streams.
 *
 * <p>To create or refresh expected output after an intentional change, run with the update flag:
 * <pre>{@code
 *   GRADLE_USER_HOME=.gradle ./gradlew :affogato-compiler:test \
 *       --tests dev.affogato.compiler.AffogatoExecutionTest \
 *       -Daffogato.exec.update=true
 * }</pre>
 * (or set {@code AFFOGATO_UPDATE_EXEC=1}).
 */
public final class AffogatoExecutionTest {
    private static final Path EXEC_ROOT = Path.of("src/test/resources/exec");

    private enum EntryPoint {
        RUN,
        MAIN
    }

    private record CapturedOutput(String stdout, String stderr) {
    }

    private static final boolean UPDATE =
            Boolean.getBoolean("affogato.exec.update")
                    || "1".equals(System.getenv("AFFOGATO_UPDATE_EXEC"));

    @Test
    public void executionFixturesProduceExpectedOutput() throws Exception {
        require(Files.isDirectory(EXEC_ROOT),
                "Execution fixtures directory is missing: " + EXEC_ROOT.toAbsolutePath()
                        + " (tests must run with the module directory as the working directory).");

        List<Path> fixtures;
        try (var stream = Files.list(EXEC_ROOT)) {
            fixtures = stream.filter(Files::isDirectory).sorted().toList();
        }
        require(!fixtures.isEmpty(), "No execution fixtures found under " + EXEC_ROOT + ".");

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

        EntryPoint entryPoint = readEntryPoint(fixture);
        Path classesDir = compileFixture(fixture);
        CapturedOutput actual = invokeEntryPoint(classesDir, mainClass, entryPoint);

        Path expectedOutputFile = fixture.resolve("expected-output.txt");
        Path expectedStderrFile = fixture.resolve("expected-stderr.txt");
        if (UPDATE) {
            Files.writeString(expectedOutputFile, actual.stdout() + System.lineSeparator(), StandardCharsets.UTF_8);
            if (Files.isRegularFile(expectedStderrFile) || !actual.stderr().isEmpty()) {
                Files.writeString(expectedStderrFile, actual.stderr() + System.lineSeparator(), StandardCharsets.UTF_8);
            }
            return;
        }

        require(Files.isRegularFile(expectedOutputFile),
                "Missing expected-output.txt. Run with -Daffogato.exec.update=true to create it.");
        String expectedOutput = normalizeOutput(Files.readString(expectedOutputFile, StandardCharsets.UTF_8));
        require(expectedOutput.equals(actual.stdout()),
                "Stdout mismatch." + System.lineSeparator()
                        + "--- expected ---" + System.lineSeparator() + expectedOutput
                        + System.lineSeparator() + "--- actual ---" + System.lineSeparator() + actual.stdout());

        if (Files.isRegularFile(expectedStderrFile)) {
            String expectedStderr = normalizeOutput(Files.readString(expectedStderrFile, StandardCharsets.UTF_8));
            require(expectedStderr.equals(actual.stderr()),
                    "Stderr mismatch." + System.lineSeparator()
                            + "--- expected ---" + System.lineSeparator() + expectedStderr
                            + System.lineSeparator() + "--- actual ---" + System.lineSeparator() + actual.stderr());
        }
    }

    private static EntryPoint readEntryPoint(Path fixture) throws Exception {
        Path entryPointFile = fixture.resolve("entry-point.txt");
        if (!Files.isRegularFile(entryPointFile)) {
            return EntryPoint.RUN;
        }
        String value = Files.readString(entryPointFile, StandardCharsets.UTF_8).strip();
        return "main".equalsIgnoreCase(value) ? EntryPoint.MAIN : EntryPoint.RUN;
    }

    private Path compileFixture(Path fixture) throws Exception {
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

        List<File> helperSources = collectHelperJava(fixture);
        Path helperClasses = workDir.resolve("helper-classes");
        if (!helperSources.isEmpty()) {
            compileJavaFiles(helperSources, helperClasses);
        }

        Path generatedRoot = workDir.resolve("generated");
        try {
            AffogatoCompilerOptions.Builder options = AffogatoCompilerOptions.builder()
                    .addSourceRoot(sourceRoot)
                    .outputDirectory(generatedRoot);
            if (!helperSources.isEmpty()) {
                options.addClasspathEntry(helperClasses);
            }
            AffogatoCompilationResult result = new AffogatoCompiler().compile(options.build());
            require(!result.hasErrors(), "Fixture produced error diagnostics: " + result.diagnostics());
        } catch (AffogatoCompilationException exception) {
            throw new AssertionError("Fixture failed to compile: " + describe(exception));
        }

        Path classesDir = workDir.resolve("classes");
        compileJavaSources(generatedRoot, classesDir, collectHelperJava(fixture));
        return classesDir;
    }

    private static List<File> collectHelperJava(Path fixture) throws Exception {
        List<File> helpers = new ArrayList<>();
        Path helperRoot = fixture.resolve("java");
        if (!Files.isDirectory(helperRoot)) {
            return helpers;
        }
        try (var stream = Files.walk(helperRoot)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::toFile)
                    .forEach(helpers::add);
        }
        return helpers;
    }

    private static void compileJavaSources(Path generatedRoot, Path classesDir, List<File> extraSources) throws Exception {
        List<File> javaFiles = new ArrayList<>(extraSources);
        try (var stream = Files.walk(generatedRoot)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::toFile)
                    .forEach(javaFiles::add);
        }
        require(!javaFiles.isEmpty(), "Compiler produced no Java output.");
        compileJavaFiles(javaFiles, classesDir);
    }

    private static void compileJavaFiles(List<File> javaFiles, Path classesDir) throws Exception {
        Files.createDirectories(classesDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "A JDK with javac is required.");
        require(!javaFiles.isEmpty(), "No Java sources to compile.");

        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(javaFiles);
            Boolean ok = compiler.getTask(null, fileManager, null, List.of(
                    "--release", "21",
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classesDir.toString()
            ), null, units).call();
            require(Boolean.TRUE.equals(ok), "Java sources did not compile with javac.");
        }
    }

    private static CapturedOutput invokeEntryPoint(Path classesDir, String mainClass, EntryPoint entryPoint) throws Exception {
        URL[] urls = {classesDir.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getSystemClassLoader())) {
            Class<?> clazz = loader.loadClass(mainClass);
            Method method = entryPoint == EntryPoint.MAIN
                    ? clazz.getMethod("main", String[].class)
                    : clazz.getMethod("run");

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            try {
                if (entryPoint == EntryPoint.MAIN) {
                    method.invoke(null, (Object) new String[0]);
                } else {
                    method.invoke(null);
                }
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
            return new CapturedOutput(
                    normalizeOutput(stdout.toString(StandardCharsets.UTF_8)),
                    normalizeOutput(stderr.toString(StandardCharsets.UTF_8))
            );
        }
    }

    private static String normalizeOutput(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
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
