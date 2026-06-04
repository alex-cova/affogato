package dev.affogato.golden;

import java.util.function.Function;
import java.util.function.Supplier;

public class LambdaTargetOverloads {
    public static String use(Supplier<String> fn) {
        final String value = fn.get();
        return "supplier:" + value;
    }

    public static String use(Function<String,String> fn) {
        final String value = fn.apply("x");
        return "function:" + value;
    }

}
