package dev.affogato.golden;

import java.util.function.Function;
import java.util.function.Supplier;
import static java.lang.Math.max;

public class Functional {
    public static String apply(String value, Function<String,String> fn) {
        return fn.apply(value);
    }

    public static String supply(Supplier<String> fn) {
        return fn.get();
    }

    public String run() {
        final String lambdaValue = Functional.apply("x", value -> value);
        final String methodRefValue = Functional.apply(" trim ", String::trim);
        final String supplied = Functional.supply(() -> "ready");
        final int bigger = max(2, 3);
        return lambdaValue + methodRefValue + supplied + bigger;
    }

}
