package dev.affogato.golden;

import java.util.function.Function;
import java.util.function.Supplier;

public class LambdaOverloadPicker {
    public static String pick(Supplier<String> fn) {
        final String value = fn.get();
        return "supplier:" + value;
    }

    public static String pick(Function<String,String> fn) {
        final String value = fn.apply("af");
        return "function:" + value;
    }

}
