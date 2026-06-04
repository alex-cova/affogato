package dev.affogato.golden;

import java.util.function.Supplier;

public class LambdaCapturesMutableLocal {
    public String run() {
        String suffix = "!";
        final Supplier<String> supplier = () -> "af" + suffix;
        return supplier.get();
    }

}
