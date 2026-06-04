package dev.affogato.golden.interop;

import java.util.List;

public final class JavaWildcardNumbers {
    private JavaWildcardNumbers() {
    }

    public static Number sum(List<? extends Number> values) {
        int total = 0;
        for (Number value : values) {
            total += value.intValue();
        }
        return total;
    }
}
