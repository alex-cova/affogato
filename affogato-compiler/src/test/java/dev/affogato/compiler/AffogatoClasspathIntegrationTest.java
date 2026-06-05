package dev.affogato.compiler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.Test;

public final class AffogatoClasspathIntegrationTest {
    @Test
    public void compilesAgainstMultipleJavaClasspathRoots() throws Exception {
        Path workDir = Files.createTempDirectory("affogato-classpath");
        Path alphaClasses = compileJava(workDir, "alpha-src", "alpha-classes",
                "thirdparty/alpha/Names.java",
                """
                        package thirdparty.alpha;

                        import java.util.ArrayList;
                        import java.util.List;

                        public final class Names {
                            public static List<String> listOf(String value) {
                                ArrayList<String> names = new ArrayList<>();
                                names.add(value);
                                return names;
                            }
                        }
                        """);
        Path betaClasses = compileJava(workDir, "beta-src", "beta-classes",
                "thirdparty/beta/NameBox.java",
                """
                        package thirdparty.beta;

                        public final class NameBox {
                            private final String label;

                            public NameBox(String label) {
                                this.label = label;
                            }

                            public String label() {
                                return label;
                            }
                        }
                        """);

        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("App.aff"), """
                package dev.affogato.classpath

                import thirdparty.alpha.Names
                import thirdparty.beta.NameBox

                class App {
                    run(): String {
                        let box = NameBox("Ada")
                        let names = Names.listOf(box.label())
                        return names.get(0).toUpperCase()
                    }
                }
                """, StandardCharsets.UTF_8);

        AffogatoCompilationResult result = new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                .addSourceRoot(sourceRoot)
                .addClasspathEntry(alphaClasses)
                .addClasspathEntry(betaClasses)
                .outputDirectory(workDir.resolve("generated"))
                .build());

        require(result.diagnostics().stream().noneMatch(AffogatoDiagnostic::isError),
                "Expected clean classpath integration compile, got: " + result.diagnostics());
    }

    private static Path compileJava(Path workDir, String sourceDirName, String classesDirName,
                                    String relativeSource, String source) throws Exception {
        Path sourceFile = workDir.resolve(sourceDirName).resolve(relativeSource);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        Path classes = workDir.resolve(classesDirName);
        Files.createDirectories(classes);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "A JDK with javac is required.");
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<File> sources = new ArrayList<>();
            sources.add(sourceFile.toFile());
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(sources);
            Boolean ok = compiler.getTask(null, fileManager, null, List.of(
                    "--release", "21",
                    "-parameters",
                    "-d", classes.toString()
            ), null, units).call();
            require(Boolean.TRUE.equals(ok), "Java classpath fixture did not compile.");
        }
        return classes;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
