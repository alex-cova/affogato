package dev.affogato.compiler.internal;

record TypedExpression(String javaSource, TypeGuess resolvedType, AstExpression ast) {
    static TypedExpression untyped(String source) {
        return new TypedExpression(source, TypeGuess.unknown(), new UnknownExpression(source));
    }
}
