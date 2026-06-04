package dev.affogato.golden;

import java.util.function.Function;

public class LambdaGenericFunctionalInterface {
    public <T, R> R apply(T value, Function<T,R> fn) {
        return fn.apply(value);
    }

    public String run() {
        final String upper = apply("affogato", value -> value.toUpperCase());
        final Integer length = apply("latte", value -> value.length());
        return upper + ":" + length;
    }

}
