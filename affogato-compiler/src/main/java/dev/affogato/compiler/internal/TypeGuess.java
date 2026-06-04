package dev.affogato.compiler.internal;

record TypeGuess(String javaType, boolean isNullLiteral, boolean isLambda, int lambdaArity) {
    static final int UNKNOWN_ARITY = -1;

    static TypeGuess of(String javaType) {
        return new TypeGuess(javaType, false, false, UNKNOWN_ARITY);
    }

    static TypeGuess nullLiteral() {
        return new TypeGuess("null", true, false, UNKNOWN_ARITY);
    }

    static TypeGuess lambda() {
        return lambda(UNKNOWN_ARITY);
    }

    static TypeGuess lambda(int arity) {
        return new TypeGuess("<lambda>", false, true, arity);
    }

    static TypeGuess unknown() {
        return new TypeGuess("", false, false, UNKNOWN_ARITY);
    }

    boolean isKnown() {
        return isNullLiteral || isLambda || !javaType.isBlank();
    }
}
