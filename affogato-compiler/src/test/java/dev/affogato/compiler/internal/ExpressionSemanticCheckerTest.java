package dev.affogato.compiler.internal;

import org.junit.Test;
import java.util.List;
import java.util.Set;
import static dev.affogato.compiler.internal.TranspilerTypes.*;

public final class ExpressionSemanticCheckerTest {
    @Test
    public void genericMapConstructorParsesViaAntlr() {
        AstExpression ast = new ExpressionSemanticChecker(new StubSupport()).parse("Map<String, List<Integer>>()");
        require(ast instanceof ConstructorExpression, "Expected generic constructor, got: " + ast.getClass().getSimpleName());
        ConstructorExpression constructor = (ConstructorExpression) ast;
        require(constructor.typeName().startsWith("Map<String"), "Expected Map<String,...> constructor, got: " + constructor.typeName());
    }

    @Test
    public void switchExpressionParsesViaAntlr() {
        String expression = """
                switch (1) {
                    case 1 -> 2
                    default -> 0
                }""";
        AstExpression ast = new ExpressionSemanticChecker(new StubSupport()).parse(expression.strip());
        require(ast instanceof SwitchExpressionNode, "Expected switch expression node, got: " + ast.getClass().getSimpleName());
    }

    @Test
    public void invalidExpressionFallsBackToUnknownWithoutRegex() {
        AstExpression ast = new ExpressionSemanticChecker(new StubSupport()).parse("???");
        require(ast instanceof UnknownExpression, "Expected unknown node without regex fallback, got: " + ast.getClass().getSimpleName());
    }

    @Test
    public void javaResolverResolvesJdkAppendAndBufferLimit() {
        CompilationUnit unit = new CompilationUnit(
                java.nio.file.Path.of("Test.aff"),
                "",
                "dev.affogato.test",
                List.of("java.nio.ByteBuffer"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        try (JavaResolver resolver = new JavaResolver(List.of(), null)) {
            TypeGuess append = resolver.methodReturnType(
                    "StringBuilder",
                    "append",
                    List.of(new TypedArgument("", "c", TypeGuess.of("char"))),
                    unit).orElse(TypeGuess.unknown());
            require(append.isKnown(), "Expected StringBuilder.append(char) to resolve.");

            TypeGuess limit = resolver.methodReturnType(
                    "ByteBuffer",
                    "limit",
                    List.of(new TypedArgument("", "0", TypeGuess.of("int"))),
                    unit).orElse(TypeGuess.unknown());
            require(limit.isKnown(), "Expected ByteBuffer.limit(int) to resolve.");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class StubSupport implements ExpressionSemanticChecker.Support {
        @Override
        public String stripOuterParens(String text) {
            return text;
        }

        @Override
        public boolean containsTopLevelMethodReference(String value) {
            return false;
        }

        @Override
        public int topLevelOperatorIndex(String value, java.util.List<String> operators) {
            return -1;
        }

        @Override
        public int lambdaParameterArity(String header) {
            return 0;
        }

        @Override
        public int stringLiteralEnd(String expression, int openQuoteIndex) {
            return -1;
        }

        @Override
        public String stripNullableSuffix(String typeName) {
            return typeName;
        }

        @Override
        public int namedArgumentEquals(String expression) {
            return -1;
        }

        @Override
        public int callOpenParen(String value) {
            return -1;
        }

        @Override
        public String callNameBefore(String expression, int openIndex) {
            return "";
        }

        @Override
        public String simpleTypeName(String type) {
            int dot = type.lastIndexOf('.');
            return dot >= 0 ? type.substring(dot + 1) : type;
        }

        @Override
        public String constructorImplementation(String typeName) {
            return typeName;
        }

        @Override
        public java.util.List<String> splitTopLevel(String text, char delimiter) {
            return java.util.List.of(text);
        }

        @Override
        public boolean startsWithBooleanNegation(String value) {
            return false;
        }

        @Override
        public boolean isStringType(TypeGuess type) {
            return false;
        }

        @Override
        public boolean isNumericType(TypeGuess type) {
            return false;
        }

        @Override
        public String promotedNumericType(String left, String right) {
            return "int";
        }

        @Override
        public String variableType(String name) {
            return null;
        }

        @Override
        public boolean typeExists(String qualifiedName) {
            return false;
        }

        @Override
        public TypeGuess propertyResultType(TypeGuess receiverType, String property) {
            return TypeGuess.unknown();
        }

        @Override
        public TypeGuess callResultType(String callName, AstExpression receiver, java.util.List<AstExpression> arguments) {
            return TypeGuess.unknown();
        }
    }
}
