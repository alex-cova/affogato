package dev.affogato.golden;

import static java.lang.Math.max;
import static java.lang.Math.min;

public class StaticImportMultiple {
    public int run() {
        final int low = min(1, 2);
        final int high = max(3, 4);
        return low + high;
    }

}
