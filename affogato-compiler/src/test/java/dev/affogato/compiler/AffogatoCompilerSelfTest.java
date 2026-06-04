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
import java.util.List;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

public final class AffogatoCompilerSelfTest {
    public static void main(String[] args) throws Exception {
        Result result = JUnitCore.runClasses(AffogatoCompilerSelfTest.class);
        for (Failure failure : result.getFailures()) {
            System.err.println(failure);
            System.err.println(failure.getTrace());
        }
        if (!result.wasSuccessful()) {
            throw new AssertionError(result.getFailureCount() + " compiler self-test(s) failed.");
        }
    }

    @Test
    public void generatesSourcesForCurrentLanguageFeatures() throws Exception {
        Path workDir = newWorkDir();
        Path apiClasses = compileJavaApi(workDir);
        Path sourceRoot = workDir.resolve("src/main/affogato/dev/affogato/test");
        Files.createDirectories(sourceRoot);

        Files.writeString(sourceRoot.resolve("Person.aff"), """
                package dev.affogato.test

                class Person(var name: String!, let age: int) {
                    override String toString() {
                        return name
                    }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(sourceRoot.resolve("Greetings.aff"), """
                package dev.affogato.test

                func String.shout(): String {
                    return this + "!"
                }

                func String.twice(): String {
                    return this + this
                }

                func Person.greeting(prefix: String!): String {
                    return prefix + " " + this.name
                }

                func Person.describe(): String {
                    return name + " " + age
                }

                func CharSequence.lengthPlus(): int {
                    return this.length() + 1
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(sourceRoot.resolve("App.aff"), """
                package dev.affogato.test

                import static dev.affogato.test.JavaApi.identity

                class App {
                    static func main(args: String[]) {
                        let person = Person(age = 1, name = "Affogato")
                        println(person.name)
                        let trimmedName = person.name.trim()
                        println(trimmedName)
                        let greeting = person.greeting(prefix = "Hola")
                        println(greeting)
                        println("hi".shout())
                        let chained = "ab".shout().twice()
                        println(chained)
                        let personDescribed = person.describe()
                        println(personDescribed)
                        let lengthPlus = "abc".lengthPlus()
                        println(lengthPlus)
                        let api = JavaApi(label = "interop")
                        println(api.label)
                        println(api.direct)
                        api.mutableCount = 3
                        println(api.mutableCount)
                        let joined: String = JavaApi.join(second = "B", first = "A")
                        println(joined)
                        let overloaded = JavaApi.overloaded(fallback = JavaApi.any(), value = "typed")
                        println(overloaded)
                        let packageVisible = JavaApi.packageChoice(fallback = JavaApi.any(), value = "pkg")
                        println(packageVisible)
                        let numeric = JavaApi.numeric(other = 2L, value = 1)
                        println(numeric)
                        let varargs = JavaApi.varargs(prefix = "p", values = "a")
                        println(varargs)
                        let specific = JavaApi.specificity(value = "typed")
                        println(specific)
                        let defaults = JavaDefaultImpl()
                        let decorated = defaults.decorated(value = "default")
                        println(decorated)
                        let genericIdentity = JavaApi.identity(value = "generic")
                        println(genericIdentity)
                        let importedIdentity = identity(value = "imported")
                        println(importedIdentity)
                        let genericNumber = JavaApi.numberIdentity(value = 7)
                        println(genericNumber)
                        let genericSpecific = JavaApi.genericSpecific(value = "specific")
                        println(genericSpecific)
                        let lambdaValue = JavaApi.lambdaResult(fn = x -> x)
                        println(lambdaValue)
                        let methodRefValue = JavaApi.lambdaResult(fn = String::trim)
                        println(methodRefValue)
                        let supplied = JavaApi.functional(fn = () -> "ready")
                        println(supplied)
                        let mapped = JavaApi.functional(fn = value -> value)
                        println(mapped)
                        let suppliedClosure = JavaApi.functional { "ready" }
                        println(suppliedClosure)
                        let mappedClosure = JavaApi.lambdaResult { value -> value }
                        println(mappedClosure)
                        let mixedClosure = JavaApi.mapWith("seed") { item -> item }
                        println(mixedClosure)
                        JavaApi.functional { "discarded" }
                        let pinged = JavaApi.combined()
                        let pong = pinged.ping()
                        println(pong)
                        let router = DerivedRoute()
                        let routed = router.label(value = "x")
                        println(routed)
                        let tagged = JavaApi.taggedName()
                        let described = JavaApi.describe(value = tagged)
                        println(described)
                        let seqs = JavaApi.sequences()
                        let picked = JavaApi.pick(values = seqs)
                        println(picked)
                        let box = GenericBox<String>("seed")
                        let echoed = box.echo(value = "echoed")
                        println(echoed)
                        let derived = DerivedOverloads()
                        let inherited = derived.pick(value = "child")
                        println(inherited)
                        let affogatoOverload = LocalOverloads.route(name = JavaApi.any(), count = 2L)
                        println(affogatoOverload)
                        var names = List<String>()
                        names.add("x")
                        let wildcard = JavaApi.wildcard(values = names)
                        println(wildcard)
                        let sum: long = 1 + 2L
                        if sum > 0 {
                            println(sum)
                        }
                        for name in names {
                            println(name)
                        }

                        if not(person.name.isBlank()) {
                            println("ready")
                        }

                        guard person.name.length() > 0 else {
                            return
                        }

                        var value: Object = "42"
                        if value is String {
                            println(value as String)
                        }

                        let ternaryResult = sum > 0 ? "pos" : "non"
                        println(ternaryResult)

                        try {
                            let parsed = Integer.parseInt("42")
                            println(parsed)
                        } catch (NumberFormatException e) {
                            println(e.getMessage())
                        }

                        let numbers: int[] = [1, 2, 3]
                        println(numbers.length)

                        var counter = 0
                        while counter < 2 {
                            counter = counter + 1
                        }
                        println(counter)

                        try {
                            println("attempt")
                        } catch (RuntimeException e) {
                            println(e.getMessage())
                        } finally {
                            println("cleanup")
                        }

                    }

                    String greet(who: String) {
                        return "Hello, " + who
                    }

                    String greetSelf() {
                        return this.greet("World")
                    }

                    String branchReturn(value: int) {
                        if value > 0 {
                            return "positive"
                        } else if value == 0 {
                            return "zero"
                        } else {
                            return "negative"
                        }
                    }

                    String tryReturn(value: String) {
                        try {
                            return Integer.parseInt(value) + ""
                        } catch (NumberFormatException e) {
                            return "nan"
                        }
                    }

                    String finallyReturn() {
                        try {
                            println("before")
                        } finally {
                            return "done"
                        }
                    }

                    String blockReturn() {
                        {
                            return "block"
                        }
                    }
                }

                class LocalOverloads {
                    static String route(name: String, count: int) {
                        return name + count
                    }

                    static String route(count: long, name: Object) {
                        return name.toString() + count
                    }
                }

                class BaseOverloads {
                    String pick(value: CharSequence) {
                        return value.toString()
                    }

                    String pick(value: Object) {
                        return value.toString()
                    }
                }

                class DerivedOverloads: BaseOverloads {
                }

                class BaseRoute {
                    label(value: String): String {
                        return "base" + value
                    }
                }

                class DerivedRoute: BaseRoute {
                    override label(value: String): String {
                        return "derived" + value
                    }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(sourceRoot.resolve("Types.aff"), """
                package dev.affogato.test

                @Deprecated
                enum Color {
                    RED, GREEN, BLUE
                }

                @SuppressWarnings("unused")
                record Coord(x: int, y: int) {
                    sum(): int {
                        return x + y
                    }
                }

                interface Drawable {
                    func draw()
                    describe(): String
                    default func label() {
                        println("Drawable")
                    }
                }

                class Rectangle: Drawable {
                    let width: int
                    let height: int

                    constructor(width: int, height: int) {
                        this.width = width
                        this.height = height
                    }

                    override func draw() {
                        switch width {
                            case 0 -> println("zero width")
                            default -> println("width: " + width)
                        }
                    }

                    override describe(): String {
                        return "Rectangle"
                    }

                    @Deprecated
                    category(): String {
                        let label = switch width {
                            case 0 -> "empty"
                            case 1 -> "thin"
                            default -> "filled"
                        }
                        return label
                    }

                    profile(): String {
                        return switch height {
                            case 0 -> "flat"
                            default -> "solid"
                        }
                    }

                    origin(): int {
                        let c = new Coord(width, height)
                        return c.x + c.sum()
                    }

                    summary(): String {
                        return "Rectangle ${width}x${height}, area $width"
                    }
                }
                """, StandardCharsets.UTF_8);

        Path generatedRoot = workDir.resolve("generated");
        AffogatoCompilationResult result = new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                .addSourceRoot(workDir.resolve("src/main/affogato"))
                .addClasspathEntry(apiClasses)
                .outputDirectory(generatedRoot)
                .build());

        require(result.generatedFiles().size() == 12, "Expected twelve generated Java files.");
        String personJava = Files.readString(generatedRoot.resolve("dev/affogato/test/Person.java"));
        String appJava = Files.readString(generatedRoot.resolve("dev/affogato/test/App.java"));
        String extensionsJava = Files.readString(generatedRoot.resolve("dev/affogato/test/GreetingsExtensions.java"));

        // Extension functions: the holder exposes them as static methods with the receiver as the first
        // parameter, and call sites are rewritten to static dispatch (receiver passed as the first argument).
        requireContains(extensionsJava, "public final class GreetingsExtensions {");
        requireContains(extensionsJava, "public static String shout(String $this) {");
        requireContains(extensionsJava, "return $this + \"!\";");
        requireContains(extensionsJava, "public static String greeting(Person $this, @NotNull String prefix) {");
        requireContains(extensionsJava, "return prefix + \" \" + $this.getName();");
        // Implicit receiver: bare `name`/`age` resolve to the receiver inside the extension body.
        requireContains(extensionsJava, "public static String describe(Person $this) {");
        requireContains(extensionsJava, "return $this.getName() + \" \" + $this.getAge();");
        // Java supertype hierarchy: an extension on CharSequence applies to a String receiver.
        requireContains(extensionsJava, "public static int lengthPlus(CharSequence $this) {");
        requireContains(appJava, "final String greeting = dev.affogato.test.GreetingsExtensions.greeting(person, \"Hola\");");
        requireContains(appJava, "System.out.println(dev.affogato.test.GreetingsExtensions.shout(\"hi\"));");
        // Chained extension calls.
        requireContains(appJava, "final String chained = dev.affogato.test.GreetingsExtensions.twice(dev.affogato.test.GreetingsExtensions.shout(\"ab\"));");
        requireContains(appJava, "final String personDescribed = dev.affogato.test.GreetingsExtensions.describe(person);");
        requireContains(appJava, "final int lengthPlus = dev.affogato.test.GreetingsExtensions.lengthPlus(\"abc\");");

        requireContains(personJava, "private @NotNull String name;");
        requireContains(personJava, "Objects.requireNonNull(name, \"name\");");
        requireContains(personJava, "@Override");
        requireContains(appJava, "final Person person = new Person(\"Affogato\", 1);");
        requireContains(appJava, "final java.lang.String trimmedName = person.getName().trim();");
        requireContains(appJava, "final JavaApi api = new JavaApi(\"interop\");");
        requireContains(appJava, "System.out.println(api.getLabel());");
        requireContains(appJava, "System.out.println(api.direct);");
        requireContains(appJava, "api.mutableCount = 3;");
        requireContains(appJava, "System.out.println(api.mutableCount);");
        requireContains(appJava, "final String joined = JavaApi.join(\"A\", \"B\");");
        requireContains(appJava, "final java.lang.String overloaded = JavaApi.overloaded(\"typed\", JavaApi.any());");
        requireContains(appJava, "final java.lang.String packageVisible = JavaApi.packageChoice(\"pkg\", JavaApi.any());");
        requireContains(appJava, "final java.lang.String numeric = JavaApi.numeric(1, 2L);");
        requireContains(appJava, "final java.lang.String varargs = JavaApi.varargs(\"p\", \"a\");");
        requireContains(appJava, "final java.lang.String specific = JavaApi.specificity(\"typed\");");
        requireContains(appJava, "final JavaDefaultImpl defaults = new JavaDefaultImpl();");
        requireContains(appJava, "final java.lang.String decorated = defaults.decorated(\"default\");");
        requireContains(appJava, "final String genericIdentity = JavaApi.identity(\"generic\");");
        requireContains(appJava, "final String importedIdentity = identity(\"imported\");");
        requireContains(appJava, "final java.lang.Integer genericNumber = JavaApi.numberIdentity(7);");
        requireContains(appJava, "final java.lang.String genericSpecific = JavaApi.genericSpecific(\"specific\");");
        requireContains(appJava, "final java.lang.String lambdaValue = JavaApi.lambdaResult(x -> x);");
        requireContains(appJava, "final java.lang.String methodRefValue = JavaApi.lambdaResult(String::trim);");
        requireContains(appJava, "final java.lang.String supplied = JavaApi.functional(() -> \"ready\");");
        requireContains(appJava, "final java.lang.String mapped = JavaApi.functional(value -> value);");
        requireContains(appJava, "final java.lang.String suppliedClosure = JavaApi.functional(() -> \"ready\");");
        requireContains(appJava, "final java.lang.String mappedClosure = JavaApi.lambdaResult(value -> value);");
        requireContains(appJava, "final java.lang.String mixedClosure = JavaApi.mapWith(\"seed\", item -> item);");
        requireContains(appJava, "JavaApi.functional(() -> \"discarded\");");
        requireContains(appJava, "JavaApi.combined()");
        requireContains(appJava, "final java.lang.String pong = pinged.ping();");
        requireContains(appJava, "final String routed = router.label(\"x\");");
        requireContains(appJava, "final java.lang.String described = JavaApi.describe(tagged);");
        requireContains(appJava, "final java.lang.CharSequence picked = JavaApi.pick(seqs);");
        requireContains(appJava, "final GenericBox<String> box = new GenericBox<String>(\"seed\");");
        requireContains(appJava, "final String echoed = box.echo(\"echoed\");");
        requireContains(appJava, "final DerivedOverloads derived = new DerivedOverloads();");
        requireContains(appJava, "final String inherited = derived.pick(\"child\");");
        requireContains(appJava, "final String affogatoOverload = LocalOverloads.route(2L, JavaApi.any());");
        requireContains(appJava, "java.util.ArrayList<String> names = new java.util.ArrayList<String>();");
        requireContains(appJava, "final java.lang.String wildcard = JavaApi.wildcard(names);");
        requireContains(appJava, "final long sum = 1 + 2L;");
        requireContains(appJava, "if (sum > 0) {");
        requireContains(appJava, "for (var name : names) {");
        requireContains(appJava, "System.out.println(person.getName());");
        requireContains(appJava, "if (!(person.getName().isBlank())) {");
        requireContains(appJava, "if (!(person.getName().length() > 0)) {");
        requireContains(appJava, "value instanceof String");
        requireContains(appJava, "System.out.println(((String) value));");
        requireContains(appJava, "final String ternaryResult = sum > 0 ? \"pos\" : \"non\";");
        requireContains(appJava, "try {");
        requireContains(appJava, "} catch (NumberFormatException e) {");
        requireContains(appJava, "final int[] numbers = new int[]{1, 2, 3};");
        requireContains(appJava, "while (counter < 2) {");
        requireContains(appJava, "counter = counter + 1;");
        requireContains(appJava, "} finally {");
        requireContains(appJava, "System.out.println(\"cleanup\");");
        requireContains(appJava, "return this.greet(\"World\");");
        requireContains(appJava, "return Integer.parseInt(value) + \"\";");
        requireContains(appJava, "return \"block\";");

        String colorJava = Files.readString(generatedRoot.resolve("dev/affogato/test/Color.java"));
        String drawableJava = Files.readString(generatedRoot.resolve("dev/affogato/test/Drawable.java"));
        String rectangleJava = Files.readString(generatedRoot.resolve("dev/affogato/test/Rectangle.java"));
        requireContains(colorJava, "public enum Color {");
        requireContains(colorJava, "RED, GREEN, BLUE");
        requireContains(drawableJava, "public interface Drawable {");
        requireContains(drawableJava, "void draw();");
        requireContains(drawableJava, "String describe();");
        requireContains(drawableJava, "default void label() {");
        requireContains(rectangleJava, "public class Rectangle implements Drawable {");
        requireContains(rectangleJava, "@Override");
        requireContains(rectangleJava, "switch (width) {");
        requireContains(rectangleJava, "case 0 -> System.out.println(\"zero width\");");
        requireContains(rectangleJava, "default -> System.out.println(\"width: \" + width);");
        requireContains(rectangleJava, "final var label = switch (width) {");
        requireContains(rectangleJava, "case 0 -> \"empty\";");
        requireContains(rectangleJava, "default -> \"filled\";");
        requireContains(rectangleJava, "return switch (height) {");
        requireContains(rectangleJava, "case 0 -> \"flat\";");
        requireContains(rectangleJava, "final Coord c = new Coord(width, height);");
        requireContains(rectangleJava, "return c.x() + c.sum();");

        String coordJava = Files.readString(generatedRoot.resolve("dev/affogato/test/Coord.java"));
        requireContains(coordJava, "@SuppressWarnings(\"unused\")");
        requireContains(coordJava, "public record Coord(int x, int y) {");
        requireContains(coordJava, "public int sum() {");
        requireContains(coordJava, "return x + y;");
        requireContains(colorJava, "@Deprecated");
        requireContains(rectangleJava, "@Deprecated");
        requireContains(rectangleJava, "return \"Rectangle \" + (width) + \"x\" + (height) + \", area \" + (width);");

        compileGeneratedJava(generatedRoot, workDir.resolve("classes"), apiClasses);
    }

    @Test
    public void invalidGuardFailsWithFlowDiagnostic() throws Exception {
        Path workDir = newWorkDir();
        verifyInvalidGuardFails(workDir);
    }

    @Test
    public void ambiguousOverloadFailsWithNamedArgumentDiagnostic() throws Exception {
        Path workDir = newWorkDir();
        Path apiClasses = compileJavaApi(workDir);
        verifyAmbiguousOverloadFails(workDir, apiClasses);
    }

    @Test
    public void lambdaArityFailsWithNamedArgumentDiagnostic() throws Exception {
        Path workDir = newWorkDir();
        Path apiClasses = compileJavaApi(workDir);
        verifyLambdaArityFails(workDir, apiClasses);
    }

    @Test
    public void intersectionBoundFailsWithNamedArgumentDiagnostic() throws Exception {
        Path workDir = newWorkDir();
        Path apiClasses = compileJavaApi(workDir);
        verifyIntersectionBoundFails(workDir, apiClasses);
    }

    @Test
    public void wildcardBoundFailsWithNamedArgumentDiagnostic() throws Exception {
        Path workDir = newWorkDir();
        Path apiClasses = compileJavaApi(workDir);
        verifyWildcardBoundFails(workDir, apiClasses);
    }

    @Test
    public void typeCheckerFailuresReportSpecificDiagnostics() throws Exception {
        Path workDir = newWorkDir();
        Path apiClasses = compileJavaApi(workDir);
        verifyTypeCheckerFails(workDir, apiClasses);
    }

    @Test
    public void badExtensionReceiverFailsWithCallResolutionDiagnostic() throws Exception {
        Path workDir = newWorkDir();
        verifyExtensionResolutionFails(workDir);
    }

    @Test
    public void unsupportedProductionSubsetEdgesReportExplicitDiagnostics() throws Exception {
        Path workDir = newWorkDir();
        verifyUnsupportedSubsetEdgesFail(workDir);
    }

    private static Path newWorkDir() throws Exception {
        return Files.createTempDirectory("affogato-compiler-test");
    }

    private static void verifyExtensionResolutionFails(Path workDir) throws Exception {
        Path sourceRoot = workDir.resolve("badext/src/main/affogato/dev/affogato/test");
        Files.createDirectories(sourceRoot);
        // The extension is declared on String, so calling it on an int receiver must not resolve.
        Files.writeString(sourceRoot.resolve("BadExtension.aff"), """
                package dev.affogato.test

                func String.shout(): String {
                    return this + "!"
                }

                class BadExtension {
                    func run() {
                        let count = 5
                        count.shout()
                    }
                }
                """, StandardCharsets.UTF_8);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(workDir.resolve("badext/src/main/affogato"))
                    .outputDirectory(workDir.resolve("badext/generated"))
                    .build());
            throw new AssertionError("Extension call on the wrong receiver type should fail compilation.");
        } catch (AffogatoCompilationException exception) {
            boolean found = exception.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code().equals("AFFOGATO_CALL_RESOLUTION"));
            require(found, "Extension call on the wrong receiver type should report AFFOGATO_CALL_RESOLUTION.");
        }
    }

    private static void verifyUnsupportedSubsetEdgesFail(Path workDir) throws Exception {
        Path sourceRoot = workDir.resolve("unsupported/src/main/affogato/dev/affogato/test");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Unsupported.aff"), """
                package dev.affogato.test

                class Unsupported {
                    func run() {
                        var text: String? = null
                        println(text?.length())
                        println(text ?: "fallback")
                        println(text!!)
                    }
                }
                """, StandardCharsets.UTF_8);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(workDir.resolve("unsupported/src/main/affogato"))
                    .outputDirectory(workDir.resolve("unsupported/generated"))
                    .build());
            throw new AssertionError("Unsupported subset edges should fail compilation.");
        } catch (AffogatoCompilationException exception) {
            require(hasDiagnostic(exception, "AFFOGATO_UNSUPPORTED_SAFE_CALL"), "Safe-call use should report AFFOGATO_UNSUPPORTED_SAFE_CALL.");
            require(hasDiagnostic(exception, "AFFOGATO_UNSUPPORTED_ELVIS"), "Elvis use should report AFFOGATO_UNSUPPORTED_ELVIS.");
            require(hasDiagnostic(exception, "AFFOGATO_UNSUPPORTED_NOT_NULL_ASSERTION"), "Not-null assertion use should report AFFOGATO_UNSUPPORTED_NOT_NULL_ASSERTION.");
        }
    }

    private static Path compileJavaApi(Path workDir) throws Exception {
        Path apiSourceRoot = workDir.resolve("api-src/dev/affogato/test");
        Files.createDirectories(apiSourceRoot);
        Files.writeString(apiSourceRoot.resolve("JavaApi.java"), """
                package dev.affogato.test;

                public final class JavaApi {
                    private final String label;
                    public final String direct = "direct";
                    public final Nested nested = new Nested();
                    public int mutableCount;

                    public JavaApi(String label) {
                        this.label = label;
                    }

                    public String getLabel() {
                        return label;
                    }

                    public static String join(String first, String second) {
                        return first + second;
                    }

                    public static Object any() {
                        return new Object();
                    }

                    public static String overloaded(String value, Object fallback) {
                        return value;
                    }

                    public static String overloaded(Object value, String fallback) {
                        return fallback;
                    }

                    public static String stringOnly(String value) {
                        return value;
                    }

                    public static boolean booleanOnly(boolean value) {
                        return value;
                    }

                    static String packageChoice(String value, Object fallback) {
                        return value;
                    }

                    public static String numeric(int value, long other) {
                        return value + ":" + other;
                    }

                    public static String numeric(long value, int other) {
                        return value + ":" + other;
                    }

                    public static String varargs(String prefix, String... values) {
                        return prefix + values.length;
                    }

                    public static String specificity(CharSequence value) {
                        return value.toString();
                    }

                    public static String specificity(Object value) {
                        return value.toString();
                    }

                    public static <T> T identity(T value) {
                        return value;
                    }

                    public static <T extends Number> T numberIdentity(T value) {
                        return value;
                    }

                    public static <T extends CharSequence> String genericSpecific(T value) {
                        return value.toString();
                    }

                    public static String genericSpecific(Object value) {
                        return value.toString();
                    }

                    public static String lambdaResult(java.util.function.Function<String, String> fn) {
                        return fn.apply("lambda");
                    }

                    public static String lambdaResult(Object fn) {
                        return fn.toString();
                    }

                    public static String functional(java.util.function.Supplier<String> fn) {
                        return fn.get();
                    }

                    public static String functional(java.util.function.Function<String, String> fn) {
                        return fn.apply("x");
                    }

                    public static String mapWith(String value, java.util.function.Function<String, String> fn) {
                        return fn.apply(value);
                    }

                    public static String ambiguous(String value) {
                        return value;
                    }

                    public static String ambiguous(StringBuilder value) {
                        return value.toString();
                    }

                    public static String wildcard(java.util.List<? extends CharSequence> values) {
                        return values.get(0).toString();
                    }

                    public static String wildcard(Object values) {
                        return values.toString();
                    }

                    public static String wildcardOnly(java.util.List<? extends CharSequence> values) {
                        return values.get(0).toString();
                    }

                    public static Combined combined() {
                        return () -> "pong";
                    }

                    public static <T extends Tagged & Named> String describe(T value) {
                        return value.tag() + ":" + value.name();
                    }

                    public static TaggedName taggedName() {
                        return new TaggedName();
                    }

                    public static OnlyTagged onlyTagged() {
                        return new OnlyTagged();
                    }

                    public static java.util.List<? extends CharSequence> sequences() {
                        java.util.List<String> list = new java.util.ArrayList<>();
                        list.add("seq");
                        return list;
                    }

                    public static <T> T pick(java.util.List<T> values) {
                        return values.get(0);
                    }
                }

                interface Pingable {
                    String ping();
                }

                interface Echoable {
                    String ping();
                }

                interface Combined extends Pingable, Echoable {
                }

                interface Tagged {
                    String tag();
                }

                interface Named {
                    String name();
                }

                final class TaggedName implements Tagged, Named {
                    public String tag() {
                        return "tag";
                    }

                    public String name() {
                        return "name";
                    }
                }

                final class OnlyTagged implements Tagged {
                    public String tag() {
                        return "tag";
                    }
                }

                final class Nested {
                    public final String name = "nested";
                }

                interface JavaDefault {
                    default String decorated(String value) {
                        return value;
                    }
                }

                final class JavaDefaultImpl implements JavaDefault {
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(apiSourceRoot.resolve("GenericBox.java"), """
                package dev.affogato.test;

                public final class GenericBox<T> {
                    private final T value;

                    public GenericBox(T value) {
                        this.value = value;
                    }

                    public T get() {
                        return value;
                    }

                    public T echo(T value) {
                        return value;
                    }
                }
                """, StandardCharsets.UTF_8);

        Path apiClasses = workDir.resolve("api-classes");
        Files.createDirectories(apiClasses);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "A JDK with javac is required.");
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<File> apiSources = new ArrayList<>();
            try (var stream = Files.list(apiSourceRoot)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".java"))
                        .map(Path::toFile)
                        .forEach(apiSources::add);
            }
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(apiSources);
            Boolean ok = compiler.getTask(null, fileManager, null, List.of(
                    "--release", "21",
                    "-parameters",
                    "-d", apiClasses.toString()
            ), null, compilationUnits).call();
            require(Boolean.TRUE.equals(ok), "Java interop fixture did not compile.");
        }
        return apiClasses;
    }

