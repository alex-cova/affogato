package dev.affogato.compiler.cli;

import dev.affogato.compiler.AffogatoCompiler;
import dev.affogato.compiler.AffogatoCompilerOptions;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class AffogatoCliTest {
    @Test
    public void helpFlagPrintsUsageAndReturnsZero() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            int code = AffogatoCli.run(new String[] {"--help"});
            require(code == 0, "Help should exit 0, was " + code);
            String text = out.toString(StandardCharsets.UTF_8);
            require(text.contains("Usage:"), "Help should print usage.");
            require(text.contains("--classpath"), "Help should document classpath.");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    public void missingArgsReturnsUsageError() {
        require(AffogatoCli.run(new String[0]) == 2, "No args should return 2.");
    }

    @Test
    public void successfulCompileReturnsZero() throws Exception {
        Path workDir = Files.createTempDirectory("affogato-cli-ok");
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("App.aff"), """
                package dev.affogato.cli

                class App {
                    run(): String {
                        return "ok"
                    }
                }
                """, StandardCharsets.UTF_8);
        Path outputDir = workDir.resolve("generated");

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            int code = AffogatoCli.run(new String[] {
                    sourceRoot.toString(),
                    outputDir.toString()
            });
            require(code == 0, "Successful compile should exit 0, was " + code);
            require(out.toString(StandardCharsets.UTF_8).contains("Generated 1 Java file(s)."),
                    "CLI should report generated file count.");
            require(Files.isRegularFile(outputDir.resolve("dev/affogato/cli/App.java")),
                    "CLI should write generated Java.");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    public void compileFailureReturnsOne() throws Exception {
        Path workDir = Files.createTempDirectory("affogato-cli-fail");
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Bad.aff"), """
                package dev.affogato.cli

                class Bad {
                    run(): String {
                        return missing
                    }
                }
                """, StandardCharsets.UTF_8);
        Path outputDir = workDir.resolve("generated");

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            int code = AffogatoCli.run(new String[] {
                    sourceRoot.toString(),
                    outputDir.toString()
            });
            require(code == 1, "Compile failure should exit 1, was " + code);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    public void unknownArgumentReturnsUsageError() {
        require(AffogatoCli.run(new String[] {"src", "out", "--not-a-flag"}) == 2,
                "Unknown flag should return 2.");
    }

    @Test
    public void nonNumericReleaseReturnsUsageErrorInsteadOfCrashing() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            int code = AffogatoCli.run(new String[] {"src", "out", "--release", "abc"});
            require(code == 2, "Non-numeric --release should return 2, was " + code);
            require(err.toString(StandardCharsets.UTF_8).contains("--release"),
                    "Error message should mention --release.");
        } finally {
            System.setErr(originalErr);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
