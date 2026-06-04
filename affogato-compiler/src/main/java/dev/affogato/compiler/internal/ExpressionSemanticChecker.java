package dev.affogato.compiler.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExpressionSemanticChecker {
    interface Support {
        String stripOuterParens(String text);

        boolean containsTopLevelMethodReference(String value);

        int topLevelOperatorIndex(String value, List<String> operators);

        int lambdaParameterArity(String header);

        int stringLiteralEnd(String expression, int openQuoteIndex);

        String stripNullableSuffix(String typeName);

        int namedArgumentEquals(String expression);

        int callOpenParen(String value);

        String callNameBefore(String expression, int openIndex);

        String simpleTypeName(String type);

        String constructorImplementation(String typeName);

        List<String> splitTopLevel(String text, char delimiter);

        boolean startsWithBooleanNegation(String value);

        boolean isStringType(TypeGuess type);

        boolean isNumericType(TypeGuess type);

        String promotedNumericType(String left, String right);

        String variableType(String name);
    }

    private final Support support;

    ExpressionSemanticChecker(Support support) {
        this.support = support;
    }

    AstExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return new UnknownExpression("");
        }
        String value = support.stripOuterParens(expression.trim());
        if (value.contains("?.")) {
            return new UnsupportedExpression(value, "AFFOGATO_UNSUPPORTED_SAFE_CALL", "Safe-call expressions are not in the production subset; use an explicit null check.");
        }
        if (value.contains("?:")) {
            return new UnsupportedExpression(value, "AFFOGATO_UNSUPPORTED_ELVIS", "Elvis expressions are not in the production subset; use a ternary expression.");
        }
        if (value.contains("!!")) {
            return new UnsupportedExpression(value, "AFFOGATO_UNSUPPORTED_NOT_NULL_ASSERTION", "Not-null assertion expressions are not in the production subset; use an explicit cast or null check.");
        }

        int arrowIndex = support.topLevelOperatorIndex(value, List.of("->"));
        if (arrowIndex >= 0) {
            int arity = support.lambdaParameterArity(value.substring(0, arrowIndex));
            return new LambdaExpression(value, arity, TypeGuess.lambda(arity));
        }
        if (support.containsTopLevelMethodReference(value)) {
            return new MethodReferenceExpression(value, TypeGuess.lambda());
        }
        if (value.startsWith("switch ") || value.startsWith("switch(")) {
            return new SwitchExpressionNode(value, TypeGuess.unknown());
        }
        if (value.equals("null")) {
            return new LiteralExpression(value, TypeGuess.nullLiteral());
        }
        if (value.startsWith("\"") && support.stringLiteralEnd(value, 0) == value.length()) {
            return new LiteralExpression(value, TypeGuess.of("String"));
        }
        if (value.equals("true") || value.equals("false")) {
            return new LiteralExpression(value, TypeGuess.of("boolean"));
        }
        if (value.matches("-?\\d+[lL]")) {
            return new LiteralExpression(value, TypeGuess.of("long"));
        }
        if (value.matches("-?\\d+")) {
            return new LiteralExpression(value, TypeGuess.of("int"));
        }
        if (value.matches("-?\\d+\\.\\d+[fF]")) {
            return new LiteralExpression(value, TypeGuess.of("float"));
        }
        if (value.matches("-?\\d+\\.\\d+[dD]?")) {
            return new LiteralExpression(value, TypeGuess.of("double"));
        }

        Matcher affogatoCast = Pattern.compile("^(.+)\\s+as\\s+([A-Za-z_][A-Za-z0-9_.$]*(?:<[^>]+>)?\\??)$").matcher(value);
        if (affogatoCast.matches()) {
            String targetType = support.stripNullableSuffix(affogatoCast.group(2));
            return new CastExpression(value, parse(affogatoCast.group(1)), targetType, TypeGuess.of(targetType));
        }
        Matcher javaCast = Pattern.compile("^\\(\\(([^)]+)\\)\\s+(.+)\\)$").matcher(value);
        if (javaCast.matches()) {
            String targetType = support.stripNullableSuffix(javaCast.group(1).trim());
            return new CastExpression(value, parse(javaCast.group(2)), targetType, TypeGuess.of(targetType));
        }

        int assignment = support.namedArgumentEquals(value);
        if (assignment > 0 && !value.substring(0, assignment).contains(",")) {
            AstExpression target = parse(value.substring(0, assignment));
            AstExpression assigned = parse(value.substring(assignment + 1));
            return new AssignmentExpression(value, target, assigned, assigned.resolvedType());
        }

        int ternaryQ = support.topLevelOperatorIndex(value, List.of("?"));
        if (ternaryQ > 0 && !Character.isJavaIdentifierPart(value.charAt(ternaryQ - 1))) {
            String rest = value.substring(ternaryQ + 1).trim();
            int colonIdx = support.topLevelOperatorIndex(rest, List.of(":"));
            if (colonIdx >= 0) {
                AstExpression thenExpr = parse(rest.substring(0, colonIdx));
                AstExpression elseExpr = parse(rest.substring(colonIdx + 1));
                TypeGuess type = thenExpr.resolvedType().isKnown() && !thenExpr.resolvedType().isNullLiteral()
                        ? thenExpr.resolvedType()
                        : elseExpr.resolvedType();
                return new TernaryExpression(value, parse(value.substring(0, ternaryQ)), thenExpr, elseExpr, type.isKnown() ? type : TypeGuess.unknown());
            }
        }

        for (String operator : List.of("||", "&&", "==", "!=", "<=", ">=", "<", ">")) {
            int index = support.topLevelOperatorIndex(value, List.of(operator));
            if (index > 0) {
                return new BinaryExpression(value, operator, parse(value.substring(0, index)), parse(value.substring(index + operator.length())), TypeGuess.of("boolean"));
            }
        }
        for (String operator : List.of("+", "-", "*", "/", "%")) {
            int index = support.topLevelOperatorIndex(value, List.of(operator));
            if (index > 0) {
                AstExpression left = parse(value.substring(0, index));
                AstExpression right = parse(value.substring(index + operator.length()));
                TypeGuess type = numericOrStringType(operator, left.resolvedType(), right.resolvedType());
                return new BinaryExpression(value, operator, left, right, type);
            }
        }
        if (support.startsWithBooleanNegation(value)) {
            String operand = value.startsWith("not(") && value.endsWith(")")
                    ? value.substring("not(".length(), value.length() - 1)
                    : value.substring(1);
            return new UnaryExpression(value, "!", parse(operand), TypeGuess.of("boolean"));
        }

        Matcher newExpression = Pattern.compile("^new\\s+([A-Za-z_$][A-Za-z0-9_.$]*(?:<[^>]+>)?(?:\\[\\])*)\\s*\\((.*)\\)$").matcher(value);
        if (newExpression.matches()) {
            return new ConstructorExpression(value, newExpression.group(1), argumentExpressions(newExpression.group(2)), TypeGuess.of(newExpression.group(1)));
        }

        Matcher constructor = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_.$]*(?:<[^>]+>)?)\\s*\\((.*)\\)$").matcher(value);
        if (constructor.matches()) {
            String typeName = constructor.group(1);
            String simpleName = support.simpleTypeName(typeName);
            if (!simpleName.isBlank() && Character.isUpperCase(simpleName.charAt(0))) {
                return new ConstructorExpression(value, typeName, argumentExpressions(constructor.group(2)), TypeGuess.of(support.constructorImplementation(typeName)));
            }
        }

        int callOpen = support.callOpenParen(value);
        if (callOpen > 0) {
            String callName = support.callNameBefore(value, callOpen);
            List<AstExpression> arguments = argumentExpressions(value.substring(callOpen + 1, value.length() - 1));
            if (!callName.isBlank()) {
                String simple = support.simpleTypeName(callName);
                if (!simple.isBlank() && Character.isUpperCase(simple.charAt(0)) && !callName.contains(".")) {
                    return new ConstructorExpression(value, callName, arguments, TypeGuess.of(support.constructorImplementation(callName)));
                }
                AstExpression receiver = new UnknownExpression("");
                int dot = callName.lastIndexOf('.');
                if (dot > 0) {
                    receiver = parse(callName.substring(0, dot));
                }
                return new CallExpression(value, callName, receiver, arguments, TypeGuess.unknown());
            }
        }

        int dot = value.lastIndexOf('.');
        if (dot > 0 && dot < value.length() - 1 && value.indexOf('(') < 0) {
            AstExpression receiver = parse(value.substring(0, dot));
            String property = value.substring(dot + 1);
            return new PropertyAccessExpression(value, receiver, property, TypeGuess.unknown());
        }

        String variableType = support.variableType(value);
        if (variableType != null) {
            return new IdentifierExpression(value, value, TypeGuess.of(variableType));
        }
        if (value.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            return new IdentifierExpression(value, value, TypeGuess.unknown());
        }
        return new UnknownExpression(value);
    }

    private List<AstExpression> argumentExpressions(String args) {
        if (args.isBlank()) {
            return List.of();
        }
        List<AstExpression> expressions = new ArrayList<>();
        for (String part : support.splitTopLevel(args, ',')) {
            int equals = support.namedArgumentEquals(part);
            expressions.add(parse(equals > 0 ? part.substring(equals + 1) : part));
        }
        return expressions;
    }

    private TypeGuess numericOrStringType(String operator, TypeGuess left, TypeGuess right) {
        if (operator.equals("+") && (support.isStringType(left) || support.isStringType(right))) {
            return TypeGuess.of("String");
        }
        if (support.isNumericType(left) && support.isNumericType(right)) {
            return TypeGuess.of(support.promotedNumericType(left.javaType(), right.javaType()));
        }
        return TypeGuess.unknown();
    }
}
