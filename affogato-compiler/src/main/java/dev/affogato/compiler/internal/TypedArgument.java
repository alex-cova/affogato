package dev.affogato.compiler.internal;

record TypedArgument(String name, String expression, TypeGuess type, AstExpression ast) {
    TypedArgument(String name, String expression, TypeGuess type) {
        this(name, expression, type, new UnknownExpression(expression));
    }
}
