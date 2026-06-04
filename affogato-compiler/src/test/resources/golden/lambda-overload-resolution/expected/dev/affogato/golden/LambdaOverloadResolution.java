package dev.affogato.golden;

import java.util.function.Function;
import java.util.function.Supplier;

public class LambdaOverloadResolution {
    public String run() {
        final String supplied = LambdaOverloadPicker.pick(() -> "ready");
        final String mapped = LambdaOverloadPicker.pick(value -> value.toUpperCase());
        return supplied + ":" + mapped;
    }

}
