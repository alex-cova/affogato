package dev.affogato.compiler;

import dev.affogato.compiler.fixture.DiagnosticFixtureRunner;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    public void negativeFixturesFailWithExpectedDiagnostics() throws Exception {
        Path apiClasses = Files.createTempDirectory("affogato-negative-api").resolve("classes");
        Files.createDirectories(apiClasses.getParent());
        apiClasses = compileJavaApi(apiClasses.getParent());
        DiagnosticFixtureRunner.runAllUnder(NEGATIVE_ROOT, List.of(apiClasses));
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

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
