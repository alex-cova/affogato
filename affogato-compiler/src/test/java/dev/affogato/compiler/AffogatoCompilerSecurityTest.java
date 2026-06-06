package dev.affogato.compiler;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Security-focused unit tests for compiler output paths.
 */
public final class AffogatoCompilerSecurityTest {
    @Test
    public void packageDirectoryRejectsParentTraversalSegment() throws Exception {
        Path output = Files.createTempDirectory("affogato-security-out");
        require(resolvePackageDirectory(output, "..") == null,
                "Expected null for parent-segment package name.");
    }

    @Test
    public void packageDirectoryRejectsBlankSegments() throws Exception {
        Path output = Files.createTempDirectory("affogato-security-out");
        require(resolvePackageDirectory(output, "com..example") == null,
                "Expected null for blank package segment.");
        require(resolvePackageDirectory(output, "com.example..") == null,
                "Expected null for trailing blank package segment.");
    }

    @Test
    public void packageDirectoryKeepsResolvedPathInsideOutputRoot() throws Exception {
        Path output = Files.createTempDirectory("affogato-security-out");
        Path resolved = resolvePackageDirectory(output, "dev.affogato.safe");
        require(resolved != null, "Expected a safe package directory.");
        require(resolved.normalize().startsWith(output.toAbsolutePath().normalize()),
                "Resolved package directory escaped output root: " + resolved);
    }

    @Test
    public void maliciousPackageNameReportsWriteDiagnostic() throws Exception {
        Path workDir = Files.createTempDirectory("affogato-security-compile");
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        // Parser rejects most malformed packages; simulate traversal at write time via blank segment.
        Files.writeString(sourceRoot.resolve("Escape.aff"), """
                package com..escape

                class Escape {
                    run(): String {
                        return "ok"
                    }
                }
                """, java.nio.charset.StandardCharsets.UTF_8);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(sourceRoot)
                    .outputDirectory(workDir.resolve("generated"))
                    .build());
            throw new AssertionError("Expected compilation to fail on invalid package.");
        } catch (AffogatoCompilationException exception) {
            require(exception.diagnostics().stream().anyMatch(d -> "AFFOGATO_PARSE".equals(d.code())),
                    "Expected AFFOGATO_PARSE for malformed package, got: " + exception.diagnostics());
        }
    }

    private static Path resolvePackageDirectory(Path outputDirectory, String packageName) throws Exception {
        Method packageDirectory = AffogatoCompiler.class.getDeclaredMethod("packageDirectory", Path.class, String.class);
        packageDirectory.setAccessible(true);
        return (Path) packageDirectory.invoke(new AffogatoCompiler(), outputDirectory, packageName);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
