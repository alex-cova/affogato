package dev.affogato.compiler.internal;

import java.util.List;

sealed interface AstExpression permits
        LiteralExpression,
        ArrayLiteralExpression,
        ArrayAccessExpression,
        ClassLiteralExpression,
        IdentifierExpression,
        CallExpression,
        ConstructorExpression,
        PropertyAccessExpression,
        AssignmentExpression,
        TernaryExpression,
        InstanceOfExpression,
        BinaryExpression,
        UnaryExpression,
        CastExpression,
        LambdaExpression,
        MethodReferenceExpression,
        SwitchExpressionNode,
        SafeCallExpression,
        ElvisExpression,
        NamedArgumentExpression,
        UnknownExpression,
        UnsupportedExpression {
    String source();

    TypeGuess resolvedType();
}

record LiteralExpression(String source, TypeGuess resolvedType) implements AstExpression {
}

record ArrayLiteralExpression(String source, List<AstExpression> elements, TypeGuess resolvedType) implements AstExpression {
}

record ArrayAccessExpression(String source, AstExpression receiver, AstExpression index, TypeGuess resolvedType) implements AstExpression {
}

record ClassLiteralExpression(String source, String typeName, TypeGuess resolvedType) implements AstExpression {
}

record IdentifierExpression(String source, String name, TypeGuess resolvedType) implements AstExpression {
}

record CallExpression(
        String source,
        String name,
        AstExpression receiver,
        List<AstExpression> arguments,
        TypeGuess resolvedType
) implements AstExpression {
}

record ConstructorExpression(
        String source,
        String typeName,
        List<AstExpression> arguments,
        TypeGuess resolvedType
) implements AstExpression {
}

record PropertyAccessExpression(String source, AstExpression receiver, String property, TypeGuess resolvedType) implements AstExpression {
}

record AssignmentExpression(String source, String operator, AstExpression target, AstExpression value, TypeGuess resolvedType) implements AstExpression {
}

record TernaryExpression(String source, AstExpression condition, AstExpression thenExpression, AstExpression elseExpression, TypeGuess resolvedType) implements AstExpression {
}

record InstanceOfExpression(String source, AstExpression expression, String targetType, TypeGuess resolvedType) implements AstExpression {
}

record BinaryExpression(String source, String operator, AstExpression left, AstExpression right, TypeGuess resolvedType) implements AstExpression {
}

record UnaryExpression(String source, String operator, AstExpression expression, TypeGuess resolvedType) implements AstExpression {
}

record CastExpression(String source, AstExpression expression, String targetType, TypeGuess resolvedType) implements AstExpression {
}

record LambdaExpression(
        String source,
        int parameterArity,
        TypeGuess resolvedType,
        String parametersSource,
        AstExpression expressionBody
) implements AstExpression {
    LambdaExpression(String source, int parameterArity, TypeGuess resolvedType) {
        this(source, parameterArity, resolvedType, "", null);
    }
}

record MethodReferenceExpression(String source, TypeGuess resolvedType) implements AstExpression {
}

record SwitchExpressionNode(String source, TypeGuess resolvedType) implements AstExpression {
}

record SafeCallExpression(String source, AstExpression receiver, String property, TypeGuess resolvedType) implements AstExpression {
}

record ElvisExpression(String source, AstExpression left, AstExpression right, TypeGuess resolvedType) implements AstExpression {
}

record NamedArgumentExpression(String name, AstExpression expression, TypeGuess resolvedType) implements AstExpression {
    @Override
    public String source() {
        return name + " = " + expression.source();
    }
}

record UnknownExpression(String source) implements AstExpression {
    @Override
    public TypeGuess resolvedType() {
        return TypeGuess.unknown();
    }
}

record UnsupportedExpression(String source, String code, String message) implements AstExpression {
    @Override
    public TypeGuess resolvedType() {
        return TypeGuess.unknown();
    }
}