    private static void compileGeneratedJava(Path generatedRoot, Path classesDir, Path extraClasspath) throws Exception {
        Files.createDirectories(classesDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "A JDK with javac is required.");

        List<File> javaFiles = new ArrayList<>();
        try (var stream = Files.walk(generatedRoot)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::toFile)
                    .forEach(javaFiles::add);
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(javaFiles);
            List<String> options = List.of(
                    "--release", "21",
                    "-classpath", System.getProperty("java.class.path") + File.pathSeparator + extraClasspath,
                    "-d", classesDir.toString()
            );
            Boolean ok = compiler.getTask(null, fileManager, null, options, null, compilationUnits).call();
            require(Boolean.TRUE.equals(ok), "Generated Java did not compile.");
        }
    }

    private static void verifyInvalidGuardFails(Path workDir) throws Exception {
        Path sourceRoot = workDir.resolve("bad/src/main/affogato/dev/affogato/test");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("BadGuard.aff"), """
                package dev.affogato.test

                class BadGuard {
                    func run() {
                        guard true else {
                            println("bad")
                        }
                    }
                }
                """, StandardCharsets.UTF_8);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(workDir.resolve("bad/src/main/affogato"))
                    .outputDirectory(workDir.resolve("bad/generated"))
                    .build());
            throw new AssertionError("Invalid guard should fail compilation.");
        } catch (AffogatoCompilationException exception) {
            boolean found = exception.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("AFFOGATO_GUARD_FLOW"));
            require(found, "Invalid guard should report AFFOGATO_GUARD_FLOW.");
        }
    }

    private static void verifyAmbiguousOverloadFails(Path workDir, Path apiClasses) throws Exception {
        Path sourceRoot = workDir.resolve("ambiguous/src/main/affogato/dev/affogato/test");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("BadOverload.aff"), """
                package dev.affogato.test

                class BadOverload {
                    func run() {
                        let value = JavaApi.ambiguous(value = null)
                        println(value)
                    }
                }
                """, StandardCharsets.UTF_8);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(workDir.resolve("ambiguous/src/main/affogato"))
                    .addClasspathEntry(apiClasses)
                    .outputDirectory(workDir.resolve("ambiguous/generated"))
                    .build());
            throw new AssertionError("Ambiguous overload should fail compilation.");
        } catch (AffogatoCompilationException exception) {
            boolean found = exception.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code().equals("AFFOGATO_NAMED_ARGS")
                            && diagnostic.message().contains("Ambiguous overload"));
            require(found, "Ambiguous overload should report a specific AFFOGATO_NAMED_ARGS diagnostic.");
        }
    }

    private static void verifyLambdaArityFails(Path workDir, Path apiClasses) throws Exception {
        Path sourceRoot = workDir.resolve("lambda/src/main/affogato/dev/affogato/test");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("BadLambda.aff"), """
                package dev.affogato.test

                class BadLambda {
                    func run() {
                        let value = JavaApi.lambdaResult(fn = () -> "nullary")
                        println(value)
                    }
                }
                """, StandardCharsets.UTF_8);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(workDir.resolve("lambda/src/main/affogato"))
                    .addClasspathEntry(apiClasses)
                    .outputDirectory(workDir.resolve("lambda/generated"))
                    .build());
            throw new AssertionError("Lambda arity mismatch should fail compilation.");
        } catch (AffogatoCompilationException exception) {
            boolean found = exception.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code().equals("AFFOGATO_NAMED_ARGS"));
            require(found, "Lambda arity mismatch should report AFFOGATO_NAMED_ARGS.");
        }
    }

    private static void verifyIntersectionBoundFails(Path workDir, Path apiClasses) throws Exception {
        Path sourceRoot = workDir.resolve("intersection/src/main/affogato/dev/affogato/test");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("BadIntersection.aff"), """
                package dev.affogato.test

                class BadIntersection {
                    func run() {
                        let partial = JavaApi.onlyTagged()
                        let value = JavaApi.describe(value = partial)
                        println(value)
                    }
                }
                """, StandardCharsets.UTF_8);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(workDir.resolve("intersection/src/main/affogato"))
                    .addClasspathEntry(apiClasses)
                    .outputDirectory(workDir.resolve("intersection/generated"))
                    .build());
            throw new AssertionError("Unsatisfied intersection bound should fail compilation.");
        } catch (AffogatoCompilationException exception) {
            boolean found = exception.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code().equals("AFFOGATO_NAMED_ARGS"));
            require(found, "Unsatisfied intersection bound should report AFFOGATO_NAMED_ARGS.");
        }
    }

    private static void verifyWildcardBoundFails(Path workDir, Path apiClasses) throws Exception {
        Path sourceRoot = workDir.resolve("wildcard/src/main/affogato/dev/affogato/test");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("BadWildcard.aff"), """
                package dev.affogato.test

                class BadWildcard {
                    func run() {
                        var numbers = List<Integer>()
                        let value = JavaApi.wildcardOnly(values = numbers)
                        println(value)
                    }
                }
                """, StandardCharsets.UTF_8);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(workDir.resolve("wildcard/src/main/affogato"))
                    .addClasspathEntry(apiClasses)
                    .outputDirectory(workDir.resolve("wildcard/generated"))
                    .build());
            throw new AssertionError("Wildcard bound mismatch should fail compilation.");
        } catch (AffogatoCompilationException exception) {
            boolean found = exception.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code().equals("AFFOGATO_NAMED_ARGS"));
            require(found, "Wildcard bound mismatch should report AFFOGATO_NAMED_ARGS.");
        }
    }

    private static void verifyTypeCheckerFails(Path workDir, Path apiClasses) throws Exception {
        Path sourceRoot = workDir.resolve("types/src/main/affogato/dev/affogato/test");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("BadTypes.aff"), """
                package dev.affogato.test

                class BadTypes {
                    var badField: int = "field"
                    var missingFieldType: MissingThing

                    String badReturn() {
                        return 1
                    }

                    String missingReturn() {
                        println("missing")
                    }

                    func badLocal() {
                        let typed: int = "local"
                    }

                    func badLocalType() {
                        let typed: MissingThing = null
                        println(typed)
                    }

                    func badGenericTypeArgument() {
                        let values: List<MissingThing> = List<MissingThing>()
                        println(values)
                    }

                    func badLet() {
                        let value: int = 1
                        value = 2
                    }

                    func badNull() {
                        let value: String! = null
                    }

                    func badNullReassignment() {
                        var value: String! = "x"
                        value = null
                        println(value)
                    }

                    func badNullableLocal() {
                        let maybe: String? = null
                        let value: String! = maybe
                        println(value)
                    }

                    func badNullableParameter(maybe: String?) {
                        let value: String! = maybe
                        println(value)
                    }

                    func badNullableTernary(flag: boolean) {
                        let maybe: String? = null
                        let value: String! = flag ? "ok" : maybe
                        println(value)
                    }

                    func badCast() {
                        var value: String = "x"
                        println(value as Integer)
                    }

                    func badExpressionCast() {
                        println((1 + 2) as String)
                    }

                    func badCastTarget() {
                        var value: Object = "x"
                        println(value as MissingThing)
                    }

                    func badCondition() {
                        if 1 {
                            println("bad")
                        }
                    }

                    func badInstanceOfSource() {
                        if 1 is String {
                            println("bad")
                        }
                    }

                    func badInstanceOfTarget() {
                        var value: Object = "x"
                        if value is MissingThing {
                            println("bad")
                        }
                    }

                    func badCompoundCondition() {
                        if 1 && true {
                            println("bad")
                        }
                    }

                    func badRelationalCondition() {
                        if "left" < "right" {
                            println("bad")
                        }
                    }

                    func badTernaryAssignment(flag: boolean) {
                        let value: String = flag ? "ok" : 1
                        println(value)
                    }

                    func badUntypedTernary(flag: boolean) {
                        let value = flag ? "ok" : 1
                        println(value)
                    }

                    func badTernaryCondition(flag: boolean) {
                        if flag ? true : 1 {
                            println("bad")
                        }
                    }

                    func badBooleanInitializer() {
                        let value: boolean = !1
                        println(value)
                    }

                    func badRelationalInitializer() {
                        let value: boolean = "left" < "right"
                        println(value)
                    }

                    func badArithmeticInitializer() {
                        let value = true + 1
                        println(value)
                    }

                    func badEqualityInitializer() {
                        let value = 1 == "one"
                        println(value)
                    }

                    func badArrayAssignment() {
                        let values: int[] = ["bad"]
                        println(values.length)
                    }

                    func badArrayElementExpression() {
                        let values: boolean[] = [!1]
                        println(values.length)
                    }

                    func badUnknownIdentifier() {
                        println(missingValue)
                    }

                    func badThrow() {
                        throw "bad"
                    }

                    func badCatchType() {
                        try {
                            println("try")
                        } catch (String e) {
                            println(e)
                        }
                    }

                    func badUntargetedLambda() {
                        let fn = value -> value
                        println(fn)
                    }

                    func badUntargetedMethodReference() {
                        let fn = String::trim
                        println(fn)
                    }

                    func badForIterable() {
                        for value in 1 {
                            println(value)
                        }
                    }

                    func badSwitchLocal(width: int) {
                        let label: String = switch width {
                            case 0 -> "zero"
                            default -> 1
                        }
                        println(label)
                    }

                    func badSwitchLabel(width: int) {
                        switch width {
                            case "zero" -> println("bad")
                            default -> println("ok")
                        }
                    }

                    func badSwitchExpressionLabel(width: int) {
                        let label = switch width {
                            case "zero" -> "bad"
                            default -> "ok"
                        }
                        println(label)
                    }

                    func badSwitchSelector() {
                        let selector: long = 1L
                        switch selector {
                            case 1L -> println("bad")
                            default -> println("ok")
                        }
                    }

                    badSwitchReturn(width: int): String {
                        return switch width {
                            case 0 -> "zero"
                            default -> 1
                        }
                    }

                    func badCall() {
                        var value: String = "x"
                        value.noSuchMethod()
                    }

                    func badChainedCall() {
                        var value: String = "x"
                        value.trim().noSuchMethod()
                    }

                    func badTernaryJavaCall(flag: boolean) {
                        let value = JavaApi.stringOnly(flag ? "ok" : 1)
                        println(value)
                    }

                    func badBooleanJavaCall() {
                        let value = JavaApi.booleanOnly(!1)
                        println(value)
                    }

                    func badTernaryAffogatoCall(flag: boolean) {
                        let value = acceptString(flag ? "ok" : 1)
                        println(value)
                    }

                    acceptString(value: String): String {
                        return value
                    }

                    func badProperty() {
                        var value: String = "x"
                        println(value.noSuchProperty)
                    }

                    func badPropertyChain() {
                        let api = JavaApi("interop")
                        println(api.nested.noSuchProperty)
                    }

                    func badType() {
                        let value = MissingThing()
                        println(value)
                    }

                    func badConstructor() {
                        let shorthand = NeedsInt("bad")
                        let explicit = new NeedsInt("bad")
                        let badBoolShorthand = NeedsBoolean(!1)
                        let badBoolExplicit = new NeedsBoolean(!1)
                        println(shorthand)
                        println(explicit)
                        println(badBoolShorthand)
                        println(badBoolExplicit)
                    }

                    func badGenericAssignment() {
                        let names: List<String> = List<Integer>()
                        println(names)
                    }

                    func badJavaFieldAssignments() {
                        let api = JavaApi("interop")
                        api.mutableCount = "bad"
                        api.direct = "bad"
                    }
                }

                class NeedsInt {
                    constructor(value: int) {
                        println(value)
                    }
                }

                class NeedsBoolean {
                    constructor(value: boolean) {
                        println(value)
                    }
                }
                """, StandardCharsets.UTF_8);

        try {
            new AffogatoCompiler().compile(AffogatoCompilerOptions.builder()
                    .addSourceRoot(workDir.resolve("types/src/main/affogato"))
                    .addClasspathEntry(apiClasses)
                    .outputDirectory(workDir.resolve("types/generated"))
                    .build());
            throw new AssertionError("Type checker failures should fail compilation.");
        } catch (AffogatoCompilationException exception) {
            require(hasDiagnostic(exception, "AFFOGATO_FIELD_TYPE"), "Bad field initializer should report AFFOGATO_FIELD_TYPE.");
            require(hasDiagnostic(exception, "AFFOGATO_RETURN_TYPE"), "Bad return should report AFFOGATO_RETURN_TYPE.");
            require(hasDiagnostic(exception, "AFFOGATO_RETURN_FLOW"), "Missing return should report AFFOGATO_RETURN_FLOW.");
            require(hasDiagnostic(exception, "AFFOGATO_ASSIGNMENT_TYPE"), "Bad local initializer should report AFFOGATO_ASSIGNMENT_TYPE.");
            require(hasDiagnostic(exception, "AFFOGATO_LET_ASSIGN"), "Bad let reassignment should report AFFOGATO_LET_ASSIGN.");
            require(hasDiagnostic(exception, "AFFOGATO_CAST_TYPE"), "Bad cast should report AFFOGATO_CAST_TYPE.");
            require(hasDiagnostic(exception, "AFFOGATO_CONDITION_TYPE"), "Bad condition should report AFFOGATO_CONDITION_TYPE.");
            require(hasDiagnostic(exception, "AFFOGATO_OPERATOR_TYPE"), "Bad operators should report AFFOGATO_OPERATOR_TYPE.");
            require(hasDiagnostic(exception, "AFFOGATO_TERNARY_TYPE"), "Bad ternary branches should report AFFOGATO_TERNARY_TYPE.");
            require(hasDiagnostic(exception, "AFFOGATO_INSTANCEOF_TYPE"), "Bad instance-of source should report AFFOGATO_INSTANCEOF_TYPE.");
            require(hasDiagnostic(exception, "AFFOGATO_CALL_RESOLUTION"), "Bad method call should report AFFOGATO_CALL_RESOLUTION.");
            require(hasDiagnostic(exception, "AFFOGATO_PROPERTY_RESOLUTION"), "Bad property should report AFFOGATO_PROPERTY_RESOLUTION.");
            require(hasDiagnostic(exception, "AFFOGATO_TYPE_RESOLUTION"), "Bad type should report AFFOGATO_TYPE_RESOLUTION.");
            require(hasDiagnostic(exception, "AFFOGATO_CONSTRUCTOR_RESOLUTION"), "Bad constructor should report AFFOGATO_CONSTRUCTOR_RESOLUTION.");
            require(hasDiagnostic(exception, "AFFOGATO_SWITCH_LABEL_TYPE"), "Bad switch labels should report AFFOGATO_SWITCH_LABEL_TYPE.");
            require(hasDiagnostic(exception, "AFFOGATO_SWITCH_SELECTOR_TYPE"), "Bad switch selectors should report AFFOGATO_SWITCH_SELECTOR_TYPE.");
            require(hasDiagnostic(exception, "AFFOGATO_FOR_ITERABLE_TYPE"), "Bad for-in iterable should report AFFOGATO_FOR_ITERABLE_TYPE.");
            require(hasDiagnostic(exception, "AFFOGATO_IDENTIFIER_RESOLUTION"), "Bad identifiers should report AFFOGATO_IDENTIFIER_RESOLUTION.");
            require(hasDiagnostic(exception, "AFFOGATO_POLY_TARGET_TYPE"), "Untargeted poly expressions should report AFFOGATO_POLY_TARGET_TYPE.");
            require(hasDiagnostic(exception, "AFFOGATO_THROW_TYPE"), "Bad throw expressions should report AFFOGATO_THROW_TYPE.");
            require(hasDiagnostic(exception, "AFFOGATO_CATCH_TYPE"), "Bad catch types should report AFFOGATO_CATCH_TYPE.");
        }
    }

    private static boolean hasDiagnostic(AffogatoCompilationException exception, String code) {
        return exception.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals(code));
    }

    private static void requireContains(String text, String expected) {
        require(text.contains(expected), "Missing expected text: " + expected + System.lineSeparator() + text);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
