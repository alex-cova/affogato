package dev.affogato.golden;

import dev.affogato.golden.interop.JavaWildcardSinks;
import java.util.ArrayList;
import java.util.function.Consumer;

public class JavaWildcardsSuper {
    public String run() {
        final ArrayList<Number> values = new ArrayList<Number>();
        JavaWildcardSinks.addInteger(values);
        final java.util.function.Consumer<java.lang.Object> consumer = JavaWildcardSinks.objectConsumer();
        JavaWildcardSinks.consumeString(consumer);
        return values.size() + "";
    }

}
