package dev.affogato.golden;

import java.util.function.Function;
import java.util.function.Supplier;

public class OverloadsLambdaTarget {
    public String run() {
        final String supplied = LambdaTargetOverloads.use(() -> "ready");
        final String mapped = LambdaTargetOverloads.use(value -> value.toUpperCase());
        return supplied + ":" + mapped;
    }

}
