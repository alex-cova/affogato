package dev.affogato.compiler.cli;

import dev.affogato.compiler.AffogatoCompilationException;
import dev.affogato.compiler.AffogatoCompilationResult;
import dev.affogato.compiler.AffogatoCompiler;
import dev.affogato.compiler.AffogatoCompilerOptions;
import dev.affogato.compiler.AffogatoDiagnosticPrinter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class AffogatoCli {
    private AffogatoCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** @return process exit code (0 success, 1 compile failure, 2 usage error) */
    static int run(String[] args) {
        if (args.length == 0 || hasHelpFlag(args)) {
            printUsage();
            return args.length == 0 ? 2 : 0;
        }

        if (args.length < 2) {
            System.err.println("error: missing <source-dir> and <generated-java-dir>");
            printUsage();
            return 2;
        }

        Path sourceRoot = Path.of(args[0]);
        Path outputDirectory = Path.of(args[1]);
        boolean failOnWarnings = false;
        int javaRelease = AffogatoCompiler.SUPPORTED_JAVA_RELEASE;
        List<Path> classpath = new ArrayList<>();

        for (int index = 2; index < args.length; index++) {
            String arg = args[index];
            switch (arg) {
                case "--help", "-h" -> {
                    printUsage();
                    return 0;
                }
                case "--fail-on-warnings" -> failOnWarnings = true;
                case "--release" -> {
                    if (index + 1 >= args.length) {
                        System.err.println("error: --release requires a value");
                        return 2;
                    }
                    String releaseValue = args[++index];
                    try {
                        javaRelease = Integer.parseInt(releaseValue);
                    } catch (NumberFormatException notANumber) {
                        System.err.println("error: --release expects an integer, got: " + releaseValue);
                        return 2;
                    }
                }
                case "--classpath", "-cp" -> {
                    if (index + 1 >= args.length) {
                        System.err.println("error: --classpath requires a path");
                        return 2;
                    }
                    classpath.add(Path.of(args[++index]));
                }
                default -> {
                    System.err.println("error: unknown argument: " + arg);
                    printUsage();
                    return 2;
                }
            }
        }

        AffogatoCompilerOptions.Builder options = AffogatoCompilerOptions.builder()
                .addSourceRoot(sourceRoot)
                .outputDirectory(outputDirectory)
                .failOnWarnings(failOnWarnings)
                .javaRelease(javaRelease);
        classpath.forEach(options::addClasspathEntry);

        try {
            AffogatoCompilationResult result = new AffogatoCompiler().compile(options.build());
            AffogatoDiagnosticPrinter.printAll(System.err, result.diagnostics());
            System.out.println("Generated " + result.generatedFiles().size() + " Java file(s).");
            return 0;
        } catch (AffogatoCompilationException exception) {
            AffogatoDiagnosticPrinter.printAll(System.err, exception.diagnostics());
            return 1;
        }
    }

    private static boolean hasHelpFlag(String[] args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void printUsage() {
        System.out.println("""
                Affogato compiler — transpile .aff sources to Java 21

                Usage:
                  affogato <source-dir> <generated-java-dir> [options]

                Options:
                  -h, --help              Show this help
                  --fail-on-warnings      Treat warnings as errors
                  --release <n>           Target Java release (currently 21 only)
                  -cp, --classpath <path>  Java classpath entry for interop (repeatable)

                Examples:
                  affogato src/main/affogato build/generated/affogato
                  affogato src/main/affogato out -cp libs/app.jar --fail-on-warnings
                """);
    }
}
