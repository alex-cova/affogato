package dev.affogato.compiler.cli;

import dev.affogato.compiler.AffogatoCompilationException;
import dev.affogato.compiler.AffogatoCompilationResult;
import dev.affogato.compiler.AffogatoCompiler;
import dev.affogato.compiler.AffogatoCompilerOptions;
import dev.affogato.compiler.AffogatoDiagnostic;

import java.nio.file.Path;

public final class AffogatoCli {
    private AffogatoCli() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: affogato <source-dir> <generated-java-dir> [--fail-on-warnings] [--release <java-release>]");
            System.exit(2);
        }

        boolean failOnWarnings = false;
        int javaRelease = 21;
        for (int index = 2; index < args.length; index++) {
            if ("--fail-on-warnings".equals(args[index])) {
                failOnWarnings = true;
            } else if ("--release".equals(args[index]) && index + 1 < args.length) {
                javaRelease = Integer.parseInt(args[++index]);
            }
        }
        AffogatoCompilerOptions options = AffogatoCompilerOptions.builder()
                .addSourceRoot(Path.of(args[0]))
                .outputDirectory(Path.of(args[1]))
                .failOnWarnings(failOnWarnings)
                .javaRelease(javaRelease)
                .build();

        try {
            AffogatoCompilationResult result = new AffogatoCompiler().compile(options);
            for (AffogatoDiagnostic diagnostic : result.diagnostics()) {
                System.err.println(diagnostic);
            }
            System.out.println("Generated " + result.generatedFiles().size() + " Java file(s).");
        } catch (AffogatoCompilationException exception) {
            for (AffogatoDiagnostic diagnostic : exception.diagnostics()) {
                System.err.println(diagnostic);
            }
            System.exit(1);
        }
    }
}
