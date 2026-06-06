package dev.affogato.compiler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Focused semantic, diagnostic, and codegen unit tests for behaviors not covered by fixture harnesses alone.
 */
public final class AffogatoSemanticUnitTest {
    @Test
    public void missingReturnFlowPointsAtMethodDeclarationLine() throws Exception {
        Path sourceRoot = writeSource("""
                package dev.affogato.semantic

                class MissingReturn {
                    run(): String {
                        println("no return")
                    }
                }
                """);

        try {
            compile(sourceRoot);
            throw new AssertionError("Expected compilation failure.");
        } catch (AffogatoCompilationException exception) {
            AffogatoDiagnostic diagnostic = findDiagnostic(exception, "AFFOGATO_RETURN_FLOW");
            require(diagnostic.line() == 4,
                    "Expected RETURN_FLOW at method declaration line 4, was " + diagnostic.line());
        }
    }

    @Test
    public void propertyMutationAsStatementCompiles() throws Exception {
        Path sourceRoot = writeSource("""
                package dev.affogato.semantic

                class Counter(var n: int) { }

                class PropertyStatement {
                    run(): int {
                        let c = Counter(0)
                        c.n++
                        c.n += 1
                        return c.n
                    }
                }
                """);

        CompileOutput compiled = compile(sourceRoot);
        require(!compiled.result().hasErrors(), "Property mutation at statement level should compile: " + compiled.result().diagnostics());
        String generated = readGeneratedJava(compiled.outputDirectory(), "PropertyStatement.java");
        require(generated.contains("getN()"), "Expected getter-backed property read in generated Java.");
        require(generated.contains("setN("), "Expected setter-backed property write in generated Java.");
    }

    @Test
    public void propertyMutationInExpressionReportsDedicatedDiagnostic() throws Exception {
        Path sourceRoot = writeSource("""
                package dev.affogato.semantic

                class Counter(var n: int) { }

                class PropertyExpression {
                    run(): int {
                        let c = Counter(0)
                        println(c.n++)
                        return 0
                    }
                }
                """);

        try {
            compile(sourceRoot);
            throw new AssertionError("Expected compilation failure.");
        } catch (AffogatoCompilationException exception) {
            require(hasDiagnostic(exception, "AFFOGATO_PROPERTY_MUTATION_EXPR"),
                    "Expected PROPERTY_MUTATION_EXPR for postfix mutation in expression.");
        }
    }

    @Test
    public void prefixPropertyMutationInExpressionReportsDedicatedDiagnostic() throws Exception {
        Path sourceRoot = writeSource("""
                package dev.affogato.semantic

                class Counter(var n: int) { }

                class PrefixPropertyExpression {
                    run(): int {
                        let c = Counter(0)
                        println(++c.n)
                        return 0
                    }
                }
                """);

        try {
            compile(sourceRoot);
            throw new AssertionError("Expected compilation failure.");
        } catch (AffogatoCompilationException exception) {
            require(hasDiagnostic(exception, "AFFOGATO_PROPERTY_MUTATION_EXPR"),
                    "Expected PROPERTY_MUTATION_EXPR for prefix mutation in expression.");
        }
    }

    @Test
    public void compactConstructorAccessorsAreAlwaysPublic() throws Exception {
        Path sourceRoot = writeSource("""
                package dev.affogato.semantic

                class Holder(var count: int) {
                    run(): int {
                        return count
                    }
                }
                """);

        CompileOutput compiled = compile(sourceRoot);
        String generated = readGeneratedJava(compiled.outputDirectory(), "Holder.java");
        require(generated.contains("private int count;"), "Compact constructor field should stay private.");
        require(generated.contains("public int getCount()"), "Accessor getter must be public.");
        require(generated.contains("public void setCount("), "Accessor setter must be public.");
    }

    @Test
    public void crossClassPropertyChainUsesPublicAccessors() throws Exception {
        Path sourceRoot = writeSource("""
                package dev.affogato.semantic

                class Inner(var value: int) { }

                class Outer(var inner: Inner) { }

                class Reader {
                    read(o: Outer): int {
                        return o.inner.value
                    }
                }
                """);

        CompileOutput compiled = compile(sourceRoot);
        String generated = readGeneratedJava(compiled.outputDirectory(), "Reader.java");
        require(generated.contains("o.getInner().getValue()"),
                "Cross-class property chain should use public accessors, got: " + generated);
    }

