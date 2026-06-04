package dev.affogato.golden;

import java.util.function.Function;
import java.util.function.Supplier;

public class MethodReferenceConstructor {
    public String run() {
        final Supplier<StringBuilder> empty = StringBuilder::new;
        final Function<String,StringBuilder> fromString = StringBuilder::new;
        final java.lang.String first = empty.get().append("af").toString();
        final java.lang.String second = fromString.apply("fogato").toString();
        return first + second;
    }

}
