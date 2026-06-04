package dev.affogato.golden.interop;

import java.util.List;
import java.util.function.Consumer;

public final class JavaWildcardSinks {
    private JavaWildcardSinks() {
    }

    public static void addInteger(List<? super Integer> values) {
        values.add(7);
    }

    public static void consumeString(Consumer<? super String> consumer) {
        consumer.accept("affogato");
    }

    public static Consumer<Object> objectConsumer() {
        return value -> {
        };
    }
}