    @Test
    public void untypedNullLocalReportsLocalTypeDiagnostic() throws Exception {
        Path sourceRoot = writeSource("""
                package dev.affogato.semantic

                class NullLocal {
                    run(): String {
                        let value = null
                        return "ok"
                    }
                }
                """);

        try {
            compile(sourceRoot);
            throw new AssertionError("Expected compilation failure.");
        } catch (AffogatoCompilationException exception) {
            AffogatoDiagnostic diagnostic = findDiagnostic(exception, "AFFOGATO_LOCAL_TYPE");
            require(diagnostic.line() == 5, "Expected LOCAL_TYPE at let declaration line 5, was " + diagnostic.line());
        }
    }

    @Test
    public void untargetedLambdaReportsPolyTargetTypeDiagnostic() throws Exception {
        Path sourceRoot = writeSource("""
                package dev.affogato.semantic

                class UntargetedLambda {
                    run(): String {
                        let fn = value -> value
                        return "ok"
                    }
                }
                """);

        try {
            compile(sourceRoot);
            throw new AssertionError("Expected compilation failure.");
        } catch (AffogatoCompilationException exception) {
            require(hasDiagnostic(exception, "AFFOGATO_POLY_TARGET_TYPE"),
                    "Expected POLY_TARGET_TYPE for untyped lambda initializer.");
        }
    }

    @Test
    public void primitiveInstanceOfInConditionReportsInstanceOfTypeDiagnostic() throws Exception {
        Path sourceRoot = writeSource("""
                package dev.affogato.semantic

                class PrimitiveInstanceOf {
                    run(): String {
                        if 1 is String {
                            return "bad"
                        }
                        return "ok"
                    }
                }
                """);

        try {
            compile(sourceRoot);
            throw new AssertionError("Expected compilation failure.");
        } catch (AffogatoCompilationException exception) {
            require(hasDiagnostic(exception, "AFFOGATO_INSTANCEOF_TYPE"),
                    "Expected INSTANCEOF_TYPE for primitive instance-of check.");
        }
    }

    @Test
    public void unresolvedFieldTypeReportsTypeResolutionAtFieldLine() throws Exception {
        Path sourceRoot = writeSource("""
                package dev.affogato.semantic

                class MissingFieldType {
                    var missing: MissingThing

                    run(): String {
                        return "ok"
                    }
                }
                """);

        try {
            compile(sourceRoot);
            throw new AssertionError("Expected compilation failure.");
        } catch (AffogatoCompilationException exception) {
            AffogatoDiagnostic diagnostic = findDiagnostic(exception, "AFFOGATO_TYPE_RESOLUTION");
            require(diagnostic.line() == 4,
                    "Expected TYPE_RESOLUTION at field declaration line 4, was " + diagnostic.line());
        }
    }

    @Test
    public void genericInstanceOfEmitsErasedTypeInJava() throws Exception {
        Path sourceRoot = writeSource("""
                package dev.affogato.semantic

                import java.util.List

                class InstanceOfGenerics {
                    describe(value: Object): String {
                        if value is List<String> {
                            return "list"
                        }
                        return "other"
                    }
                }
                """);

        CompileOutput compiled = compile(sourceRoot);
        String generated = readGeneratedJava(compiled.outputDirectory(), "InstanceOfGenerics.java");
        require(generated.contains("instanceof List)"), "Expected erased instanceof List, got: " + generated);
        require(!generated.contains("instanceof List<String>)"),
                "Java rejects parameterized instanceof types: " + generated);
    }

    @Test
    public void throwOnlyMethodDoesNotReportReturnFlow() throws Exception {
        Path sourceRoot = writeSource("""
                package dev.affogato.semantic

                class ThrowOnly {
                    run(): int {
                        throw new RuntimeException("stop")
                    }
                }
                """);

        CompileOutput compiled = compile(sourceRoot);
        require(!compiled.result().hasErrors(), "Throw-only method should compile: " + compiled.result().diagnostics());
        require(compiled.result().diagnostics().stream().noneMatch(d -> "AFFOGATO_RETURN_FLOW".equals(d.code())),
                "Throw should satisfy non-void return flow.");
    }

    @Test
    public void qualifiedStaticCallWithAmbiguousNullArgumentReportsCallResolution() throws Exception {
        Path workDir = Files.createTempDirectory("affogato-semantic-api");
        Path apiClasses = compileJavaApi(workDir);
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Ambiguous.aff"), """
                package dev.affogato.semantic

                class AmbiguousCall {
                    run(): String {
                        return JavaApi.ambiguous(null)
                    }
                }
                """, StandardCharsets.UTF_8);

        Path output = workDir.resolve("generated");
        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(sourceRoot)
                    .addClasspathEntry(apiClasses)
                    .outputDirectory(output)
                    .build());
            throw new AssertionError("Expected ambiguous overload to fail.");
        } catch (AffogatoCompilationException exception) {
            require(hasDiagnostic(exception, "AFFOGATO_CALL_RESOLUTION"),
                    "Expected CALL_RESOLUTION for ambiguous qualified static call.");
        }
    }

    @Test
    public void lambdaBlockBodyCompilesWithParameterInScope() throws Exception {
        Path sourceRoot = writeSource("""
                package dev.affogato.semantic

                import java.util.function.Function

                class LambdaBlockBody {
                    run(): String {
                        let mapper: Function<String, String> = value -> {
                            var suffix = "!"
                            return value + suffix
                        }
                        return mapper.apply("af")
                    }
                }
                """);

        CompileOutput compiled = compile(sourceRoot);
        require(!compiled.result().hasErrors(), "Lambda block body should compile: " + compiled.result().diagnostics());
        String generated = readGeneratedJava(compiled.outputDirectory(), "LambdaBlockBody.java");
        require(generated.contains("value -> {"), "Expected lambda block body in generated Java.");
        require(generated.contains("return value + suffix"), "Lambda parameter must remain in scope.");
    }

    private static Path writeSource(String source) throws Exception {
        Path workDir = Files.createTempDirectory("affogato-semantic");
        Path sourceRoot = workDir.resolve("src");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("App.aff"), source, StandardCharsets.UTF_8);
        return sourceRoot;
    }

    private record CompileOutput(AffogatoCompilationResult result, Path outputDirectory) {
    }

    private static CompileOutput compile(Path sourceRoot) throws Exception {
        Path output = sourceRoot.getParent().resolve("generated");
        AffogatoCompilationResult result = new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                .addSourceRoot(sourceRoot)
                .outputDirectory(output)
                .build());
        return new CompileOutput(result, output);
    }

    private static String readGeneratedJava(Path outputDirectory, String classFileName) throws Exception {
        try (var stream = Files.walk(outputDirectory)) {
            Path file = stream
                    .filter(path -> path.getFileName().toString().equals(classFileName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing generated file " + classFileName));
            return Files.readString(file, StandardCharsets.UTF_8);
        }
    }

    private static AffogatoDiagnostic findDiagnostic(AffogatoCompilationException exception, String code) {
        return exception.diagnostics().stream()
                .filter(d -> code.equals(d.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + code + ": " + exception.diagnostics()));
    }

    private static boolean hasDiagnostic(AffogatoCompilationException exception, String code) {
        return exception.diagnostics().stream().anyMatch(d -> code.equals(d.code()));
    }

    private static Path compileJavaApi(Path workDir) throws Exception {
        Path apiSourceRoot = workDir.resolve("api-src/dev/affogato/semantic");
        Files.createDirectories(apiSourceRoot);
        Files.writeString(apiSourceRoot.resolve("JavaApi.java"), """
                package dev.affogato.semantic;

                public final class JavaApi {
                    private JavaApi() {
                    }

                    public static String ambiguous(String value) {
                        return value;
                    }

                    public static String ambiguous(StringBuilder value) {
                        return value.toString();
                    }
                }
                """, StandardCharsets.UTF_8);

        Path classesDir = workDir.resolve("api-classes");
        Files.createDirectories(classesDir);
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "JDK with javac is required.");
        try (javax.tools.StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            java.util.List<java.io.File> files = java.util.List.of(apiSourceRoot.resolve("JavaApi.java").toFile());
            Iterable<? extends javax.tools.JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(files);
            Boolean ok = compiler.getTask(null, fileManager, null, java.util.List.of(
                    "--release", "21",
                    "-d", classesDir.toString()
            ), null, units).call();
            require(Boolean.TRUE.equals(ok), "JavaApi stub did not compile.");
        }
        return classesDir;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
